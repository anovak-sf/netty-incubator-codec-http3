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

import io.netty.incubator.codec.quic.QuicStreamType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

public class WebTransportSessionRegistryTest {

    private EmbeddedQuicChannel quicChannel;

    @BeforeEach
    void setUp() {
        quicChannel = new EmbeddedQuicChannel(true);
    }

    @AfterEach
    void tearDown() {
        quicChannel.finish();
    }

    private WebTransportSession newSession(long sessionId) {
        EmbeddedQuicStreamChannel streamChannel =
                new EmbeddedQuicStreamChannel(quicChannel, false, QuicStreamType.BIDIRECTIONAL,
                        sessionId, new io.netty.channel.ChannelHandlerAdapter() { });
        return new WebTransportSession(quicChannel, streamChannel, sessionId, noopListener());
    }

    private static WebTransportSessionListener noopListener() {
        return new WebTransportSessionListener() {
            @Override
            public void onBidirectionalStream(WebTransportSession s,
                    io.netty.incubator.codec.quic.QuicStreamChannel ch) { }
            @Override
            public void onUnidirectionalStream(WebTransportSession s,
                    io.netty.incubator.codec.quic.QuicStreamChannel ch) { }
            @Override
            public void onDatagram(WebTransportSession s, io.netty.buffer.ByteBuf payload) { }
            @Override
            public void onSessionClosed(WebTransportSession s, int errorCode, String reason) { }
        };
    }

    @Test
    void testGetOrCreateCreatesRegistryOnFirstCall() {
        WebTransportSessionRegistry registry = WebTransportSessionRegistry.getOrCreate(quicChannel);
        assertNotNull(registry);
        assertNotNull(quicChannel.attr(WebTransportSessionRegistry.REGISTRY_KEY).get());
    }

    @Test
    void testGetOrCreateReturnsExistingRegistry() {
        WebTransportSessionRegistry first = WebTransportSessionRegistry.getOrCreate(quicChannel);
        WebTransportSessionRegistry second = WebTransportSessionRegistry.getOrCreate(quicChannel);
        assertSame(first, second);
    }

    @Test
    void testGetReturnsNullWhenAbsent() {
        assertNull(WebTransportSessionRegistry.get(quicChannel));
    }

    @Test
    void testGetReturnsRegistryAfterCreate() {
        WebTransportSessionRegistry created = WebTransportSessionRegistry.getOrCreate(quicChannel);
        assertSame(created, WebTransportSessionRegistry.get(quicChannel));
    }

    @Test
    void testRegisterAndFind() {
        WebTransportSessionRegistry registry = WebTransportSessionRegistry.getOrCreate(quicChannel);
        WebTransportSession session = newSession(0L);
        registry.register(0L, session);
        assertSame(session, registry.find(0L));
        ((EmbeddedQuicStreamChannel) session.sessionStream()).finish();
    }

    @Test
    void testFindReturnsNullForUnknownId() {
        WebTransportSessionRegistry registry = WebTransportSessionRegistry.getOrCreate(quicChannel);
        assertNull(registry.find(999L));
    }

    @Test
    void testDeregisterRemovesSession() {
        WebTransportSessionRegistry registry = WebTransportSessionRegistry.getOrCreate(quicChannel);
        WebTransportSession session = newSession(4L);
        registry.register(4L, session);
        assertNotNull(registry.find(4L));
        registry.deregister(4L);
        assertNull(registry.find(4L));
        ((EmbeddedQuicStreamChannel) session.sessionStream()).finish();
    }

    @Test
    void testDeregisterNonExistentIdNoOp() {
        WebTransportSessionRegistry registry = WebTransportSessionRegistry.getOrCreate(quicChannel);
        // Should not throw
        registry.deregister(12345L);
    }

    @Test
    void testMultipleSessionsIsolated() {
        WebTransportSessionRegistry registry = WebTransportSessionRegistry.getOrCreate(quicChannel);
        WebTransportSession s0 = newSession(0L);
        WebTransportSession s4 = newSession(4L);

        registry.register(0L, s0);
        registry.register(4L, s4);

        assertSame(s0, registry.find(0L));
        assertSame(s4, registry.find(4L));
        assertNotSame(s0, s4);

        registry.deregister(0L);
        assertNull(registry.find(0L));
        assertSame(s4, registry.find(4L));

        ((EmbeddedQuicStreamChannel) s0.sessionStream()).finish();
        ((EmbeddedQuicStreamChannel) s4.sessionStream()).finish();
    }
}
