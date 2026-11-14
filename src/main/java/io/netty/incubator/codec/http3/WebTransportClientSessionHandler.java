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

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.socket.ChannelInputShutdownEvent;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.internal.ObjectUtil;

/**
 * Client-side handler that establishes a WebTransport session over an HTTP/3 Extended CONNECT stream.
 * <p>
 * This handler is installed on a new bidirectional QUIC stream created via
 * {@link Http3#newWebTransportSession}. It:
 * <ol>
 *   <li>Writes the Extended CONNECT {@link Http3HeadersFrame} when the stream becomes active.</li>
 *   <li>Awaits a successful final response.</li>
 *   <li>On success, enables the RFC 9297 capsule protocol over HTTP/3 DATA frames, creates a
 *       {@link WebTransportSession}, and completes the provided {@link ChannelPromise}.</li>
 *   <li>On failure, closes the stream and fails the promise.</li>
 * </ol>
 */
final class WebTransportClientSessionHandler extends ChannelInboundHandlerAdapter {

    private final Http3Headers connectHeaders;
    private final WebTransportSessionListener listener;
    private final ChannelPromise sessionPromise;

    WebTransportClientSessionHandler(Http3Headers connectHeaders,
                                     WebTransportSessionListener listener,
                                     ChannelPromise sessionPromise) {
        // Legacy draft negotiation uses this header in addition to SETTINGS_ENABLE_WEBTRANSPORT.
        // Copy before adding it: callers may reuse their request headers.
        this.connectHeaders = new DefaultHttp3Headers()
                .set(ObjectUtil.checkNotNull(connectHeaders, "connectHeaders"))
                .set("sec-webtransport-http3-draft02", "1");
        this.listener = ObjectUtil.checkNotNull(listener, "listener");
        this.sessionPromise = ObjectUtil.checkNotNull(sessionPromise, "sessionPromise");
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        // Send the Extended CONNECT request immediately on stream activation.
        ctx.writeAndFlush(new DefaultHttp3HeadersFrame(connectHeaders)).addListener(future -> {
            if (!future.isSuccess()) {
                failAndClose(ctx, future.cause());
            }
        });
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
            failAndClose(ctx, new Http3Exception(Http3ErrorCode.H3_MESSAGE_ERROR,
                    "Response missing :status pseudo-header"));
            return;
        }

        if (status.length() != 3 || status.charAt(0) < '1' || status.charAt(0) > '5'
                || status.charAt(1) < '0' || status.charAt(1) > '9'
                || status.charAt(2) < '0' || status.charAt(2) > '9') {
            failAndClose(ctx, new Http3Exception(Http3ErrorCode.H3_MESSAGE_ERROR,
                    "Invalid :status value: " + status));
            return;
        }
        int statusCode = Integer.parseInt(status.toString());
        if (statusCode == 101) {
            failAndClose(ctx, new Http3Exception(Http3ErrorCode.H3_MESSAGE_ERROR,
                    "Invalid :status value: " + status));
            return;
        }
        if (statusCode < 200) {
            // Informational responses do not decide whether the CONNECT request succeeds.
            return;
        }
        if (statusCode >= 300) {
            failAndClose(ctx, new IllegalStateException(
                    "WebTransport session rejected with status " + statusCode));
            return;
        }

        if (statusCode == 204 || statusCode == 205 || statusCode == 206
                || WebTransportHttp3CapsuleHandler.hasForbiddenHeaders(headers)) {
            failAndClose(ctx, new Http3Exception(Http3ErrorCode.H3_MESSAGE_ERROR,
                    "Response is not valid for the Capsule Protocol"));
            return;
        }

        // Session established — rewire the pipeline.
        QuicStreamChannel streamChannel = (QuicStreamChannel) ctx.channel();
        QuicChannel quicChannel = (QuicChannel) streamChannel.parent();
        long sessionId = streamChannel.streamId();

        WebTransportSession session = new WebTransportSession(quicChannel, streamChannel, sessionId, listener);
        WebTransportSessionRegistry registry = WebTransportSessionRegistry.getOrCreate(quicChannel);
        WebTransportHttp3CapsuleHandler.install(ctx, session);
        registry.register(sessionId, session);

        // Notify the caller that the session is ready.
        sessionPromise.trySuccess();
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

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        if (evt instanceof ChannelInputShutdownEvent) {
            failAndClose(ctx, new IllegalStateException(
                    "WebTransport session response ended before session was established"));
        }
        ctx.fireUserEventTriggered(evt);
    }

    private void failAndClose(ChannelHandlerContext ctx, Throwable cause) {
        if (!sessionPromise.isDone()) {
            sessionPromise.setFailure(cause);
        }
        if (cause instanceof Http3Exception) {
            ((QuicStreamChannel) ctx.channel()).shutdown(((Http3Exception) cause).errorCode().code());
        }
        ctx.close();
    }

    @Override
    public boolean isSharable() {
        return false;
    }
}
