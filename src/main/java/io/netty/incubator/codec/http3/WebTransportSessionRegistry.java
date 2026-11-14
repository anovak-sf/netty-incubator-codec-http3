/*
 * Copyright 2025 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.incubator.codec.http3;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static io.netty.incubator.codec.http3.WebTransportCodecUtils.WT_BUFFERED_STREAM_REJECTED;
import static io.netty.incubator.codec.http3.WebTransportCodecUtils.WT_SESSION_GONE;

/** Per-connection sessions and bounded buffers for streams and datagrams arriving before CONNECT. */
final class WebTransportSessionRegistry {

    static final AttributeKey<WebTransportSessionRegistry> REGISTRY_KEY =
            AttributeKey.valueOf(WebTransportSessionRegistry.class, "WT_SESSION_REGISTRY");
    static final int MAX_BUFFERED_STREAMS = 16;
    static final int MAX_BUFFERED_STREAM_BYTES = 65536;
    static final int MAX_BUFFERED_DATAGRAMS = 64;
    static final int MAX_BUFFERED_DATAGRAM_BYTES = 65536;

    private final Map<Long, WebTransportSession> sessions = new HashMap<>();
    private final List<PendingStream> pendingStreams = new ArrayList<>();
    private final List<PendingDatagram> pendingDatagrams = new ArrayList<>();
    // Keep recent closed IDs without growing with the lifetime number of CONNECT requests.
    private final Set<Long> closedSessions = new LinkedHashSet<>();
    private int pendingDatagramBytes;
    private boolean closed;

    static WebTransportSessionRegistry getOrCreate(Channel channel) {
        WebTransportSessionRegistry registry = channel.attr(REGISTRY_KEY).get();
        if (registry == null) {
            registry = new WebTransportSessionRegistry();
            WebTransportSessionRegistry existing = channel.attr(REGISTRY_KEY).setIfAbsent(registry);
            if (existing != null) {
                registry = existing;
            } else {
                WebTransportSessionRegistry created = registry;
                channel.closeFuture().addListener(future -> created.close());
            }
        }
        return registry;
    }

    @Nullable
    static WebTransportSessionRegistry get(Channel channel) {
        return channel.attr(REGISTRY_KEY).get();
    }

    static void initStream(ChannelHandlerContext ctx, long sessionId, boolean bidirectional) {
        if ((sessionId & 3) != 0) {
            ctx.pipeline().replace(ctx.name(), null, Http3UnidirectionalStreamInboundHandler.ReleaseHandler.INSTANCE);
            Http3CodecUtils.connectionError(ctx, Http3ErrorCode.H3_ID_ERROR,
                    "WebTransport session ID is not a client-initiated bidirectional stream: " + sessionId, false);
            return;
        }
        WebTransportSessionRegistry registry = getOrCreate(ctx.channel().parent());
        ctx.pipeline().replace(ctx.name(), null, new PendingStream(registry, sessionId, bidirectional));
    }

    void register(long sessionId, WebTransportSession session) {
        List<PendingStream> streams = new ArrayList<>();
        List<PendingDatagram> datagrams = new ArrayList<>();
        synchronized (this) {
            if (closed || session.isClosed()) {
                return;
            }
            sessions.put(sessionId, session);
            for (PendingStream stream : pendingStreams) {
                if (stream.sessionId == sessionId) {
                    streams.add(stream);
                }
            }
            for (Iterator<PendingDatagram> it = pendingDatagrams.iterator(); it.hasNext();) {
                PendingDatagram datagram = it.next();
                if (datagram.sessionId == sessionId) {
                    it.remove();
                    pendingDatagramBytes -= datagram.payload.readableBytes();
                    datagrams.add(datagram);
                }
            }
        }
        for (PendingStream stream : streams) {
            if (stream.ctx.executor().inEventLoop()) {
                stream.activate(session);
            } else {
                stream.ctx.executor().execute(() -> stream.activate(session));
            }
        }
        for (PendingDatagram datagram : datagrams) {
            deliverDatagram(session, datagram.payload);
        }
    }

    void deregister(long sessionId) {
        List<PendingStream> streams = new ArrayList<>();
        synchronized (this) {
            sessions.remove(sessionId);
            closedSessions.add(sessionId);
            if (closedSessions.size() > 1024) {
                Iterator<Long> it = closedSessions.iterator();
                it.next();
                it.remove();
            }
            for (PendingStream stream : pendingStreams) {
                if (stream.sessionId == sessionId) {
                    streams.add(stream);
                }
            }
            for (Iterator<PendingDatagram> it = pendingDatagrams.iterator(); it.hasNext();) {
                PendingDatagram datagram = it.next();
                if (datagram.sessionId == sessionId) {
                    it.remove();
                    pendingDatagramBytes -= datagram.payload.readableBytes();
                    datagram.payload.release();
                }
            }
        }
        for (PendingStream stream : streams) {
            stream.ctx.executor().execute(() -> stream.reject(WT_SESSION_GONE));
        }
    }

    @Nullable
    synchronized WebTransportSession find(long sessionId) {
        return sessions.get(sessionId);
    }

    /** Takes ownership of the HTTP Datagram payload after its Quarter Stream ID has been removed. */
    void receiveDatagram(long sessionId, ByteBuf payload) {
        WebTransportSession session;
        synchronized (this) {
            session = sessions.get(sessionId);
            if (session == null) {
                if (!closed && !closedSessions.contains(sessionId)
                        && pendingDatagrams.size() < MAX_BUFFERED_DATAGRAMS
                        && payload.readableBytes() <= MAX_BUFFERED_DATAGRAM_BYTES - pendingDatagramBytes) {
                    pendingDatagrams.add(new PendingDatagram(sessionId, payload));
                    pendingDatagramBytes += payload.readableBytes();
                } else {
                    payload.release();
                }
                return;
            }
        }
        deliverDatagram(session, payload);
    }

    private static void deliverDatagram(WebTransportSession session, ByteBuf payload) {
        try {
            if (!session.isClosed()) {
                session.listener().onDatagram(session, payload);
            }
        } catch (Throwable cause) {
            session.sessionStream().pipeline().fireExceptionCaught(cause);
        } finally {
            payload.release();
        }
    }

    private void close() {
        List<PendingStream> streams;
        synchronized (this) {
            closed = true;
            sessions.clear();
            streams = new ArrayList<>(pendingStreams);
            for (PendingDatagram datagram : pendingDatagrams) {
                datagram.payload.release();
            }
            pendingDatagrams.clear();
            pendingDatagramBytes = 0;
        }
        for (PendingStream stream : streams) {
            stream.ctx.executor().execute(() -> stream.reject(WT_SESSION_GONE));
        }
    }

    private static final class PendingDatagram {
        final long sessionId;
        final ByteBuf payload;

        PendingDatagram(long sessionId, ByteBuf payload) {
            this.sessionId = sessionId;
            this.payload = payload;
        }
    }

    private static final class PendingStream extends ChannelInboundHandlerAdapter {
        private final WebTransportSessionRegistry registry;
        private final long sessionId;
        private final boolean bidirectional;
        private final List<Object> messages = new ArrayList<>();
        private final List<Object> events = new ArrayList<>();
        private ChannelHandlerContext ctx;
        private ScheduledFuture<?> timeout;
        private int bytes;
        private boolean removed;

        PendingStream(WebTransportSessionRegistry registry, long sessionId, boolean bidirectional) {
            this.registry = registry;
            this.sessionId = sessionId;
            this.bidirectional = bidirectional;
        }

        @Override
        public void handlerAdded(ChannelHandlerContext ctx) {
            this.ctx = ctx;
            WebTransportSession session;
            int rejection = 0;
            synchronized (registry) {
                session = registry.sessions.get(sessionId);
                if (session == null) {
                    if (registry.closed || registry.closedSessions.contains(sessionId)) {
                        rejection = WT_SESSION_GONE;
                    } else if (registry.pendingStreams.size() >= MAX_BUFFERED_STREAMS) {
                        rejection = WT_BUFFERED_STREAM_REJECTED;
                    } else {
                        registry.pendingStreams.add(this);
                    }
                }
            }
            if (session != null) {
                activate(session);
            } else if (rejection != 0) {
                reject(rejection);
            } else {
                timeout = ctx.executor().schedule(() -> reject(WT_BUFFERED_STREAM_REJECTED), 10, TimeUnit.SECONDS);
            }
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            int size = msg instanceof ByteBuf ? ((ByteBuf) msg).readableBytes() : 0;
            if (size == 0) {
                ReferenceCountUtil.release(msg);
                return;
            }
            if (size > MAX_BUFFERED_STREAM_BYTES - bytes) {
                ReferenceCountUtil.release(msg);
                reject(WT_BUFFERED_STREAM_REJECTED);
            } else {
                bytes += size;
                messages.add(msg);
            }
        }

        @Override
        public void channelReadComplete(ChannelHandlerContext ctx) {
            // Replay completion after buffered data reaches the application's handlers.
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object event) {
            events.add(event);
        }

        void activate(WebTransportSession session) {
            if (removed) {
                return;
            }
            QuicStreamChannel channel = (QuicStreamChannel) ctx.channel();
            if (!session.addStream(channel)) {
                reject(WT_SESSION_GONE);
                return;
            }
            try {
                if (bidirectional) {
                    session.listener().onBidirectionalStream(session, channel);
                } else {
                    session.listener().onUnidirectionalStream(session, channel);
                }
                if (removed) {
                    return;
                }
                if (session.isClosed()) {
                    reject(WT_SESSION_GONE);
                    return;
                }
                List<Object> replay = new ArrayList<>(messages);
                List<Object> replayEvents = new ArrayList<>(events);
                messages.clear();
                events.clear();
                ctx.pipeline().remove(this);
                for (Object msg : replay) {
                    ctx.fireChannelRead(msg);
                }
                if (!replay.isEmpty()) {
                    ctx.fireChannelReadComplete();
                }
                for (Object event : replayEvents) {
                    ctx.fireUserEventTriggered(event);
                }
            } catch (Throwable cause) {
                reject(WT_SESSION_GONE);
                ctx.fireExceptionCaught(cause);
            }
        }

        void reject(int error) {
            if (removed) {
                return;
            }
            QuicStreamChannel channel = (QuicStreamChannel) ctx.channel();
            ctx.pipeline().replace(this, null, Http3UnidirectionalStreamInboundHandler.ReleaseHandler.INSTANCE);
            if (bidirectional) {
                channel.shutdown(error);
            } else {
                channel.shutdownInput(error);
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            cleanup();
            ctx.fireChannelInactive();
        }

        @Override
        public void handlerRemoved(ChannelHandlerContext ctx) {
            cleanup();
        }

        private void cleanup() {
            removed = true;
            synchronized (registry) {
                registry.pendingStreams.remove(this);
            }
            if (timeout != null) {
                timeout.cancel(false);
            }
            for (Object msg : messages) {
                ReferenceCountUtil.release(msg);
            }
            messages.clear();
            for (Object event : events) {
                ReferenceCountUtil.release(event);
            }
            events.clear();
        }
    }
}
