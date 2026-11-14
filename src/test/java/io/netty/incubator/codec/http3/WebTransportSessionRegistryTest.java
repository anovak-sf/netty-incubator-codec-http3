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
import io.netty.channel.ChannelHandlerAdapter;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.handler.codec.quic.QuicStreamType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static io.netty.incubator.codec.http3.WebTransportCodecUtils.WT_BUFFERED_STREAM_REJECTED;
import static io.netty.incubator.codec.http3.WebTransportCodecUtils.WT_SESSION_GONE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
                    io.netty.handler.codec.quic.QuicStreamChannel ch) { }
            @Override
            public void onUnidirectionalStream(WebTransportSession s,
                    io.netty.handler.codec.quic.QuicStreamChannel ch) { }
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
    @Test
    void testDatagramsBufferedUntilSessionRegistered() {
        WebTransportSessionRegistry registry = WebTransportSessionRegistry.getOrCreate(quicChannel);
        AtomicInteger received = new AtomicInteger();
        ByteBuf first = Unpooled.buffer(1).writeByte(42);
        ByteBuf second = Unpooled.buffer(1).writeByte(43);
        registry.receiveDatagram(4, first);
        registry.receiveDatagram(0, second);
        EmbeddedQuicStreamChannel sessionStream = new EmbeddedQuicStreamChannel(
                quicChannel, false, QuicStreamType.BIDIRECTIONAL, 4, new ChannelHandlerAdapter() { });
        WebTransportSession session = new WebTransportSession(quicChannel, sessionStream, 4,
                new WebTransportSessionListener() {
                    @Override
                    public void onBidirectionalStream(WebTransportSession s, QuicStreamChannel ch) { }
                    @Override
                    public void onUnidirectionalStream(WebTransportSession s, QuicStreamChannel ch) { }
                    @Override
                    public void onDatagram(WebTransportSession s, ByteBuf payload) {
                        received.set(payload.readUnsignedByte());
                    }
                    @Override
                    public void onSessionClosed(WebTransportSession s, int code, String reason) { }
                });
        assertEquals(0, received.get());
        registry.register(4, session);
        assertEquals(42, received.get());
        assertEquals(0, first.refCnt());
        assertEquals(1, second.refCnt());
        quicChannel.finish();
        assertEquals(0, second.refCnt());
        assertFalse(sessionStream.finish());
    }

    @Test
    void testDatagramCountAndByteLimitsReleaseExcess() {
        WebTransportSessionRegistry registry = WebTransportSessionRegistry.getOrCreate(quicChannel);
        for (int i = 0; i < WebTransportSessionRegistry.MAX_BUFFERED_DATAGRAMS; i++) {
            registry.receiveDatagram(0, Unpooled.buffer(1).writeByte(i));
        }
        ByteBuf excess = Unpooled.buffer(1).writeByte(0);
        registry.receiveDatagram(0, excess);
        assertEquals(0, excess.refCnt());
        registry.deregister(0);
        ByteBuf large = Unpooled.buffer(WebTransportSessionRegistry.MAX_BUFFERED_DATAGRAM_BYTES)
                .writeZero(WebTransportSessionRegistry.MAX_BUFFERED_DATAGRAM_BYTES);
        registry.receiveDatagram(4, large);
        ByteBuf oneMore = Unpooled.buffer(1).writeByte(0);
        registry.receiveDatagram(4, oneMore);
        assertEquals(0, oneMore.refCnt());
        quicChannel.finish();
        assertEquals(0, large.refCnt());
    }

    @Test
    void testDatagramsForClosedSessionReleased() {
        WebTransportSessionRegistry registry = WebTransportSessionRegistry.getOrCreate(quicChannel);
        ByteBuf beforeClose = Unpooled.buffer(1).writeByte(0);
        registry.receiveDatagram(0, beforeClose);
        registry.deregister(0);
        assertEquals(0, beforeClose.refCnt());
        ByteBuf afterClose = Unpooled.buffer(1).writeByte(0);
        registry.receiveDatagram(0, afterClose);
        assertEquals(0, afterClose.refCnt());
    }

    @Test
    void testBufferedStreamLimitRejectsOnlyExcessStream() {
        EmbeddedQuicStreamChannel[] streams =
                new EmbeddedQuicStreamChannel[WebTransportSessionRegistry.MAX_BUFFERED_STREAMS];
        for (int i = 0; i < streams.length; i++) {
            streams[i] = pendingStream(0);
            assertFalse(streams[i].isInputShutdown());
        }
        EmbeddedQuicStreamChannel excess = pendingStream(0);
        assertEquals(WT_BUFFERED_STREAM_REJECTED, excess.inputShutdownError());
        assertTrue(quicChannel.isActive());
        assertFalse(excess.finish());
        for (EmbeddedQuicStreamChannel stream : streams) {
            assertFalse(stream.finish());
        }
    }

    @Test
    void testBufferedStreamByteLimitReleasesPayload() {
        EmbeddedQuicStreamChannel stream = pendingStream(0);
        ByteBuf excess = Unpooled.buffer(WebTransportSessionRegistry.MAX_BUFFERED_STREAM_BYTES + 1)
                .writeZero(WebTransportSessionRegistry.MAX_BUFFERED_STREAM_BYTES + 1);
        assertFalse(stream.writeInbound(excess));
        assertEquals(WT_BUFFERED_STREAM_REJECTED, stream.inputShutdownError());
        assertEquals(0, excess.refCnt());
        assertTrue(quicChannel.isActive());
        assertFalse(stream.finish());
    }

    @Test
    void testStreamForClosedSessionUsesSessionGone() {
        WebTransportSessionRegistry.getOrCreate(quicChannel).deregister(0);
        EmbeddedQuicStreamChannel stream = pendingStream(0);
        assertEquals(WT_SESSION_GONE, stream.inputShutdownError());
        assertTrue(quicChannel.isActive());
        assertFalse(stream.finish());
    }

    @Test
    void testClosingSessionInStreamCallbackReleasesBufferedData() {
        EmbeddedQuicStreamChannel stream = pendingStream(0);
        ByteBuf payload = Unpooled.buffer(1).writeByte(42);
        assertFalse(stream.writeInbound(payload));
        EmbeddedQuicStreamChannel sessionStream = new EmbeddedQuicStreamChannel(
                quicChannel, false, QuicStreamType.BIDIRECTIONAL, 0, new ChannelHandlerAdapter() { });
        WebTransportSession session = new WebTransportSession(quicChannel, sessionStream, 0,
                new WebTransportSessionListener() {
                    @Override
                    public void onBidirectionalStream(WebTransportSession s, QuicStreamChannel ch) { }
                    @Override
                    public void onUnidirectionalStream(WebTransportSession s, QuicStreamChannel ch) {
                        s.terminate(0, "");
                    }
                    @Override
                    public void onDatagram(WebTransportSession s, ByteBuf data) { }
                    @Override
                    public void onSessionClosed(WebTransportSession s, int code, String reason) { }
                });
        WebTransportSessionRegistry.getOrCreate(quicChannel).register(0, session);
        assertEquals(0, payload.refCnt());
        assertEquals(WT_SESSION_GONE, stream.inputShutdownError());
        assertFalse(stream.finish());
        assertFalse(sessionStream.finish());
    }

    private EmbeddedQuicStreamChannel pendingStream(long sessionId) {
        EmbeddedQuicStreamChannel stream = new EmbeddedQuicStreamChannel(
                quicChannel, false, QuicStreamType.UNIDIRECTIONAL, 2, new ChannelInboundHandlerAdapter());
        ChannelHandlerContext ctx = stream.pipeline().lastContext();
        WebTransportSessionRegistry.initStream(ctx, sessionId, false);
        return stream;
    }

}
