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
import io.netty.incubator.codec.quic.QuicChannel;
import io.netty.incubator.codec.quic.QuicStreamChannel;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import org.jetbrains.annotations.Nullable;

/**
 * Server-side handler that detects and establishes WebTransport sessions.
 * <p>
 * Install this as the {@code requestStreamHandler} argument to {@link Http3ServerConnectionHandler}.
 * When an Extended CONNECT request with {@code :protocol: webtransport} is received, this handler
 * invokes the configured {@link WebTransportSessionAcceptor} to decide whether to accept. If accepted,
 * it sends a 200 response, rewires the stream pipeline to the RFC 9297 capsule protocol, and creates
 * a {@link WebTransportSession}.
 * <p>
 * Non-WebTransport requests are forwarded to the optional {@code fallbackHandler}. If no fallback is
 * configured and a non-WebTransport request arrives, a 405 Method Not Allowed response is sent.
 * <p>
 * <strong>Required server settings:</strong> The server SETTINGS frame must include:
 * <pre>{@code
 * Http3Settings.defaultSettings()
 *     .enableConnectProtocol(true)
 *     .enableH3Datagram(true)
 *     .enableWebTransport(true)
 * }</pre>
 *
 * @see Http3ServerConnectionHandler
 * @see WebTransportSessionAcceptor
 * @see WebTransportSessionListener
 */
public final class WebTransportServerHandler extends ChannelInboundHandlerAdapter {

    private static final InternalLogger logger =
            InternalLoggerFactory.getInstance(WebTransportServerHandler.class);

    private static final String WT_PROTOCOL = "webtransport";

    private final WebTransportSessionAcceptor acceptor;
    @Nullable
    private final ChannelHandler fallbackHandler;

    /**
     * Creates a new handler that accepts WebTransport sessions and returns HTTP 405 for all other requests.
     *
     * @param acceptor the session acceptor (never {@code null})
     */
    public WebTransportServerHandler(WebTransportSessionAcceptor acceptor) {
        this(acceptor, null);
    }

    /**
     * Creates a new handler that accepts WebTransport sessions and forwards non-WebTransport requests
     * to {@code fallbackHandler}.
     *
     * @param acceptor        the session acceptor (never {@code null})
     * @param fallbackHandler the handler for non-WebTransport requests, or {@code null} to return HTTP 405
     */
    public WebTransportServerHandler(WebTransportSessionAcceptor acceptor,
                                     @Nullable ChannelHandler fallbackHandler) {
        this.acceptor = ObjectUtil.checkNotNull(acceptor, "acceptor");
        this.fallbackHandler = fallbackHandler;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!(msg instanceof Http3HeadersFrame)) {
            // Data frames or unexpected messages before headers — ignore.
            ReferenceCountUtil.release(msg);
            return;
        }

        Http3HeadersFrame headersFrame = (Http3HeadersFrame) msg;
        Http3Headers headers = headersFrame.headers();
        ReferenceCountUtil.release(headersFrame);

        CharSequence method = headers.method();
        CharSequence protocol = headers.get(Http3Headers.PseudoHeaderName.PROTOCOL.value());
        logger.debug("stream={} CONNECT received: method={} protocol={} authority={} path={} scheme={}",
                ctx.channel(), method, protocol, headers.authority(), headers.path(), headers.scheme());

        boolean isWebTransportConnect = "CONNECT".equals(method == null ? "" : method.toString())
                && WT_PROTOCOL.equals(protocol == null ? null : protocol.toString());

        if (!isWebTransportConnect) {
            logger.debug("stream={} not a WT CONNECT (method={} protocol={}), routing to fallback/405",
                    ctx.channel(), method, protocol);
            if (fallbackHandler != null) {
                ctx.pipeline().addLast(fallbackHandler);
                ctx.pipeline().remove(this);
                ctx.fireChannelRead(new DefaultHttp3HeadersFrame(headers));
            } else {
                sendErrorAndClose(ctx, 405);
            }
            return;
        }

        // Validate required pseudo-headers for WebTransport CONNECT.
        if (headers.authority() == null || headers.path() == null || headers.scheme() == null) {
            logger.warn("stream={} WT CONNECT missing required pseudo-headers (authority={} path={} scheme={})",
                    ctx.channel(), headers.authority(), headers.path(), headers.scheme());
            sendErrorAndClose(ctx, 400);
            return;
        }

        // Consult the acceptor.
        WebTransportSessionListener listener = acceptor.accept(headers);
        if (listener == null) {
            logger.warn("stream={} acceptor rejected WT session", ctx.channel());
            sendErrorAndClose(ctx, 400);
            return;
        }

        // Accept: send 200. RFC 9220 does not include :protocol in the response
        // (only in the request). Echoing it back causes RFC-compliant clients (e.g.
        // aioquic) to reject the response because :protocol is not a valid response
        // pseudo-header per RFC 9114 §4.3.
        Http3Headers responseHeaders = new DefaultHttp3Headers();
        responseHeaders.status("200");
        ctx.writeAndFlush(new DefaultHttp3HeadersFrame(responseHeaders));
        logger.debug("stream={} sent 200 WT response", ctx.channel());

        // Establish the session.
        QuicStreamChannel streamChannel = (QuicStreamChannel) ctx.channel();
        QuicChannel quicChannel = streamChannel.parent();
        long sessionId = streamChannel.streamId();

        WebTransportSession session = new WebTransportSession(quicChannel, streamChannel, sessionId, listener);
        WebTransportSessionRegistry.getOrCreate(quicChannel).register(sessionId, session);
        logger.debug("stream={} WT session registered with sessionId={}", ctx.channel(), sessionId);

        // Rewire pipeline: replace HTTP/3 frame codec with capsule codec.
        ChannelPipeline pipeline = ctx.pipeline();

        // Remove HTTP/3 stream validators BEFORE replacing the frame codec.
        // Http3FrameCodec extends ByteToMessageDecoder; when it is removed from the pipeline,
        // ByteToMessageDecoder.handlerRemoved() fires any buffered decoded frames synchronously
        // back into the pipeline via fireChannelRead().  If the validators are still present at
        // that moment they treat those frames as unexpected and raise an Http3Exception.  Removing
        // them first ensures the buffered frames pass through (or are released by this handler)
        // without triggering a connection error.
        removeIfPresent(pipeline, Http3RequestStreamEncodeStateValidator.class);
        removeIfPresent(pipeline, Http3RequestStreamDecodeStateValidator.class);
        removeIfPresent(pipeline, Http3RequestStreamValidationHandler.class);

        // The Http3FrameCodec is always in the pipeline — get it by type.
        ChannelHandler frameCodec = pipeline.get(Http3FrameCodec.class);
        if (frameCodec != null) {
            pipeline.replace(frameCodec, "wtCapsuleDecoder", new WebTransportCapsuleDecoder());
        }

        // Add capsule encoder at the head (outbound direction goes head→network).
        pipeline.addFirst("wtCapsuleEncoder", WebTransportCapsuleEncoder.INSTANCE);

        // Replace this handler with the capsule dispatcher. The session established
        // user event will be fired from WebTransportSessionStreamHandler.handlerAdded().
        pipeline.replace(this, "wtSessionStream", new WebTransportSessionStreamHandler(session));
    }

    private static void removeIfPresent(ChannelPipeline pipeline, Class<? extends ChannelHandler> type) {
        ChannelHandler h = pipeline.get(type);
        if (h != null) {
            pipeline.remove(h);
        }
    }

    private static void sendErrorAndClose(ChannelHandlerContext ctx, int statusCode) {
        Http3Headers responseHeaders = new DefaultHttp3Headers();
        responseHeaders.status(String.valueOf(statusCode));
        ctx.writeAndFlush(new DefaultHttp3HeadersFrame(responseHeaders))
                .addListener(f -> ctx.channel().close());
    }

    @Override
    public boolean isSharable() {
        return false;
    }
}
