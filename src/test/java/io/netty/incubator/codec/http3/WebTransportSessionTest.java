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
import io.netty.incubator.codec.quic.QuicStreamChannel;
import io.netty.incubator.codec.quic.QuicStreamType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.netty.incubator.codec.http3.Http3CodecUtils.numBytesForVariableLengthInteger;
import static io.netty.incubator.codec.http3.Http3CodecUtils.readVariableLengthInteger;
import static io.netty.incubator.codec.http3.WebTransportCodecUtils.WT_STREAM_TYPE_BIDIRECTIONAL;
import static io.netty.incubator.codec.http3.WebTransportCodecUtils.WT_STREAM_TYPE_UNIDIRECTIONAL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
        sessionStream.finish();
        quicChannel.finish();
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

        // The prefix handler writes [0x41][sessionId varint] on channelActive
        ByteBuf prefix = ((EmbeddedQuicStreamChannel) newStream).readOutbound();
        assertNotNull(prefix);

        // First byte: 0x41
        assertEquals(WT_STREAM_TYPE_BIDIRECTIONAL, prefix.readByte() & 0xFF);

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

        // First byte: 0x54
        assertEquals(WT_STREAM_TYPE_UNIDIRECTIONAL, prefix.readByte() & 0xFF);

        // Session ID varint
        int sidLen = numBytesForVariableLengthInteger(prefix.getByte(prefix.readerIndex()));
        long sidRead = readVariableLengthInteger(prefix, sidLen);
        assertEquals(SESSION_ID, sidRead);

        prefix.release();
        assertFalse(((EmbeddedQuicStreamChannel) newStream).finish());
    }

    @Test
    void testSendDatagramWritesCapsule() {
        ByteBuf payload = Unpooled.wrappedBuffer(new byte[]{1, 2, 3});
        session.sendDatagram(payload);
        payload.release();

        Object written = sessionStream.readOutbound();
        assertInstanceOf(WebTransportDatagramCapsule.class, written);
        WebTransportDatagramCapsule capsule = (WebTransportDatagramCapsule) written;
        assertEquals(3, capsule.content().readableBytes());
        capsule.release();
    }

    @Test
    void testSendDatagramRetainsOriginalBuf() {
        ByteBuf payload = Unpooled.buffer().writeBytes(new byte[]{10, 20});
        assertEquals(1, payload.refCnt());

        session.sendDatagram(payload);
        // retainedDuplicate() increments refCnt; caller still owns the buffer (refCnt = 2)
        assertEquals(2, payload.refCnt());

        payload.release();
        // Drain the written capsule
        WebTransportDatagramCapsule capsule = sessionStream.readOutbound();
        assertNotNull(capsule);
        capsule.release();
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
    void testCloseSessionThenClosesChannel() throws Exception {
        assertTrue(sessionStream.isActive());
        session.closeSession(0, "").sync();
        // After the write+close chain completes, the session stream should be closed
        assertFalse(sessionStream.isActive());
    }
}
