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
import io.netty.incubator.codec.quic.QuicChannel;
import io.netty.incubator.codec.quic.QuicStreamChannel;
import io.netty.incubator.codec.quic.QuicStreamType;
import io.netty.util.concurrent.Future;
import io.netty.util.internal.ObjectUtil;

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

    WebTransportSession(QuicChannel quicChannel, QuicStreamChannel sessionStream,
                        long sessionId, WebTransportSessionListener listener) {
        this.quicChannel = ObjectUtil.checkNotNull(quicChannel, "quicChannel");
        this.sessionStream = ObjectUtil.checkNotNull(sessionStream, "sessionStream");
        this.sessionId = sessionId;
        this.listener = ObjectUtil.checkNotNull(listener, "listener");
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
        final long sid = sessionId;
        return quicChannel.createStream(QuicStreamType.BIDIRECTIONAL,
                new ChannelInitializer<QuicStreamChannel>() {
                    @Override
                    protected void initChannel(QuicStreamChannel ch) {
                        ch.pipeline().addLast(
                                new WebTransportStreamOutboundPrefixHandler(WT_STREAM_TYPE_BIDIRECTIONAL, sid));
                        ch.pipeline().addLast(handler);
                    }
                });
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
        final long sid = sessionId;
        return quicChannel.createStream(QuicStreamType.UNIDIRECTIONAL,
                new ChannelInitializer<QuicStreamChannel>() {
                    @Override
                    protected void initChannel(QuicStreamChannel ch) {
                        ch.pipeline().addLast(
                                new WebTransportStreamOutboundPrefixHandler(WT_STREAM_TYPE_UNIDIRECTIONAL, sid));
                        ch.pipeline().addLast(handler);
                    }
                });
    }

    /**
     * Sends a datagram to the peer for this session as a {@link WebTransportDatagramCapsule}
     * on the session stream.
     * <p>
     * The caller retains ownership of {@code payload} — this method will increment the reference count
     * internally by creating a retained duplicate. Release {@code payload} after calling this method if
     * you no longer need it.
     *
     * @param payload the datagram payload
     * @return the future for the write operation
     */
    public ChannelFuture sendDatagram(ByteBuf payload) {
        return sessionStream.writeAndFlush(new WebTransportDatagramCapsule(payload.retainedDuplicate()));
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
        return sessionStream.writeAndFlush(closeCapsule).addListener(f -> sessionStream.close());
    }

    @Override
    public String toString() {
        return "WebTransportSession{sessionId=" + sessionId + '}';
    }
}
