/*
 * Copyright 2026 The Netty Project
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
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.ChannelInputShutdownEvent;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.quic.InsecureQuicTokenHandler;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicSslContextBuilder;
import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.util.CharsetUtil;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Date;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in browser interoperability smoke test. Requires Node.js 22 or newer and
 * {@code -Dwebtransport.chrome=/path/to/chrome}; {@code -Dwebtransport.node=/path/to/node}
 * optionally selects the Node executable. Chrome always uses a disposable profile.
 */
@EnabledIfSystemProperty(named = "webtransport.chrome", matches = ".+")
class WebTransportChromeTest {

    @Test
    @Timeout(75)
    void testChromeHandshakeDatagramStreamAndClose() throws Exception {
        KeyPairGenerator keyGenerator = KeyPairGenerator.getInstance("EC");
        keyGenerator.initialize(256);
        KeyPair keyPair = keyGenerator.generateKeyPair();
        X509Certificate certificate = newCertificate(keyPair);
        EventLoopGroup group = new NioEventLoopGroup(1);
        Path directory = Files.createTempDirectory("netty-webtransport-chrome-");
        Channel server = null;
        Process browserTest = null;
        AtomicReference<QuicChannel> connection = new AtomicReference<>();
        BlockingQueue<String> datagrams = new LinkedBlockingQueue<>();
        BlockingQueue<String> streams = new LinkedBlockingQueue<>();
        BlockingQueue<WebTransportCloseSessionCapsule> closes = new LinkedBlockingQueue<>();
        BlockingQueue<Throwable> errors = new LinkedBlockingQueue<>();
        try {
            ChannelFuture bind = new Bootstrap().group(group).channel(NioDatagramChannel.class)
                    .handler(Http3.newQuicServerCodecBuilder()
                            .initialMaxData(1000000)
                            .initialMaxStreamDataBidirectionalLocal(100000)
                            .initialMaxStreamDataBidirectionalRemote(100000)
                            .initialMaxStreamsBidirectional(10)
                            .datagram(16, 16)
                            .sslContext(QuicSslContextBuilder.forServer(keyPair.getPrivate(), null, certificate)
                                    .applicationProtocols(Http3.supportedApplicationProtocols()).build())
                            .tokenHandler(InsecureQuicTokenHandler.INSTANCE)
                            .handler(new ChannelInitializer<QuicChannel>() {
                                @Override
                                protected void initChannel(QuicChannel channel) {
                                    connection.set(channel);
                                    channel.pipeline().addLast(new Http3WebTransportServerConnectionHandler(headers ->
                                            listener(datagrams, streams, closes, errors)));
                                }
                            }).build())
                    .bind("127.0.0.1", 0);
            bind.get(10, TimeUnit.SECONDS);
            server = bind.channel();
            int port = ((InetSocketAddress) server.localAddress()).getPort();
            String certificateHash = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256")
                    .digest(certificate.getEncoded()));
            Path script = directory.resolve("webtransport-chrome-smoke.js");
            try (InputStream source = WebTransportChromeTest.class.getResourceAsStream(
                    "/webtransport-chrome-smoke.js")) {
                assertNotNull(source);
                Files.copy(source, script);
            }
            Path log = directory.resolve("browser.log");
            browserTest = new ProcessBuilder(System.getProperty("webtransport.node", "node"), script.toString(),
                    System.getProperty("webtransport.chrome"), "https://127.0.0.1:" + port + "/webtransport",
                    certificateHash,
                    directory.resolve("profile").toString())
                    .redirectErrorStream(true).redirectOutput(log.toFile()).start();
            boolean exited = browserTest.waitFor(45, TimeUnit.SECONDS);
            String output = new String(Files.readAllBytes(log), CharsetUtil.UTF_8);
            assertTrue(exited, "Chrome test timed out: " + output + errors);
            assertEquals(0, browserTest.exitValue(), output + errors);
            assertTrue(output.contains("WEBTRANSPORT_CHROME_OK"), output);
            assertEquals("chrome-datagram", datagrams.poll(5, TimeUnit.SECONDS), output + errors);
            assertEquals("chrome-stream", streams.poll(5, TimeUnit.SECONDS), output + errors);
            WebTransportCloseSessionCapsule close = closes.poll(5, TimeUnit.SECONDS);
            assertNotNull(close, output + errors);
            assertEquals(73, close.applicationErrorCode());
            assertEquals("chrome close", close.errorReason());
            assertTrue(errors.isEmpty(), errors.toString());
        } finally {
            if (browserTest != null && browserTest.isAlive()) {
                browserTest.destroy();
                if (!browserTest.waitFor(5, TimeUnit.SECONDS)) {
                    browserTest.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
                }
            }
            if (connection.get() != null) {
                connection.get().close().awaitUninterruptibly(5, TimeUnit.SECONDS);
            }
            if (server != null) {
                server.close().awaitUninterruptibly(5, TimeUnit.SECONDS);
            }
            group.shutdownGracefully(0, 5, TimeUnit.SECONDS).awaitUninterruptibly(5, TimeUnit.SECONDS);
            Files.walkFileTree(directory, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
                    Files.deleteIfExists(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }

    private static X509Certificate newCertificate(KeyPair keyPair) throws Exception {
        long now = System.currentTimeMillis();
        X500Name name = new X500Name("CN=localhost");
        X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(name, BigInteger.valueOf(now),
                new Date(now - TimeUnit.DAYS.toMillis(1)), new Date(now + TimeUnit.DAYS.toMillis(7)),
                name, keyPair.getPublic());
        // Chrome's QUIC certificate parser requires an X.509v3 extensions block.
        builder.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(new GeneralName[] {
                new GeneralName(GeneralName.dNSName, "localhost"),
                new GeneralName(GeneralName.iPAddress, "127.0.0.1")
        }));
        return new JcaX509CertificateConverter().getCertificate(builder.build(
                new JcaContentSignerBuilder("SHA256withECDSA").build(keyPair.getPrivate())));
    }

    private static WebTransportSessionListener listener(BlockingQueue<String> datagrams,
            BlockingQueue<String> streams, BlockingQueue<WebTransportCloseSessionCapsule> closes,
            BlockingQueue<Throwable> errors) {
        return new WebTransportSessionListener() {
            @Override
            public void onBidirectionalStream(WebTransportSession session, QuicStreamChannel stream) {
                stream.pipeline().addLast(new SimpleChannelInboundHandler<ByteBuf>() {
                    private final StringBuilder payload = new StringBuilder();

                    @Override
                    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf bytes) {
                        payload.append(bytes.toString(CharsetUtil.US_ASCII));
                        ctx.writeAndFlush(bytes.retainedDuplicate());
                    }

                    @Override
                    public void userEventTriggered(ChannelHandlerContext ctx, Object event) {
                        if (event instanceof ChannelInputShutdownEvent) {
                            streams.offer(payload.toString());
                            stream.shutdownOutput();
                        }
                        ctx.fireUserEventTriggered(event);
                    }

                    @Override
                    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                        errors.offer(cause);
                        ctx.close();
                    }
                });
            }

            @Override
            public void onUnidirectionalStream(WebTransportSession session, QuicStreamChannel stream) {
                errors.offer(new AssertionError("Unexpected unidirectional stream"));
            }

            @Override
            public void onDatagram(WebTransportSession session, ByteBuf bytes) {
                datagrams.offer(bytes.toString(CharsetUtil.US_ASCII));
                session.sendDatagram(bytes).addListener(future -> {
                    if (!future.isSuccess()) {
                        errors.offer(future.cause());
                    }
                });
            }

            @Override
            public void onSessionClosed(WebTransportSession session, int code, String reason) {
                closes.offer(new WebTransportCloseSessionCapsule(code, reason));
            }
        };
    }
}
