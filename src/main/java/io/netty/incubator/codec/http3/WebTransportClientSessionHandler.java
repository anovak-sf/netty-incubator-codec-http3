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

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.incubator.codec.quic.QuicChannel;
import io.netty.incubator.codec.quic.QuicStreamChannel;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.internal.ObjectUtil;

/**
 * Client-side handler that establishes a WebTransport session over an HTTP/3 Extended CONNECT stream.
 * <p>
 * This handler is installed on a new bidirectional QUIC stream created via
 * {@link Http3#newWebTransportSession}. It:
 * <ol>
 *   <li>Writes the Extended CONNECT {@link Http3HeadersFrame} when the stream becomes active.</li>
 *   <li>Awaits a 2xx response with {@code :protocol: webtransport}.</li>
 *   <li>On success, rewires the pipeline to the RFC 9297 capsule protocol, creates a
 *       {@link WebTransportSession}, and completes the provided {@link ChannelPromise}.</li>
 *   <li>On failure, closes the stream and fails the promise.</li>
 * </ol>
 */
final class WebTransportClientSessionHandler extends ChannelInboundHandlerAdapter {

    private static final String WT_PROTOCOL = "webtransport";

    private final Http3Headers connectHeaders;
    private final WebTransportSessionListener listener;
    private final ChannelPromise sessionPromise;

    WebTransportClientSessionHandler(Http3Headers connectHeaders,
                                     WebTransportSessionListener listener,
                                     ChannelPromise sessionPromise) {
        this.connectHeaders = ObjectUtil.checkNotNull(connectHeaders, "connectHeaders");
        this.listener = ObjectUtil.checkNotNull(listener, "listener");
        this.sessionPromise = ObjectUtil.checkNotNull(sessionPromise, "sessionPromise");
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        // Send the Extended CONNECT request immediately on stream activation.
        ctx.writeAndFlush(new DefaultHttp3HeadersFrame(connectHeaders));
        ctx.fireChannelActive();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!(msg instanceof Http3HeadersFrame)) {
            ReferenceCountUtil.release(msg);
            return;
        }

        Http3HeadersFrame headersFrame = (Http3HeadersFrame) msg;
        Http3Headers headers = headersFrame.headers();
        ReferenceCountUtil.release(headersFrame);

        CharSequence status = headers.status();
        if (status == null) {
            failAndClose(ctx, new IllegalStateException("Response missing :status pseudo-header"));
            return;
        }

        int statusCode;
        try {
            statusCode = Integer.parseInt(status.toString());
        } catch (NumberFormatException e) {
            failAndClose(ctx, new IllegalStateException("Invalid :status value: " + status));
            return;
        }

        if (statusCode < 200 || statusCode >= 300) {
            failAndClose(ctx, new IllegalStateException(
                    "WebTransport session rejected with status " + statusCode));
            return;
        }

        CharSequence protocol = headers.get(Http3Headers.PseudoHeaderName.PROTOCOL.value());
        if (!WT_PROTOCOL.equals(protocol == null ? null : protocol.toString())) {
            failAndClose(ctx, new IllegalStateException(
                    "Server did not echo :protocol: webtransport in 2xx response"));
            return;
        }

        // Session established — rewire the pipeline.
        QuicStreamChannel streamChannel = (QuicStreamChannel) ctx.channel();
        QuicChannel quicChannel = (QuicChannel) streamChannel.parent();
        long sessionId = streamChannel.streamId();

        WebTransportSession session = new WebTransportSession(quicChannel, streamChannel, sessionId, listener);
        WebTransportSessionRegistry.getOrCreate(quicChannel).register(sessionId, session);

        ChannelPipeline pipeline = ctx.pipeline();

        ChannelHandler frameCodec = pipeline.get(Http3FrameCodec.class);
        if (frameCodec != null) {
            pipeline.replace(frameCodec, "wtCapsuleDecoder", new WebTransportCapsuleDecoder());
        }
        pipeline.addFirst("wtCapsuleEncoder", WebTransportCapsuleEncoder.INSTANCE);

        removeIfPresent(pipeline, Http3RequestStreamEncodeStateValidator.class);
        removeIfPresent(pipeline, Http3RequestStreamDecodeStateValidator.class);
        removeIfPresent(pipeline, Http3RequestStreamValidationHandler.class);

        pipeline.replace(this, "wtSessionStream", new WebTransportSessionStreamHandler(session));

        // Notify the caller that the session is ready.
        sessionPromise.setSuccess();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        if (!sessionPromise.isDone()) {
            sessionPromise.setFailure(new IllegalStateException(
                    "WebTransport session stream closed before session was established"));
        }
        ctx.fireChannelInactive();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        failAndClose(ctx, cause);
    }

    private void failAndClose(ChannelHandlerContext ctx, Throwable cause) {
        if (!sessionPromise.isDone()) {
            sessionPromise.setFailure(cause);
        }
        ctx.close();
    }

    private static void removeIfPresent(ChannelPipeline pipeline, Class<? extends ChannelHandler> type) {
        ChannelHandler h = pipeline.get(type);
        if (h != null) {
            pipeline.remove(h);
        }
    }

    @Override
    public boolean isSharable() {
        return false;
    }
}
