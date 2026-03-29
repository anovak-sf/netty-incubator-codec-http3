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
import io.netty.incubator.codec.quic.QuicStreamChannel;
import io.netty.incubator.codec.quic.QuicStreamType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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

    // ---- Happy path: session establishment ----

    @Test
    void testAcceptsWebTransportConnect() {
        EmbeddedQuicStreamChannel ch = newStreamChannel(noopListener());
        ch.writeInbound(new DefaultHttp3HeadersFrame(wtConnectHeaders()));

        // Response: 200 with :protocol: webtransport
        DefaultHttp3HeadersFrame resp = ch.readOutbound();
        assertNotNull(resp);
        assertEquals("200", resp.headers().status().toString());
        assertEquals("webtransport",
                resp.headers().get(Http3Headers.PseudoHeaderName.PROTOCOL.value()).toString());
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

        // Write a DATAGRAM capsule object directly (no decoder in pipeline since Http3FrameCodec absent)
        ch.writeInbound(new WebTransportDatagramCapsule(Unpooled.wrappedBuffer(new byte[]{42})));

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

        ch.writeInbound(new WebTransportCloseSessionCapsule(7, "goodbye"));
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
        ch.writeInbound(WebTransportDrainSessionCapsule.INSTANCE);
        assertTrue(ch.isActive());
        assertFalse(ch.finish());
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
