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
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.handler.codec.quic.QuicStreamType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class WebTransportServerHandlerTest {

    private EmbeddedQuicChannel parent;

    @BeforeEach
    void setUp() {
        parent = new EmbeddedQuicChannel(true);
    }

    @AfterEach
    void tearDown() {
        parent.finish();
    }

    private static DefaultHttp3Headers wtConnectHeaders() {
        DefaultHttp3Headers h = new DefaultHttp3Headers();
        h.method("CONNECT");
        h.add(Http3Headers.PseudoHeaderName.PROTOCOL.value(), "webtransport");
        h.authority("example.com");
        h.path("/chat");
        h.scheme("https");
        return h;
    }

    private static WebTransportSessionListener noopListener() {
        return new WebTransportSessionListener() {
            @Override
            public void onBidirectionalStream(WebTransportSession s, QuicStreamChannel ch) { }
            @Override
            public void onUnidirectionalStream(WebTransportSession s, QuicStreamChannel ch) { }
            @Override
            public void onDatagram(WebTransportSession s, ByteBuf payload) { }
            @Override
            public void onSessionClosed(WebTransportSession s, int errorCode, String reason) { }
        };
    }

    private EmbeddedQuicStreamChannel newStreamChannel(WebTransportSessionListener listener,
                                                       io.netty.channel.ChannelHandler... extra) {
        WebTransportSessionAcceptor acceptor = headers -> listener;
        List<io.netty.channel.ChannelHandler> handlers = new ArrayList<>();
        handlers.add(new WebTransportServerHandler(acceptor));
        for (io.netty.channel.ChannelHandler h : extra) {
            handlers.add(h);
        }
        return new EmbeddedQuicStreamChannel(parent, false, QuicStreamType.BIDIRECTIONAL, 0,
                handlers.toArray(new io.netty.channel.ChannelHandler[0]));
    }

    @Test
    void testWaitsForPeerSettingsAndReplaysCapsules() throws Exception {
        Http3WebTransportServerConnectionHandler connection =
                new Http3WebTransportServerConnectionHandler(headers -> null);
        parent.pipeline().addLast(connection);
        parent.pipeline().fireUserEventTriggered(Http3TestUtils.datagramExtensionEvent());
        AtomicInteger datagram = new AtomicInteger(-1);
        WebTransportSessionListener listener = new WebTransportSessionListener() {
            @Override
            public void onBidirectionalStream(WebTransportSession session, QuicStreamChannel stream) { }
            @Override
            public void onUnidirectionalStream(WebTransportSession session, QuicStreamChannel stream) { }
            @Override
            public void onDatagram(WebTransportSession session, ByteBuf payload) {
                datagram.set(payload.getUnsignedByte(payload.readerIndex()));
            }
            @Override
            public void onSessionClosed(WebTransportSession session, int code, String reason) { }
        };
        EmbeddedQuicStreamChannel ch = newStreamChannel(listener);
        ch.writeInbound(new DefaultHttp3HeadersFrame(wtConnectHeaders()));
        ch.writeInbound(capsuleFrame(new WebTransportDatagramCapsule(Unpooled.buffer().writeByte(47))));
        assertNull(ch.readOutbound());
        assertNull(WebTransportSessionRegistry.get(parent));
        assertEquals(-1, datagram.get());

        // Also verifies that the server's no-settings constructor actually enables WebTransport.
        connection.peerSettingsReceived(new DefaultHttp3SettingsFrame(new Http3Settings()
                .enableH3Datagram(true).enableWebTransport(true)));
        parent.runPendingTasks();
        assertEquals("200", ((Http3HeadersFrame) ch.readOutbound()).headers().status().toString());
        assertEquals(47, datagram.get());
        assertFalse(ch.finishAndReleaseAll());
        parent.localControlStream().finishAndReleaseAll();
    }

    @Test
    void testRejectsPeerWithoutWebTransportSettings() {
        Http3WebTransportServerConnectionHandler connection =
                new Http3WebTransportServerConnectionHandler(headers -> null);
        parent.pipeline().addLast(connection);
        EmbeddedQuicStreamChannel ch = newStreamChannel(noopListener());
        ch.writeInbound(new DefaultHttp3HeadersFrame(wtConnectHeaders()));
        connection.peerSettingsReceived(new DefaultHttp3SettingsFrame());
        parent.runPendingTasks();
        assertEquals("400", ((Http3HeadersFrame) ch.readOutbound()).headers().status().toString());
        assertNull(WebTransportSessionRegistry.get(parent));
        assertFalse(ch.finishAndReleaseAll());
        parent.localControlStream().finishAndReleaseAll();
    }

    @Test
    void testFinWhileWaitingForSettingsReleasesBufferedData() {
        Http3WebTransportServerConnectionHandler connection =
                new Http3WebTransportServerConnectionHandler(headers -> null);
        parent.pipeline().addLast(connection);
        EmbeddedQuicStreamChannel ch = newStreamChannel(noopListener());
        ch.writeInbound(new DefaultHttp3HeadersFrame(wtConnectHeaders()));
        ByteBuf bytes = Unpooled.buffer().writeByte(0);
        ch.writeInbound(new DefaultHttp3DataFrame(bytes));
        assertEquals(1, bytes.refCnt());
        ch.writeInboundFin();
        assertEquals(0, bytes.refCnt());
        connection.peerSettingsReceived(new DefaultHttp3SettingsFrame(new Http3Settings()
                .enableH3Datagram(true).enableWebTransport(true)));
        parent.runPendingTasks();
        assertNull(ch.readOutbound());
        assertFalse(ch.finishAndReleaseAll());
        parent.localControlStream().finishAndReleaseAll();
    }

    // ---- Happy path: session establishment ----

    @Test
    void testAcceptsWebTransportConnect() {
        EmbeddedQuicStreamChannel ch = newStreamChannel(noopListener());
        ch.writeInbound(new DefaultHttp3HeadersFrame(wtConnectHeaders()));

        // :protocol is a request-only pseudo-header.
        DefaultHttp3HeadersFrame resp = ch.readOutbound();
        assertNotNull(resp);
        assertEquals("200", resp.headers().status().toString());
        assertEquals("draft02", resp.headers().get("sec-webtransport-http3-draft").toString());
        assertNull(resp.headers().get(Http3Headers.PseudoHeaderName.PROTOCOL.value()));
        assertFalse(ch.finish());
    }

    @Test
    void testPipelineRewiredAfterAccept() {
        EmbeddedQuicStreamChannel ch = newStreamChannel(noopListener());
        ch.writeInbound(new DefaultHttp3HeadersFrame(wtConnectHeaders()));
        ch.readOutbound(); // consume response

        // WebTransportServerHandler replaced by WebTransportSessionStreamHandler
        assertNull(ch.pipeline().get(WebTransportServerHandler.class));
        assertNotNull(ch.pipeline().get(WebTransportSessionStreamHandler.class));
        assertFalse(ch.finish());
    }

    @Test
    void testCapsuleEncoderAddedAfterAccept() {
        EmbeddedQuicStreamChannel ch = newStreamChannel(noopListener());
        ch.writeInbound(new DefaultHttp3HeadersFrame(wtConnectHeaders()));
        ch.readOutbound(); // consume response

        assertNotNull(ch.pipeline().get(WebTransportCapsuleEncoder.class));
        assertFalse(ch.finish());
    }

    @Test
    void testSessionRegisteredInRegistry() {
        EmbeddedQuicStreamChannel ch = newStreamChannel(noopListener());
        ch.writeInbound(new DefaultHttp3HeadersFrame(wtConnectHeaders()));
        ch.readOutbound(); // consume response

        WebTransportSessionRegistry registry = WebTransportSessionRegistry.get(parent);
        assertNotNull(registry);
        assertNotNull(registry.find(ch.streamId()));
        assertFalse(ch.finish());
    }

    @Test
    void testSessionIdEqualsStreamId() {
        EmbeddedQuicStreamChannel ch = newStreamChannel(noopListener());
        ch.writeInbound(new DefaultHttp3HeadersFrame(wtConnectHeaders()));
        ch.readOutbound(); // consume response

        WebTransportSessionRegistry registry = WebTransportSessionRegistry.get(parent);
        assertNotNull(registry);
        WebTransportSession session = registry.find(ch.streamId());
        assertNotNull(session);
        assertEquals(ch.streamId(), session.sessionId());
        assertFalse(ch.finish());
    }

    @Test
    void testSessionEstablishedEventFired() {
        List<Object> events = new ArrayList<>();
        EmbeddedQuicStreamChannel ch = newStreamChannel(noopListener(),
                new ChannelInboundHandlerAdapter() {
                    @Override
                    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
                        events.add(evt);
                    }
                });
        ch.writeInbound(new DefaultHttp3HeadersFrame(wtConnectHeaders()));
        ch.readOutbound();

        assertTrue(events.stream().anyMatch(e -> e instanceof WebTransportSessionEstablishedEvent),
                "Expected WebTransportSessionEstablishedEvent to be fired");
        assertFalse(ch.finish());
    }

    // ---- Rejection cases ----

    @Test
    void testRejectsWhenAcceptorReturnsNull() {
        WebTransportSessionAcceptor rejectAll = headers -> null;
        EmbeddedQuicStreamChannel ch = new EmbeddedQuicStreamChannel(
                parent, false, QuicStreamType.BIDIRECTIONAL, 0,
                new WebTransportServerHandler(rejectAll));
        ch.writeInbound(new DefaultHttp3HeadersFrame(wtConnectHeaders()));

        DefaultHttp3HeadersFrame resp = ch.readOutbound();
        assertNotNull(resp);
        assertEquals("400", resp.headers().status().toString());
        assertTrue(ch.isOutputShutdown());
        assertEquals(0, ch.outputShutdownError());
        assertFalse(ch.finish());
    }

    @Test
    void testRejectsMissingAuthority() {
        DefaultHttp3Headers headers = wtConnectHeaders();
        headers.remove(Http3Headers.PseudoHeaderName.AUTHORITY.value());
        assertRejectedWithStatus(headers, "400");
    }

    @Test
    void testRejectsMissingPath() {
        DefaultHttp3Headers headers = wtConnectHeaders();
        headers.remove(Http3Headers.PseudoHeaderName.PATH.value());
        assertRejectedWithStatus(headers, "400");
    }

    @Test
    void testRejectsMissingScheme() {
        DefaultHttp3Headers headers = wtConnectHeaders();
        headers.remove(Http3Headers.PseudoHeaderName.SCHEME.value());
        assertRejectedWithStatus(headers, "400");
    }

    @Test
    void testRejectsHttpScheme() {
        DefaultHttp3Headers headers = wtConnectHeaders();
        headers.scheme("http");
        assertRejectedWithStatus(headers, "400");
    }

    @ParameterizedTest
    @ValueSource(strings = {"content-length", "content-type", "transfer-encoding"})
    void testRejectsForbiddenCapsuleHeaders(String name) {
        DefaultHttp3Headers headers = wtConnectHeaders();
        headers.add(name, "0");
        assertRejectedWithStatus(headers, "400");
    }

    private void assertRejectedWithStatus(DefaultHttp3Headers headers, String expectedStatus) {
        WebTransportSessionAcceptor acceptAll = h -> noopListener();
        EmbeddedQuicStreamChannel ch = new EmbeddedQuicStreamChannel(
                parent, false, QuicStreamType.BIDIRECTIONAL, 0,
                new WebTransportServerHandler(acceptAll));
        ch.writeInbound(new DefaultHttp3HeadersFrame(headers));

        DefaultHttp3HeadersFrame resp = ch.readOutbound();
        assertNotNull(resp);
        assertEquals(expectedStatus, resp.headers().status().toString());
        assertFalse(ch.finish());
    }

    @Test
    void testRejectsNonConnectMethod() {
        DefaultHttp3Headers headers = new DefaultHttp3Headers();
        headers.method("GET");
        headers.add(Http3Headers.PseudoHeaderName.PROTOCOL.value(), "webtransport");
        headers.authority("example.com");
        headers.path("/");
        headers.scheme("https");

        WebTransportSessionAcceptor acceptAll = h -> noopListener();
        EmbeddedQuicStreamChannel ch = new EmbeddedQuicStreamChannel(
                parent, false, QuicStreamType.BIDIRECTIONAL, 0,
                new WebTransportServerHandler(acceptAll));
        ch.writeInbound(new DefaultHttp3HeadersFrame(headers));

        // Without fallback: 405
        DefaultHttp3HeadersFrame resp = ch.readOutbound();
        assertNotNull(resp);
        assertEquals("405", resp.headers().status().toString());
        assertFalse(ch.finish());
    }

    @Test
    void testRejectsWrongProtocol() {
        DefaultHttp3Headers headers = new DefaultHttp3Headers();
        headers.method("CONNECT");
        headers.add(Http3Headers.PseudoHeaderName.PROTOCOL.value(), "grpc");
        headers.authority("example.com");
        headers.path("/");
        headers.scheme("https");

        List<Object> received = new ArrayList<>();
        io.netty.channel.ChannelHandler fallback = new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                received.add(msg);
            }
            @Override
            public boolean isSharable() {
                return false;
            }
        };

        WebTransportSessionAcceptor acceptAll = h -> noopListener();
        EmbeddedQuicStreamChannel ch = new EmbeddedQuicStreamChannel(
                parent, false, QuicStreamType.BIDIRECTIONAL, 0,
                new WebTransportServerHandler(acceptAll, fallback));
        ch.writeInbound(new DefaultHttp3HeadersFrame(headers));

        // Forwarded to fallback
        assertEquals(1, received.size());
        assertInstanceOf(DefaultHttp3HeadersFrame.class, received.get(0));
        assertFalse(ch.finish());
    }

    // ---- Fallback handler ----

    @Test
    void testNonWebTransportForwardedToFallback() {
        DefaultHttp3Headers plainGet = new DefaultHttp3Headers();
        plainGet.method("GET");
        plainGet.path("/foo");
        plainGet.scheme("https");
        plainGet.authority("example.com");

        List<Object> fallbackReceived = new ArrayList<>();
        io.netty.channel.ChannelHandler fallback = new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                fallbackReceived.add(msg);
            }
            @Override
            public boolean isSharable() {
                return false;
            }
        };

        WebTransportSessionAcceptor acceptAll = h -> noopListener();
        EmbeddedQuicStreamChannel ch = new EmbeddedQuicStreamChannel(
                parent, false, QuicStreamType.BIDIRECTIONAL, 0,
                new WebTransportServerHandler(acceptAll, fallback));
        ch.writeInbound(new DefaultHttp3HeadersFrame(plainGet));

        assertEquals(1, fallbackReceived.size());
        // WebTransportServerHandler should no longer be in pipeline
        assertNull(ch.pipeline().get(WebTransportServerHandler.class));
        assertFalse(ch.finish());
    }

    // ---- Capsule dispatch (post-session) ----

    @Test
    void testDatagramCapsuleDispatchedToListener() {
        AtomicReference<ByteBuf> receivedPayload = new AtomicReference<>();
        WebTransportSessionListener listener = new WebTransportSessionListener() {
            @Override
            public void onBidirectionalStream(WebTransportSession s, QuicStreamChannel c) { }
            @Override
            public void onUnidirectionalStream(WebTransportSession s, QuicStreamChannel c) { }
            @Override
            public void onDatagram(WebTransportSession s, ByteBuf payload) {
                receivedPayload.set(payload.copy());
            }
            @Override
            public void onSessionClosed(WebTransportSession s, int code, String reason) { }
        };

        EmbeddedQuicStreamChannel ch = newStreamChannel(listener);
        ch.writeInbound(new DefaultHttp3HeadersFrame(wtConnectHeaders()));
        ch.readOutbound(); // consume 200 response

        ch.writeInbound(capsuleFrame(new WebTransportDatagramCapsule(Unpooled.wrappedBuffer(new byte[]{42}))));

        assertNotNull(receivedPayload.get());
        assertEquals(42, receivedPayload.get().readByte());
        receivedPayload.get().release();
        assertFalse(ch.finish());
    }

    @Test
    void testCloseSessionCapsuleTriggersOnSessionClosed() {
        AtomicInteger closedCode = new AtomicInteger(-1);
        AtomicReference<String> closedReason = new AtomicReference<>();
        WebTransportSessionListener listener = new WebTransportSessionListener() {
            @Override
            public void onBidirectionalStream(WebTransportSession s, QuicStreamChannel c) { }
            @Override
            public void onUnidirectionalStream(WebTransportSession s, QuicStreamChannel c) { }
            @Override
            public void onDatagram(WebTransportSession s, ByteBuf payload) { }
            @Override
            public void onSessionClosed(WebTransportSession s, int code, String reason) {
                closedCode.set(code);
                closedReason.set(reason);
            }
        };

        EmbeddedQuicStreamChannel ch = newStreamChannel(listener);
        ch.writeInbound(new DefaultHttp3HeadersFrame(wtConnectHeaders()));
        ch.readOutbound();

        ch.writeInbound(capsuleFrame(new WebTransportCloseSessionCapsule(7, "goodbye")));
        // Channel should be closed; channelInactive fires onSessionClosed
        assertEquals(7, closedCode.get());
        assertEquals("goodbye", closedReason.get());
        assertFalse(ch.finish());
    }

    @Test
    void testDrainCapsuleReceivedWithoutError() {
        EmbeddedQuicStreamChannel ch = newStreamChannel(noopListener());
        ch.writeInbound(new DefaultHttp3HeadersFrame(wtConnectHeaders()));
        ch.readOutbound();

        // Should not throw, channel should stay open
        ch.writeInbound(capsuleFrame(WebTransportDrainSessionCapsule.INSTANCE));
        assertTrue(ch.isActive());
        assertFalse(ch.finish());
    }

    private static Http3DataFrame capsuleFrame(WebTransportCapsule capsule) {
        EmbeddedChannel encoder = new EmbeddedChannel(WebTransportCapsuleEncoder.INSTANCE);
        encoder.writeOutbound(capsule);
        ByteBuf content = encoder.readOutbound();
        assertFalse(encoder.finishAndReleaseAll());
        return new DefaultHttp3DataFrame(content);
    }

    @Test
    void testChannelInactiveDeregistersSession() {
        EmbeddedQuicStreamChannel ch = newStreamChannel(noopListener());
        ch.writeInbound(new DefaultHttp3HeadersFrame(wtConnectHeaders()));
        ch.readOutbound();

        long sessionId = ch.streamId();
        WebTransportSessionRegistry registry = WebTransportSessionRegistry.get(parent);
        assertNotNull(registry);
        assertNotNull(registry.find(sessionId));

        ch.close();
        assertNull(registry.find(sessionId));
        assertFalse(ch.finish());
    }

    @Test
    void testChannelInactiveFiresOnSessionClosed() {
        AtomicInteger closedCode = new AtomicInteger(-1);
        WebTransportSessionListener listener = new WebTransportSessionListener() {
            @Override
            public void onBidirectionalStream(WebTransportSession s, QuicStreamChannel c) { }
            @Override
            public void onUnidirectionalStream(WebTransportSession s, QuicStreamChannel c) { }
            @Override
            public void onDatagram(WebTransportSession s, ByteBuf payload) { }
            @Override
            public void onSessionClosed(WebTransportSession s, int code, String reason) {
                closedCode.set(code);
            }
        };

        EmbeddedQuicStreamChannel ch = newStreamChannel(listener);
        ch.writeInbound(new DefaultHttp3HeadersFrame(wtConnectHeaders()));
        ch.readOutbound();

        ch.close();
        // Abrupt close: closeErrorCode stays 0 (default)
        assertEquals(0, closedCode.get());
        assertFalse(ch.finish());
    }
}
