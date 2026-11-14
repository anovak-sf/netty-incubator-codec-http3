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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import io.netty.util.concurrent.Future;

import java.nio.channels.ClosedChannelException;

import static io.netty.incubator.codec.http3.Http3CodecUtils.numBytesForVariableLengthInteger;
import static io.netty.incubator.codec.http3.Http3CodecUtils.readVariableLengthInteger;
import static io.netty.incubator.codec.http3.WebTransportCodecUtils.WT_STREAM_TYPE_BIDIRECTIONAL;
import static io.netty.incubator.codec.http3.WebTransportCodecUtils.WT_STREAM_TYPE_UNIDIRECTIONAL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class WebTransportSessionTest {

    private EmbeddedQuicChannel quicChannel;
    private EmbeddedQuicStreamChannel sessionStream;
    private WebTransportSession session;
    private static final long SESSION_ID = 0L;

    @BeforeEach
    void setUp() {
        quicChannel = new EmbeddedQuicChannel(false); // client
        sessionStream = new EmbeddedQuicStreamChannel(
                quicChannel, true, QuicStreamType.BIDIRECTIONAL, SESSION_ID,
                new ChannelHandlerAdapter() { });
        session = new WebTransportSession(quicChannel, sessionStream, SESSION_ID, noopListener());
    }

    @AfterEach
    void tearDown() {
        sessionStream.finishAndReleaseAll();
        quicChannel.finishAndReleaseAll();
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

    @Test
    void testSessionIdReturnsConnectStreamId() {
        assertEquals(SESSION_ID, session.sessionId());
    }

    @Test
    void testOpenBidirectionalStreamWritesPrefix() throws Exception {
        QuicStreamChannel newStream = session.openBidirectionalStream(
                new ChannelHandlerAdapter() { }).get();
        assertNotNull(newStream);

        // The prefix handler writes [streamType(varint)][sessionId(varint)] on channelActive.
        ByteBuf prefix = ((EmbeddedQuicStreamChannel) newStream).readOutbound();
        assertNotNull(prefix);

        // Stream type varint: 0x41 (bidi) — 2 bytes on the wire.
        int typeLen = numBytesForVariableLengthInteger(prefix.getByte(prefix.readerIndex()));
        long typeRead = readVariableLengthInteger(prefix, typeLen);
        assertEquals(WT_STREAM_TYPE_BIDIRECTIONAL, (int) typeRead);

        // Session ID varint
        int sidLen = numBytesForVariableLengthInteger(prefix.getByte(prefix.readerIndex()));
        long sidRead = readVariableLengthInteger(prefix, sidLen);
        assertEquals(SESSION_ID, sidRead);

        prefix.release();
        assertFalse(((EmbeddedQuicStreamChannel) newStream).finish());
    }

    @Test
    void testOpenUnidirectionalStreamWritesPrefix() throws Exception {
        QuicStreamChannel newStream = session.openUnidirectionalStream(
                new ChannelHandlerAdapter() { }).get();
        assertNotNull(newStream);

        ByteBuf prefix = ((EmbeddedQuicStreamChannel) newStream).readOutbound();
        assertNotNull(prefix);

        // Stream type varint: 0x54 (uni) — 2 bytes on the wire.
        int typeLen = numBytesForVariableLengthInteger(prefix.getByte(prefix.readerIndex()));
        long typeRead = readVariableLengthInteger(prefix, typeLen);
        assertEquals(WT_STREAM_TYPE_UNIDIRECTIONAL, (int) typeRead);

        // Session ID varint
        int sidLen = numBytesForVariableLengthInteger(prefix.getByte(prefix.readerIndex()));
        long sidRead = readVariableLengthInteger(prefix, sidLen);
        assertEquals(SESSION_ID, sidRead);

        prefix.release();
        assertFalse(((EmbeddedQuicStreamChannel) newStream).finish());
    }

    @Test
    void testSendDatagramWritesQuicDatagram() {
        ByteBuf payload = Unpooled.wrappedBuffer(new byte[]{1, 2, 3});
        session.sendDatagram(payload);
        payload.release();

        ByteBuf datagram = quicChannel.readOutbound();
        assertEquals(0, datagram.readUnsignedByte());
        assertEquals(3, datagram.readableBytes());
        assertEquals(1, datagram.readUnsignedByte());
        assertEquals(2, datagram.readUnsignedByte());
        assertEquals(3, datagram.readUnsignedByte());
        datagram.release();
        assertNull(sessionStream.readOutbound());
    }

    @Test
    void testSendDatagramRetainsOriginalBuf() {
        ByteBuf payload = Unpooled.buffer().writeBytes(new byte[]{10, 20});
        assertEquals(1, payload.refCnt());

        session.sendDatagram(payload);
        // The caller's ownership and reader index are preserved.
        assertEquals(1, payload.refCnt());
        assertEquals(0, payload.readerIndex());

        payload.release();
        ByteBuf datagram = quicChannel.readOutbound();
        assertNotNull(datagram);
        datagram.release();
    }

    @Test
    void testCloseSessionWritesCloseCapsule() {
        session.closeSession(42, "done");

        Object written = sessionStream.readOutbound();
        assertInstanceOf(WebTransportCloseSessionCapsule.class, written);
        WebTransportCloseSessionCapsule capsule = (WebTransportCloseSessionCapsule) written;
        assertEquals(42, capsule.applicationErrorCode());
        assertEquals("done", capsule.errorReason());
    }

    @Test
    void testCloseSessionSendsFin() throws Exception {
        assertTrue(sessionStream.isActive());
        session.closeSession(0, "").sync();
        assertTrue(sessionStream.isOutputShutdown());
        assertTrue(session.isClosed());
    }

    @Test
    void testDatagramUsesQuarterStreamIdWithoutContextId() {
        WebTransportSession otherSession = new WebTransportSession(quicChannel, sessionStream, 256, noopListener());
        ByteBuf payload = Unpooled.buffer().writeByte(42);
        otherSession.sendDatagram(payload);
        payload.release();
        ByteBuf datagram = quicChannel.readOutbound();
        assertEquals(64, readVariableLengthInteger(datagram, 2));
        assertEquals(42, datagram.readUnsignedByte());
        assertFalse(datagram.isReadable());
        datagram.release();
    }

    @Test
    void testSessionClosureResetsAllAssociatedStreamsAndRejectsNewTraffic() throws Exception {
        EmbeddedQuicStreamChannel outbound = (EmbeddedQuicStreamChannel) session.openBidirectionalStream(
                new ChannelHandlerAdapter() { }).get();
        EmbeddedQuicStreamChannel inbound = new EmbeddedQuicStreamChannel(
                quicChannel, false, QuicStreamType.UNIDIRECTIONAL, 3);
        try {
            assertTrue(session.addStream(inbound));
            session.closeSession(0, "").sync();
            assertEquals(WebTransportCodecUtils.WT_SESSION_GONE, outbound.outputShutdownError());
            assertEquals(WebTransportCodecUtils.WT_SESSION_GONE, inbound.inputShutdownError());
            assertNull(inbound.outputShutdownError());
            assertFalse(session.openBidirectionalStream(new ChannelHandlerAdapter() { }).isSuccess());
            assertFalse(session.openUnidirectionalStream(new ChannelHandlerAdapter() { }).isSuccess());
            ByteBuf payload = Unpooled.buffer().writeByte(1);
            assertFalse(session.sendDatagram(payload).isSuccess());
            assertEquals(1, payload.refCnt());
            payload.release();
            assertNull(quicChannel.readOutbound());
        } finally {
            outbound.finishAndReleaseAll();
            inbound.finishAndReleaseAll();
        }
    }

    @ParameterizedTest
    @EnumSource(QuicStreamType.class)
    void testSessionTerminationDuringStreamCreationFailsFuture(QuicStreamType type) {
        EmbeddedQuicStreamChannel[] created = new EmbeddedQuicStreamChannel[1];
        ChannelInboundHandlerAdapter handler = new ChannelInboundHandlerAdapter() {
            @Override
            public void channelActive(ChannelHandlerContext ctx) {
                created[0] = (EmbeddedQuicStreamChannel) ctx.channel();
                session.terminate(0, "");
                ctx.fireChannelActive();
            }
        };
        Future<QuicStreamChannel> future = type == QuicStreamType.BIDIRECTIONAL
                ? session.openBidirectionalStream(handler) : session.openUnidirectionalStream(handler);
        assertNotNull(created[0]);
        try {
            assertTrue(future.isDone());
            assertFalse(future.isSuccess());
            assertInstanceOf(ClosedChannelException.class, future.cause());
            assertEquals(WebTransportCodecUtils.WT_SESSION_GONE, created[0].outputShutdownError());
            if (type == QuicStreamType.UNIDIRECTIONAL) {
                assertNull(created[0].inputShutdownError());
            } else {
                assertEquals(WebTransportCodecUtils.WT_SESSION_GONE, created[0].inputShutdownError());
            }
        } finally {
            created[0].finishAndReleaseAll();
        }
    }

    @Test
    void testPeerFinTerminatesSessionBeforeChannelInactive() {
        sessionStream.pipeline().addLast(new WebTransportSessionStreamHandler(session));
        WebTransportSessionRegistry.getOrCreate(quicChannel).register(SESSION_ID, session);
        sessionStream.writeInboundFin();
        assertTrue(session.isClosed());
        assertTrue(sessionStream.isOutputShutdown());
        assertNull(WebTransportSessionRegistry.get(quicChannel).find(SESSION_ID));
    }

    @Test
    void testCloseCapsuleNotifiesOnceAndDrainIsExposed() {
        int[] calls = new int[2];
        WebTransportSession observed = new WebTransportSession(quicChannel, sessionStream, SESSION_ID,
                new WebTransportSessionListener() {
                    @Override
                    public void onBidirectionalStream(WebTransportSession s, QuicStreamChannel c) { }
                    @Override
                    public void onUnidirectionalStream(WebTransportSession s, QuicStreamChannel c) { }
                    @Override
                    public void onDatagram(WebTransportSession s, ByteBuf data) { }
                    @Override
                    public void onSessionDraining(WebTransportSession s) {
                        calls[0]++;
                    }
                    @Override
                    public void onSessionClosed(WebTransportSession s, int code, String reason) {
                        assertEquals(42, code);
                        assertEquals("done", reason);
                        calls[1]++;
                    }
                });
        sessionStream.pipeline().addLast(new WebTransportSessionStreamHandler(observed));
        sessionStream.writeInbound(WebTransportDrainSessionCapsule.INSTANCE);
        assertFalse(observed.isClosed());
        sessionStream.writeInbound(new WebTransportCloseSessionCapsule(42, "done"));
        assertTrue(observed.isClosed());
        sessionStream.writeInboundFin();
        sessionStream.close();
        assertEquals(1, calls[0]);
        assertEquals(1, calls[1]);
    }
}
