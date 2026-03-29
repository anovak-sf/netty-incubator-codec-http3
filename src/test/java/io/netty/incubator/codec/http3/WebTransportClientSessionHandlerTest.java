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
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.incubator.codec.quic.QuicStreamChannel;
import io.netty.incubator.codec.quic.QuicStreamType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class WebTransportClientSessionHandlerTest {

    private EmbeddedQuicChannel parent;

    @BeforeEach
    void setUp() {
        parent = new EmbeddedQuicChannel(false); // client side
    }

    @AfterEach
    void tearDown() {
        parent.finish();
    }

    private static WebTransportSessionListener noopListener() {
        return new WebTransportSessionListener() {
            @Override
            public void onBidirectionalStream(WebTransportSession s, QuicStreamChannel c) { }
            @Override
            public void onUnidirectionalStream(WebTransportSession s, QuicStreamChannel c) { }
            @Override
            public void onDatagram(WebTransportSession s, ByteBuf p) { }
            @Override
            public void onSessionClosed(WebTransportSession s, int code, String reason) { }
        };
    }

    private static DefaultHttp3Headers connectHeaders() {
        DefaultHttp3Headers h = new DefaultHttp3Headers();
        h.method("CONNECT");
        h.add(Http3Headers.PseudoHeaderName.PROTOCOL.value(), "webtransport");
        h.authority("example.com");
        h.path("/chat");
        h.scheme("https");
        return h;
    }

    private static DefaultHttp3Headers response2xx(String status) {
        DefaultHttp3Headers h = new DefaultHttp3Headers();
        h.status(status);
        h.add(Http3Headers.PseudoHeaderName.PROTOCOL.value(), "webtransport");
        return h;
    }

    /**
     * Creates a stream channel with WebTransportClientSessionHandler and captures the
     * session promise.
     */
    private EmbeddedQuicStreamChannel newClientStream(ChannelPromise[] promiseOut,
                                                       io.netty.channel.ChannelHandler... extra) {
        ChannelPromise promise = parent.newPromise();
        if (promiseOut != null) {
            promiseOut[0] = promise;
        }
        List<io.netty.channel.ChannelHandler> handlers = new ArrayList<>();
        handlers.add(new WebTransportClientSessionHandler(connectHeaders(), noopListener(), promise));
        for (io.netty.channel.ChannelHandler h : extra) {
            handlers.add(h);
        }
        return new EmbeddedQuicStreamChannel(parent, true, QuicStreamType.BIDIRECTIONAL, 0,
                handlers.toArray(new io.netty.channel.ChannelHandler[0]));
    }

    @Test
    void testConnectFrameSentOnChannelActive() {
        ChannelPromise[] p = new ChannelPromise[1];
        EmbeddedQuicStreamChannel ch = newClientStream(p);

        // channelActive fires when EmbeddedChannel is constructed
        Object written = ch.readOutbound();
        assertInstanceOf(DefaultHttp3HeadersFrame.class, written);
        DefaultHttp3HeadersFrame frame = (DefaultHttp3HeadersFrame) written;
        assertEquals("CONNECT", frame.headers().method().toString());
        assertEquals("webtransport",
                frame.headers().get(Http3Headers.PseudoHeaderName.PROTOCOL.value()).toString());
        assertFalse(ch.finish());
    }

    @Test
    void testSessionEstablishedOn2xxResponse() {
        ChannelPromise[] p = new ChannelPromise[1];
        EmbeddedQuicStreamChannel ch = newClientStream(p);
        ch.readOutbound(); // drain CONNECT frame

        ch.writeInbound(new DefaultHttp3HeadersFrame(response2xx("200")));
        assertTrue(p[0].isSuccess());
        assertFalse(ch.finish());
    }

    @ParameterizedTest(name = "{index}: status={0}")
    @ValueSource(strings = {"200", "201", "204"})
    void testSessionEstablishedOn2xxVariants(String status) {
        ChannelPromise[] p = new ChannelPromise[1];
        EmbeddedQuicStreamChannel ch = newClientStream(p);
        ch.readOutbound();

        ch.writeInbound(new DefaultHttp3HeadersFrame(response2xx(status)));
        assertTrue(p[0].isSuccess());
        assertFalse(ch.finish());
    }

    @Test
    void testSessionRejectedOn4xxResponse() {
        ChannelPromise[] p = new ChannelPromise[1];
        EmbeddedQuicStreamChannel ch = newClientStream(p);
        ch.readOutbound();

        DefaultHttp3Headers h = new DefaultHttp3Headers();
        h.status("400");
        ch.writeInbound(new DefaultHttp3HeadersFrame(h));

        assertTrue(p[0].isDone());
        assertFalse(p[0].isSuccess());
        assertFalse(ch.finish());
    }

    @Test
    void testSessionRejectedOn5xxResponse() {
        ChannelPromise[] p = new ChannelPromise[1];
        EmbeddedQuicStreamChannel ch = newClientStream(p);
        ch.readOutbound();

        DefaultHttp3Headers h = new DefaultHttp3Headers();
        h.status("503");
        ch.writeInbound(new DefaultHttp3HeadersFrame(h));

        assertTrue(p[0].isDone());
        assertFalse(p[0].isSuccess());
        assertFalse(ch.finish());
    }

    @Test
    void testSessionRejectedMissingProtocolHeader() {
        ChannelPromise[] p = new ChannelPromise[1];
        EmbeddedQuicStreamChannel ch = newClientStream(p);
        ch.readOutbound();

        DefaultHttp3Headers h = new DefaultHttp3Headers();
        h.status("200");
        // deliberately no :protocol header
        ch.writeInbound(new DefaultHttp3HeadersFrame(h));

        assertTrue(p[0].isDone());
        assertFalse(p[0].isSuccess());
        assertFalse(ch.finish());
    }

    @Test
    void testPipelineRewiredAfterSuccess() {
        ChannelPromise[] p = new ChannelPromise[1];
        EmbeddedQuicStreamChannel ch = newClientStream(p);
        ch.readOutbound();

        ch.writeInbound(new DefaultHttp3HeadersFrame(response2xx("200")));

        assertTrue(p[0].isSuccess());
        // Client handler replaced by session stream handler
        assertNull(ch.pipeline().get(WebTransportClientSessionHandler.class));
        assertNotNull(ch.pipeline().get(WebTransportSessionStreamHandler.class));
        // Encoder added
        assertNotNull(ch.pipeline().get(WebTransportCapsuleEncoder.class));
        assertFalse(ch.finish());
    }

    @Test
    void testSessionRegisteredInRegistryAfterSuccess() {
        ChannelPromise[] p = new ChannelPromise[1];
        EmbeddedQuicStreamChannel ch = newClientStream(p);
        ch.readOutbound();

        ch.writeInbound(new DefaultHttp3HeadersFrame(response2xx("200")));

        WebTransportSessionRegistry registry = WebTransportSessionRegistry.get(parent);
        assertNotNull(registry);
        assertNotNull(registry.find(ch.streamId()));
        assertFalse(ch.finish());
    }

    @Test
    void testChannelInactiveBeforeResponseFailsPromise() {
        ChannelPromise[] p = new ChannelPromise[1];
        EmbeddedQuicStreamChannel ch = newClientStream(p);
        ch.readOutbound(); // drain CONNECT frame

        // Close channel before any response
        ch.close();

        assertTrue(p[0].isDone());
        assertFalse(p[0].isSuccess());
        assertFalse(ch.finish());
    }

    @Test
    void testEstablishedEventFiredAfterSuccess() {
        List<Object> events = new ArrayList<>();
        ChannelPromise[] p = new ChannelPromise[1];
        EmbeddedQuicStreamChannel ch = newClientStream(p,
                new ChannelInboundHandlerAdapter() {
                    @Override
                    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
                        events.add(evt);
                    }
                });
        ch.readOutbound();

        ch.writeInbound(new DefaultHttp3HeadersFrame(response2xx("200")));

        assertTrue(events.stream().anyMatch(e -> e instanceof WebTransportSessionEstablishedEvent),
                "Expected WebTransportSessionEstablishedEvent");
        assertFalse(ch.finish());
    }
}
