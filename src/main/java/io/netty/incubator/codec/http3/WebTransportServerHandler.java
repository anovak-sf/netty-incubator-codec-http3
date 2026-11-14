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
import io.netty.channel.socket.ChannelInputShutdownEvent;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Queue;

/**
 * Server-side handler that detects and establishes WebTransport sessions.
 * <p>
 * Use {@link Http3WebTransportServerConnectionHandler} to install this handler together with
 * WebTransport data-stream routing.
 * When an Extended CONNECT request with {@code :protocol: webtransport} is received, this handler
 * invokes the configured {@link WebTransportSessionAcceptor} to decide whether to accept. If accepted,
 * it sends a 200 response, enables the RFC 9297 capsule protocol over HTTP/3 DATA frames, and creates
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
    private final Queue<Http3DataFrame> pendingData = new ArrayDeque<>();
    private boolean waitingForSettings;
    private int pendingBytes;

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
        if (waitingForSettings) {
            if (msg instanceof Http3DataFrame) {
                Http3DataFrame data = (Http3DataFrame) msg;
                if (pendingData.size() == 64 || data.content().readableBytes() > 65536 - pendingBytes) {
                    ReferenceCountUtil.release(data);
                    releasePendingData();
                    ctx.close();
                } else {
                    pendingBytes += data.content().readableBytes();
                    pendingData.add(data);
                }
            } else {
                ReferenceCountUtil.release(msg);
            }
            return;
        }
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

        boolean isWebTransportConnect = "CONNECT".equals(method == null ? "" : method.toString())
                && WT_PROTOCOL.equals(protocol == null ? null : protocol.toString());

        if (!isWebTransportConnect) {
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
        if (headers.authority() == null || headers.authority().length() == 0
                || headers.path() == null || headers.path().length() == 0
                || !"https".contentEquals(headers.scheme() == null ? "" : headers.scheme())
                || WebTransportHttp3CapsuleHandler.hasForbiddenHeaders(headers)) {
            logger.debug("stream={} WT CONNECT missing required pseudo-headers (authority={} path={} scheme={})",
                    ctx.channel(), headers.authority(), headers.path(), headers.scheme());
            sendErrorAndClose(ctx, 400);
            return;
        }

        QuicChannel parent = ((QuicStreamChannel) ctx.channel()).parent();
        Http3ConnectionHandler connection = parent.pipeline().get(Http3ConnectionHandler.class);
        if (connection == null) {
            establishSession(ctx, headers);
            return;
        }
        // CONNECT can arrive before the peer's control stream because QUIC streams are unordered.
        waitingForSettings = true;
        connection.peerSettings().addListener(future -> {
            if (!ctx.channel().isActive()) {
                releasePendingData();
                return;
            }
            waitingForSettings = false;
            if (!future.isSuccess() || !connection.webTransportSupported(connection.peerSettings().getNow())) {
                releasePendingData();
                sendErrorAndClose(ctx, 400);
                return;
            }
            try {
                establishSession(ctx, headers);
                ChannelHandlerContext capsuleContext = ctx.pipeline().context(WebTransportHttp3CapsuleHandler.class);
                if (capsuleContext != null) {
                    WebTransportHttp3CapsuleHandler bridge =
                            (WebTransportHttp3CapsuleHandler) capsuleContext.handler();
                    Http3DataFrame frame;
                    while ((frame = pendingData.poll()) != null) {
                        bridge.channelRead(capsuleContext, frame);
                    }
                }
            } catch (Exception cause) {
                ctx.fireExceptionCaught(cause);
                ctx.close();
            } finally {
                releasePendingData();
            }
        });
    }

    private void establishSession(ChannelHandlerContext ctx, Http3Headers headers) throws Exception {
        // Consult the acceptor only once both peers have negotiated WebTransport support.
        WebTransportSessionListener listener = acceptor.accept(headers);
        if (listener == null) {
            logger.debug("stream={} acceptor rejected WT session", ctx.channel());
            sendErrorAndClose(ctx, 400);
            return;
        }

        // Accept: send 200. RFC 9220 does not include :protocol in the response
        // (only in the request). Echoing it back causes RFC-compliant clients (e.g.
        // aioquic) to reject the response because :protocol is not a valid response
        // pseudo-header per RFC 9114 §4.3.
        Http3Headers responseHeaders = new DefaultHttp3Headers();
        responseHeaders.status("200");
        responseHeaders.set("sec-webtransport-http3-draft", "draft02");
        ctx.writeAndFlush(new DefaultHttp3HeadersFrame(responseHeaders)).addListener(future -> {
            if (!future.isSuccess()) {
                ctx.close();
            }
        });
        if (!ctx.channel().isActive()) {
            return;
        }

        // Establish the session.
        QuicStreamChannel streamChannel = (QuicStreamChannel) ctx.channel();
        QuicChannel quicChannel = streamChannel.parent();
        long sessionId = streamChannel.streamId();

        WebTransportSession session = new WebTransportSession(quicChannel, streamChannel, sessionId, listener);
        WebTransportSessionRegistry registry = WebTransportSessionRegistry.getOrCreate(quicChannel);
        WebTransportHttp3CapsuleHandler.install(ctx, session);
        registry.register(sessionId, session);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        releasePendingData();
        ctx.fireChannelInactive();
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        if (waitingForSettings && evt instanceof ChannelInputShutdownEvent) {
            releasePendingData();
            ctx.close();
        }
        ctx.fireUserEventTriggered(evt);
    }

    private void releasePendingData() {
        Http3DataFrame frame;
        while ((frame = pendingData.poll()) != null) {
            frame.release();
        }
        pendingBytes = 0;
    }

    private static void sendErrorAndClose(ChannelHandlerContext ctx, int statusCode) {
        Http3Headers responseHeaders = new DefaultHttp3Headers();
        responseHeaders.status(String.valueOf(statusCode));
        ctx.writeAndFlush(new DefaultHttp3HeadersFrame(responseHeaders))
                .addListener(future -> {
                    QuicStreamChannel stream = (QuicStreamChannel) ctx.channel();
                    if (future.isSuccess()) {
                        stream.shutdownOutput();
                        stream.shutdownInput(Http3ErrorCode.H3_NO_ERROR.code());
                    } else {
                        stream.close();
                    }
                });
    }

    @Override
    public boolean isSharable() {
        return false;
    }
}
