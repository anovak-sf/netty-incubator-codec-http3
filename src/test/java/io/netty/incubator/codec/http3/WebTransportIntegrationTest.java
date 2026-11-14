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

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.ChannelInputShutdownEvent;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.handler.codec.quic.InsecureQuicTokenHandler;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicSslContextBuilder;
import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.util.CharsetUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.channels.ClosedChannelException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebTransportIntegrationTest {

    @Test
    @Timeout(60)
    void testNativeQuicHandshakeDatagramStreamAndClose() throws Exception {
        runNativeQuicScenario(false);
    }

    @Test
    @Timeout(60)
    void testNativeSessionResetClosesStreamBeforeLateWrite() throws Exception {
        runNativeQuicScenario(true);
    }

    private static void runNativeQuicScenario(boolean resetWithLateWrite) throws Exception {
        SelfSignedCertificate certificate = new SelfSignedCertificate();
        EventLoopGroup group = new NioEventLoopGroup(1);
        Channel server = null;
        Channel client = null;
        QuicChannel connection = null;
        BlockingQueue<String> datagrams = new LinkedBlockingQueue<>();
        BlockingQueue<String> streamPayloads = new LinkedBlockingQueue<>();
        BlockingQueue<WebTransportCloseSessionCapsule> serverClosed = new LinkedBlockingQueue<>();
        BlockingQueue<WebTransportCloseSessionCapsule> clientClosed = new LinkedBlockingQueue<>();
        BlockingQueue<Throwable> errors = new LinkedBlockingQueue<>();
        BlockingQueue<QuicStreamChannel> serverStreams = new LinkedBlockingQueue<>();
        BlockingQueue<WebTransportSession> serverSessions = new LinkedBlockingQueue<>();
        try {
            ChannelFuture serverBind = new Bootstrap().group(group).channel(NioDatagramChannel.class)
                    .handler(Http3.newQuicServerCodecBuilder()
                            .initialMaxData(1000000)
                            .initialMaxStreamDataBidirectionalLocal(100000)
                            .initialMaxStreamDataBidirectionalRemote(100000)
                            .initialMaxStreamsBidirectional(10)
                            .datagram(16, 16)
                            .sslContext(QuicSslContextBuilder.forServer(certificate.key(), null, certificate.cert())
                                    .applicationProtocols(Http3.supportedApplicationProtocols()).build())
                            .tokenHandler(InsecureQuicTokenHandler.INSTANCE)
                            .handler(new ChannelInitializer<QuicChannel>() {
                                @Override
                                protected void initChannel(QuicChannel ch) {
                                    ch.pipeline().addLast(new Http3WebTransportServerConnectionHandler(headers ->
                                            listener(datagrams, streamPayloads, serverClosed, errors,
                                                    serverStreams, serverSessions)));
                                    ch.pipeline().addLast(errorHandler(errors));
                                }
                            }).build())
                    .bind("127.0.0.1", 0);
            serverBind.get(10, TimeUnit.SECONDS);
            server = serverBind.channel();

            ChannelFuture clientBind = new Bootstrap().group(group).channel(NioDatagramChannel.class)
                    .handler(Http3.newQuicClientCodecBuilder()
                            .initialMaxData(1000000)
                            .initialMaxStreamDataBidirectionalLocal(100000)
                            .initialMaxStreamDataBidirectionalRemote(100000)
                            .initialMaxStreamsBidirectional(10)
                            .datagram(16, 16)
                            .sslContext(QuicSslContextBuilder.forClient()
                                    .trustManager(InsecureTrustManagerFactory.INSTANCE)
                                    .applicationProtocols(Http3.supportedApplicationProtocols()).build())
                            .build())
                    .bind("127.0.0.1", 0);
            clientBind.get(10, TimeUnit.SECONDS);
            client = clientBind.channel();
            connection = QuicChannel.newBootstrap(client)
                    .handler(new ChannelInitializer<QuicChannel>() {
                        @Override
                        protected void initChannel(QuicChannel ch) {
                            Http3SettingsFrame settings = new DefaultHttp3SettingsFrame(new Http3Settings()
                                    .enableConnectProtocol(true).enableH3Datagram(true).enableWebTransport(true));
                            ch.pipeline().addLast(new Http3ClientConnectionHandler(null, null, null, settings, true));
                            ch.pipeline().addLast(errorHandler(errors));
                        }
                    })
                    .localAddress(client.localAddress()).remoteAddress(server.localAddress())
                    .connect().get(10, TimeUnit.SECONDS);

            Http3SettingsFrame peerSettings = connection.pipeline().get(Http3ConnectionHandler.class)
                    .peerSettings().get(10, TimeUnit.SECONDS);
            assertEquals(1L, peerSettings.settings().get(
                    Http3SettingIdentifier.HTTP3_SETTINGS_WEBTRANSPORT_ENABLE.id()), peerSettings.toString());
            assertEquals(1L, peerSettings.settings().get(
                    Http3SettingIdentifier.HTTP3_SETTINGS_H3_DATAGRAM.id()), peerSettings.toString());
            assertEquals(1L, peerSettings.settings().get(
                    Http3SettingIdentifier.HTTP3_SETTINGS_ENABLE_CONNECT_PROTOCOL.id()), peerSettings.toString());

            Http3Headers headers = new DefaultHttp3Headers().method("CONNECT").scheme("https")
                    .authority("localhost").path("/webtransport")
                    .add(Http3Headers.PseudoHeaderName.PROTOCOL.value(), "webtransport")
                    .add("origin", "https://localhost");
            WebTransportSession session = Http3.newWebTransportSession(connection, headers,
                    listener(new LinkedBlockingQueue<>(), new LinkedBlockingQueue<>(), clientClosed, errors,
                            null, null))
                    .get(10, TimeUnit.SECONDS);
            assertTrue(errors.isEmpty(), errors.toString());

            ByteBuf datagram = Unpooled.copiedBuffer("native-datagram", CharsetUtil.US_ASCII);
            try {
                session.sendDatagram(datagram).get(10, TimeUnit.SECONDS);
            } finally {
                datagram.release();
            }
            assertEquals("native-datagram", datagrams.poll(10, TimeUnit.SECONDS), errors.toString());

            QuicStreamChannel stream = session.openBidirectionalStream(errorHandler(errors))
                    .get(10, TimeUnit.SECONDS);
            stream.writeAndFlush(Unpooled.copiedBuffer("native-stream", CharsetUtil.US_ASCII))
                    .get(10, TimeUnit.SECONDS);
            stream.shutdownOutput().get(10, TimeUnit.SECONDS);
            assertEquals("native-stream", streamPayloads.poll(10, TimeUnit.SECONDS), errors.toString());

            if (resetWithLateWrite) {
                QuicStreamChannel serverStream = serverStreams.poll(10, TimeUnit.SECONDS);
                WebTransportSession serverSession = serverSessions.poll(10, TimeUnit.SECONDS);
                assertNotNull(serverStream);
                assertNotNull(serverSession);
                // Reset and the late producer write must run in one event-loop task, before
                // peer packets can happen to close the stream and hide the shutdown race.
                serverStream.eventLoop().submit(() -> {
                    serverSession.terminate(0, "native reset");
                    assertTrue(serverStream.isOutputShutdown());
                    assertFalse(serverStream.isActive(), "Session reset must close its stream immediately");
                    ChannelFuture lateWrite = serverStream.writeAndFlush(
                            Unpooled.copiedBuffer("late", CharsetUtil.US_ASCII));
                    assertTrue(lateWrite.isDone());
                    assertInstanceOf(ClosedChannelException.class, lateWrite.cause());
                }).get(10, TimeUnit.SECONDS);
                serverStream.closeFuture().get(10, TimeUnit.SECONDS);
                assertTrue(connection.isActive(), "Resetting a session must not close the QUIC connection");
            } else {
                session.closeSession(37, "native close").get(10, TimeUnit.SECONDS);
                WebTransportCloseSessionCapsule remoteClose = serverClosed.poll(10, TimeUnit.SECONDS);
                assertNotNull(remoteClose, errors.toString());
                assertEquals(37, remoteClose.applicationErrorCode());
                assertEquals("native close", remoteClose.errorReason());
                WebTransportCloseSessionCapsule localClose = clientClosed.poll(10, TimeUnit.SECONDS);
                assertNotNull(localClose);
                assertEquals(37, localClose.applicationErrorCode());
                assertEquals("native close", localClose.errorReason());
            }
            assertTrue(errors.isEmpty(), errors.toString());
        } finally {
            if (connection != null) {
                connection.close().awaitUninterruptibly(5, TimeUnit.SECONDS);
            }
            if (client != null) {
                client.close().awaitUninterruptibly(5, TimeUnit.SECONDS);
            }
            if (server != null) {
                server.close().awaitUninterruptibly(5, TimeUnit.SECONDS);
            }
            group.shutdownGracefully(0, 5, TimeUnit.SECONDS).awaitUninterruptibly(5, TimeUnit.SECONDS);
            certificate.delete();
        }
    }

    private static WebTransportSessionListener listener(BlockingQueue<String> datagrams,
            BlockingQueue<String> streamPayloads, BlockingQueue<WebTransportCloseSessionCapsule> closed,
            BlockingQueue<Throwable> errors, BlockingQueue<QuicStreamChannel> streams,
            BlockingQueue<WebTransportSession> sessions) {
        return new WebTransportSessionListener() {
            @Override
            public void onBidirectionalStream(WebTransportSession session, QuicStreamChannel stream) {
                if (streams != null) {
                    streams.offer(stream);
                    sessions.offer(session);
                }
                stream.pipeline().addLast(new SimpleChannelInboundHandler<ByteBuf>() {
                    private final StringBuilder payload = new StringBuilder();

                    @Override
                    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf bytes) {
                        payload.append(bytes.toString(CharsetUtil.US_ASCII));
                    }

                    @Override
                    public void userEventTriggered(ChannelHandlerContext ctx, Object event) {
                        if (event instanceof ChannelInputShutdownEvent) {
                            streamPayloads.offer(payload.toString());
                        }
                        ctx.fireUserEventTriggered(event);
                    }
                });
                stream.pipeline().addLast(errorHandler(errors));
            }

            @Override
            public void onUnidirectionalStream(WebTransportSession session, QuicStreamChannel stream) {
                onBidirectionalStream(session, stream);
            }

            @Override
            public void onDatagram(WebTransportSession session, ByteBuf bytes) {
                datagrams.offer(bytes.toString(CharsetUtil.US_ASCII));
            }

            @Override
            public void onSessionClosed(WebTransportSession session, int code, String reason) {
                closed.offer(new WebTransportCloseSessionCapsule(code, reason));
            }
        };
    }

    private static ChannelInboundHandlerAdapter errorHandler(BlockingQueue<Throwable> errors) {
        return new ChannelInboundHandlerAdapter() {
            @Override
            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                errors.offer(cause);
                ctx.close();
            }
        };
    }
}
