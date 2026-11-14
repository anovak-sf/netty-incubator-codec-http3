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
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.handler.codec.quic.QuicStreamType;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;
import io.netty.util.internal.ObjectUtil;

import java.nio.channels.ClosedChannelException;
import java.util.HashSet;
import java.util.Set;

import static io.netty.incubator.codec.http3.WebTransportCodecUtils.WT_STREAM_TYPE_BIDIRECTIONAL;
import static io.netty.incubator.codec.http3.WebTransportCodecUtils.WT_STREAM_TYPE_UNIDIRECTIONAL;

/**
 * Represents an active WebTransport session.
 * <p>
 * A session is associated with a single HTTP/3 Extended CONNECT stream (the "session stream").
 * Its {@link #sessionId()} is the QUIC stream ID of that stream. The session allows opening
 * new reliable streams (bidirectional or unidirectional) and sending unreliable datagrams.
 * <p>
 * To close the session cleanly, call {@link #closeSession(int, String)}, which sends a
 * {@link WebTransportCloseSessionCapsule} and then closes the session stream.
 *
 * @see <a href="https://datatracker.ietf.org/doc/draft-ietf-webtrans-http3/">WebTransport over HTTP/3</a>
 */
public final class WebTransportSession {

    private final QuicChannel quicChannel;
    private final QuicStreamChannel sessionStream;
    private final long sessionId;
    private final WebTransportSessionListener listener;
    private final Set<QuicStreamChannel> streams = new HashSet<>();
    private volatile boolean closed;

    WebTransportSession(QuicChannel quicChannel, QuicStreamChannel sessionStream,
                        long sessionId, WebTransportSessionListener listener) {
        this.quicChannel = ObjectUtil.checkNotNull(quicChannel, "quicChannel");
        this.sessionStream = ObjectUtil.checkNotNull(sessionStream, "sessionStream");
        this.sessionId = sessionId;
        this.listener = ObjectUtil.checkNotNull(listener, "listener");
        sessionStream.closeFuture().addListener(f -> terminate(0, ""));
    }

    /**
     * Returns the session ID, which is the QUIC stream ID of the HTTP/3 CONNECT stream that
     * established this session.
     *
     * @return the session ID
     */
    public long sessionId() {
        return sessionId;
    }

    /**
     * Returns the QUIC stream channel used as the session stream (the Extended CONNECT stream).
     * After session establishment this channel uses the RFC 9297 capsule protocol.
     *
     * @return the session stream channel
     */
    public QuicStreamChannel sessionStream() {
        return sessionStream;
    }

    /**
     * Returns the {@link WebTransportSessionListener} registered for this session.
     *
     * @return the listener
     */
    WebTransportSessionListener listener() {
        return listener;
    }

    /**
     * Opens a new outbound WebTransport bidirectional stream on this session.
     * <p>
     * The stream will be prefixed with {@code [0x41][sessionId(varint)]} before any application data.
     * The given {@code handler} is installed after the prefix handler and receives raw {@link ByteBuf}s.
     *
     * @param handler the {@link ChannelHandler} to handle data on the new stream
     * @return a future that completes with the opened {@link QuicStreamChannel}
     */
    public Future<QuicStreamChannel> openBidirectionalStream(final ChannelHandler handler) {
        return openStream(QuicStreamType.BIDIRECTIONAL, WT_STREAM_TYPE_BIDIRECTIONAL, handler);
    }

    /**
     * Opens a new outbound WebTransport unidirectional stream on this session.
     * <p>
     * The stream type ({@code 0x54}) and session ID varint are written automatically before any
     * application data. The given {@code handler} receives raw {@link ByteBuf}s.
     *
     * @param handler the {@link ChannelHandler} to handle data on the new stream
     * @return a future that completes with the opened {@link QuicStreamChannel}
     */
    public Future<QuicStreamChannel> openUnidirectionalStream(final ChannelHandler handler) {
        return openStream(QuicStreamType.UNIDIRECTIONAL, WT_STREAM_TYPE_UNIDIRECTIONAL, handler);
    }

    private Future<QuicStreamChannel> openStream(QuicStreamType type, int streamType, ChannelHandler handler) {
        ObjectUtil.checkNotNull(handler, "handler");
        if (isClosed()) {
            return quicChannel.eventLoop().newFailedFuture(new ClosedChannelException());
        }
        Promise<QuicStreamChannel> promise = quicChannel.eventLoop().newPromise();
        try {
            quicChannel.createStream(type, new ChannelInitializer<QuicStreamChannel>() {
                @Override
                protected void initChannel(QuicStreamChannel ch) {
                    if (!addStream(ch)) {
                        ch.close();
                        return;
                    }
                    ch.pipeline().addLast(new WebTransportStreamOutboundPrefixHandler(streamType, sessionId));
                    ch.pipeline().addLast(handler);
                }
            }).addListener(future -> {
                if (!future.isSuccess()) {
                    promise.tryFailure(future.cause());
                    return;
                }
                QuicStreamChannel stream = (QuicStreamChannel) future.getNow();
                // Session termination can race with asynchronous creation, or happen in the
                // application's channelActive callback before the QUIC create future completes.
                if (isClosed()) {
                    resetStream(stream);
                    stream.close();
                    promise.tryFailure(new ClosedChannelException());
                } else if (!promise.trySuccess(stream)) {
                    stream.close();
                }
            });
        } catch (RuntimeException cause) {
            promise.tryFailure(cause);
        }
        return promise;
    }

    /**
     * Sends an unreliable HTTP Datagram using a QUIC DATAGRAM frame. The session's Quarter
     * Stream ID is prepended to the application payload as required by RFC 9297.
     * <p>
     * The caller retains ownership of {@code payload} — this method will increment the reference count
     * internally while the write is pending. Release {@code payload} after calling this method if
     * you no longer need it.
     *
     * @param payload the datagram payload
     * @return the future for the write operation
     */
    public ChannelFuture sendDatagram(ByteBuf payload) {
        ObjectUtil.checkNotNull(payload, "payload");
        ChannelPromise promise = quicChannel.newPromise();
        ByteBuf retained = payload.retainedDuplicate();
        Runnable write = () -> {
            try {
                if (isClosed()) {
                    promise.tryFailure(new ClosedChannelException());
                    return;
                }
                ByteBuf datagram = quicChannel.alloc().buffer(8 + retained.readableBytes());
                Http3CodecUtils.writeVariableLengthInteger(datagram, sessionId >>> 2);
                datagram.writeBytes(retained);
                quicChannel.writeAndFlush(datagram, promise);
            } catch (RuntimeException e) {
                promise.tryFailure(e);
            } finally {
                retained.release();
            }
        };
        if (quicChannel.eventLoop().inEventLoop()) {
            write.run();
        } else {
            try {
                quicChannel.eventLoop().execute(write);
            } catch (RuntimeException e) {
                retained.release();
                promise.tryFailure(e);
            }
        }
        return promise;
    }

    /**
     * Closes the session cleanly by sending a {@link WebTransportCloseSessionCapsule} and then
     * closing the session stream.
     *
     * @param applicationErrorCode the application-defined error code (0 for normal closure)
     * @param reason               the human-readable reason string (may be empty, must not be {@code null})
     * @return the future for the close operation
     */
    public ChannelFuture closeSession(int applicationErrorCode, String reason) {
        WebTransportCloseSessionCapsule closeCapsule =
                new WebTransportCloseSessionCapsule(applicationErrorCode, reason);
        ChannelPromise promise = sessionStream.newPromise();
        Runnable close = () -> {
            if (isClosed()) {
                promise.tryFailure(new ClosedChannelException());
                return;
            }
            terminate(applicationErrorCode, reason);
            sessionStream.writeAndFlush(closeCapsule).addListener(f -> {
                if (f.isSuccess()) {
                    sessionStream.shutdownOutput(promise);
                } else {
                    promise.tryFailure(f.cause());
                    sessionStream.close();
                }
            });
        };
        if (sessionStream.eventLoop().inEventLoop()) {
            close.run();
        } else {
            try {
                sessionStream.eventLoop().execute(close);
            } catch (RuntimeException e) {
                promise.tryFailure(e);
            }
        }
        return promise;
    }

    /**
     * Returns whether this session has terminated and can no longer send datagrams or open streams.
     * @return {@code true} after session termination
     */
    public boolean isClosed() {
        return closed || !sessionStream.isActive();
    }

    synchronized boolean addStream(QuicStreamChannel stream) {
        if (isClosed()) {
            resetStream(stream);
            return false;
        }
        streams.add(stream);
        stream.closeFuture().addListener(f -> removeStream(stream));
        return true;
    }

    private synchronized void removeStream(QuicStreamChannel stream) {
        streams.remove(stream);
    }

    void terminate(int errorCode, String reason) {
        Set<QuicStreamChannel> toReset;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            toReset = new HashSet<>(streams);
            streams.clear();
        }
        WebTransportSessionRegistry registry = WebTransportSessionRegistry.get(quicChannel);
        if (registry != null) {
            registry.deregister(sessionId);
        }
        for (QuicStreamChannel stream : toReset) {
            resetStream(stream);
        }
        try {
            listener.onSessionClosed(this, errorCode, reason);
        } catch (Throwable cause) {
            sessionStream.pipeline().fireExceptionCaught(cause);
        }
    }

    private static void resetStream(QuicStreamChannel stream) {
        if (stream.type() == QuicStreamType.BIDIRECTIONAL) {
            stream.shutdown(WebTransportCodecUtils.WT_SESSION_GONE);
        } else if (stream.isLocalCreated()) {
            stream.shutdownOutput(WebTransportCodecUtils.WT_SESSION_GONE);
        } else {
            stream.shutdownInput(WebTransportCodecUtils.WT_SESSION_GONE);
        }
        // Reset ends the QUIC directions but does not immediately close Netty's channel.
        // Close it too so application producers see an inactive stream and queued writes
        // cannot reach quiche after the final send offset was established by the reset.
        stream.close();
    }

    @Override
    public String toString() {
        return "WebTransportSession{sessionId=" + sessionId + '}';
    }
}
