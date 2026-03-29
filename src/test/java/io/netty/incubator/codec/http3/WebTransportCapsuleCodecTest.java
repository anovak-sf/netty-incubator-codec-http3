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
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.incubator.codec.quic.QuicStreamType;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static io.netty.incubator.codec.http3.Http3CodecUtils.writeVariableLengthInteger;
import static io.netty.incubator.codec.http3.WebTransportCodecUtils.CAPSULE_TYPE_CLOSE_WEBTRANSPORT_SESSION;
import static io.netty.incubator.codec.http3.WebTransportCodecUtils.CAPSULE_TYPE_DATAGRAM;
import static io.netty.incubator.codec.http3.WebTransportCodecUtils.CAPSULE_TYPE_DRAIN_WEBTRANSPORT_SESSION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class WebTransportCapsuleCodecTest {

    // ---- Encoder tests ----

    @Test
    void testEncodeDatagramCapsule() {
        EmbeddedChannel ch = new EmbeddedChannel(WebTransportCapsuleEncoder.INSTANCE);
        byte[] payload = {1, 2, 3};
        WebTransportDatagramCapsule capsule =
                new WebTransportDatagramCapsule(Unpooled.wrappedBuffer(payload));

        assertTrue(ch.writeOutbound(capsule));
        ByteBuf encoded = ch.readOutbound();
        assertNotNull(encoded);

        // type = 0x00 (1-byte varint)
        assertEquals(1, Http3CodecUtils.numBytesForVariableLengthInteger(encoded.getByte(encoded.readerIndex())));
        long type = Http3CodecUtils.readVariableLengthInteger(encoded,
                Http3CodecUtils.numBytesForVariableLengthInteger(encoded.getByte(encoded.readerIndex())));
        assertEquals(CAPSULE_TYPE_DATAGRAM, type);

        // length = 3 (1-byte varint)
        long length = Http3CodecUtils.readVariableLengthInteger(encoded,
                Http3CodecUtils.numBytesForVariableLengthInteger(encoded.getByte(encoded.readerIndex())));
        assertEquals(3, length);

        // payload
        byte[] actualPayload = new byte[3];
        encoded.readBytes(actualPayload);
        for (int i = 0; i < payload.length; i++) {
            assertEquals(payload[i], actualPayload[i]);
        }
        encoded.release();
        assertFalse(ch.finish());
    }

    @Test
    void testEncodeDatagramCapsuleEmpty() {
        EmbeddedChannel ch = new EmbeddedChannel(WebTransportCapsuleEncoder.INSTANCE);
        WebTransportDatagramCapsule capsule =
                new WebTransportDatagramCapsule(Unpooled.EMPTY_BUFFER);

        assertTrue(ch.writeOutbound(capsule));
        ByteBuf encoded = ch.readOutbound();
        assertNotNull(encoded);

        // type = 0x00
        long type = Http3CodecUtils.readVariableLengthInteger(encoded,
                Http3CodecUtils.numBytesForVariableLengthInteger(encoded.getByte(encoded.readerIndex())));
        assertEquals(CAPSULE_TYPE_DATAGRAM, type);

        // length = 0
        long length = Http3CodecUtils.readVariableLengthInteger(encoded,
                Http3CodecUtils.numBytesForVariableLengthInteger(encoded.getByte(encoded.readerIndex())));
        assertEquals(0, length);
        assertFalse(encoded.isReadable());

        encoded.release();
        assertFalse(ch.finish());
    }

    @Test
    void testEncodeCloseSessionCapsule() {
        EmbeddedChannel ch = new EmbeddedChannel(WebTransportCapsuleEncoder.INSTANCE);
        WebTransportCloseSessionCapsule capsule = new WebTransportCloseSessionCapsule(42, "bye");

        assertTrue(ch.writeOutbound(capsule));
        ByteBuf encoded = ch.readOutbound();
        assertNotNull(encoded);

        // type = 0x2843
        long type = Http3CodecUtils.readVariableLengthInteger(encoded,
                Http3CodecUtils.numBytesForVariableLengthInteger(encoded.getByte(encoded.readerIndex())));
        assertEquals(CAPSULE_TYPE_CLOSE_WEBTRANSPORT_SESSION, type);

        // length = 4 + 3 bytes reason
        long length = Http3CodecUtils.readVariableLengthInteger(encoded,
                Http3CodecUtils.numBytesForVariableLengthInteger(encoded.getByte(encoded.readerIndex())));
        assertEquals(7, length);

        // error code
        assertEquals(42, encoded.readInt());

        // reason
        byte[] reasonBytes = new byte[3];
        encoded.readBytes(reasonBytes);
        assertEquals("bye", new String(reasonBytes, StandardCharsets.UTF_8));

        encoded.release();
        assertFalse(ch.finish());
    }

    @Test
    void testEncodeCloseSessionCapsuleEmptyReason() {
        EmbeddedChannel ch = new EmbeddedChannel(WebTransportCapsuleEncoder.INSTANCE);
        WebTransportCloseSessionCapsule capsule = new WebTransportCloseSessionCapsule(0, "");

        assertTrue(ch.writeOutbound(capsule));
        ByteBuf encoded = ch.readOutbound();
        assertNotNull(encoded);

        // type
        Http3CodecUtils.readVariableLengthInteger(encoded,
                Http3CodecUtils.numBytesForVariableLengthInteger(encoded.getByte(encoded.readerIndex())));
        // length = 4 (just error code, no reason)
        long length = Http3CodecUtils.readVariableLengthInteger(encoded,
                Http3CodecUtils.numBytesForVariableLengthInteger(encoded.getByte(encoded.readerIndex())));
        assertEquals(4, length);

        // error code
        assertEquals(0, encoded.readInt());
        assertFalse(encoded.isReadable());

        encoded.release();
        assertFalse(ch.finish());
    }

    @Test
    void testEncodeCloseSessionCapsuleMaxReason() {
        EmbeddedChannel ch = new EmbeddedChannel(WebTransportCapsuleEncoder.INSTANCE);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 1024; i++) {
            sb.append('x');
        }
        String reason = sb.toString();
        WebTransportCloseSessionCapsule capsule = new WebTransportCloseSessionCapsule(1, reason);

        assertTrue(ch.writeOutbound(capsule));
        ByteBuf encoded = ch.readOutbound();
        assertNotNull(encoded);

        // type
        Http3CodecUtils.readVariableLengthInteger(encoded,
                Http3CodecUtils.numBytesForVariableLengthInteger(encoded.getByte(encoded.readerIndex())));
        // length = 4 + 1024
        long length = Http3CodecUtils.readVariableLengthInteger(encoded,
                Http3CodecUtils.numBytesForVariableLengthInteger(encoded.getByte(encoded.readerIndex())));
        assertEquals(1028, length);

        encoded.release();
        assertFalse(ch.finish());
    }

    @Test
    void testEncodeDrainSessionCapsule() {
        EmbeddedChannel ch = new EmbeddedChannel(WebTransportCapsuleEncoder.INSTANCE);
        assertTrue(ch.writeOutbound(WebTransportDrainSessionCapsule.INSTANCE));
        ByteBuf encoded = ch.readOutbound();
        assertNotNull(encoded);

        // type = 0x78ae
        long type = Http3CodecUtils.readVariableLengthInteger(encoded,
                Http3CodecUtils.numBytesForVariableLengthInteger(encoded.getByte(encoded.readerIndex())));
        assertEquals(CAPSULE_TYPE_DRAIN_WEBTRANSPORT_SESSION, type);

        // length = 0
        long length = Http3CodecUtils.readVariableLengthInteger(encoded,
                Http3CodecUtils.numBytesForVariableLengthInteger(encoded.getByte(encoded.readerIndex())));
        assertEquals(0, length);
        assertFalse(encoded.isReadable());

        encoded.release();
        assertFalse(ch.finish());
    }

    @Test
    void testEncodeReleasesPayload() {
        EmbeddedChannel ch = new EmbeddedChannel(WebTransportCapsuleEncoder.INSTANCE);
        ByteBuf buf = Unpooled.buffer().writeByte(0xFF);
        WebTransportDatagramCapsule capsule = new WebTransportDatagramCapsule(buf);
        assertEquals(1, capsule.refCnt());

        assertTrue(ch.writeOutbound(capsule));
        // After encode the capsule should be released
        assertEquals(0, capsule.refCnt());

        ByteBuf encoded = ch.readOutbound();
        encoded.release();
        assertFalse(ch.finish());
    }

    // ---- Decoder tests ----

    private static ByteBuf encodedCapsule(long type, byte[] payload) {
        ByteBuf buf = Unpooled.buffer();
        writeVariableLengthInteger(buf, type);
        writeVariableLengthInteger(buf, payload.length);
        buf.writeBytes(payload);
        return buf;
    }

    @Test
    void testDecodeDatagramCapsule() {
        EmbeddedChannel ch = new EmbeddedChannel(new WebTransportCapsuleDecoder());
        byte[] payload = {10, 20, 30};
        assertTrue(ch.writeInbound(encodedCapsule(CAPSULE_TYPE_DATAGRAM, payload)));

        WebTransportDatagramCapsule capsule = ch.readInbound();
        assertNotNull(capsule);
        ByteBuf content = capsule.content();
        assertEquals(3, content.readableBytes());
        assertEquals(10, content.readByte());
        assertEquals(20, content.readByte());
        assertEquals(30, content.readByte());
        capsule.release();
        assertFalse(ch.finish());
    }

    @Test
    void testDecodeDatagramCapsuleEmpty() {
        EmbeddedChannel ch = new EmbeddedChannel(new WebTransportCapsuleDecoder());
        assertTrue(ch.writeInbound(encodedCapsule(CAPSULE_TYPE_DATAGRAM, new byte[0])));

        WebTransportDatagramCapsule capsule = ch.readInbound();
        assertNotNull(capsule);
        assertFalse(capsule.content().isReadable());
        capsule.release();
        assertFalse(ch.finish());
    }

    @Test
    void testDecodeCloseSessionCapsule() {
        EmbeddedChannel ch = new EmbeddedChannel(new WebTransportCapsuleDecoder());
        byte[] reason = "oops".getBytes(StandardCharsets.UTF_8);
        ByteBuf buf = Unpooled.buffer();
        writeVariableLengthInteger(buf, CAPSULE_TYPE_CLOSE_WEBTRANSPORT_SESSION);
        writeVariableLengthInteger(buf, 4 + reason.length);
        buf.writeInt(99);
        buf.writeBytes(reason);

        assertTrue(ch.writeInbound(buf));
        WebTransportCloseSessionCapsule capsule = ch.readInbound();
        assertNotNull(capsule);
        assertEquals(99, capsule.applicationErrorCode());
        assertEquals("oops", capsule.errorReason());
        assertFalse(ch.finish());
    }

    @Test
    void testDecodeCloseSessionCapsuleEmptyReason() {
        EmbeddedChannel ch = new EmbeddedChannel(new WebTransportCapsuleDecoder());
        ByteBuf buf = Unpooled.buffer();
        writeVariableLengthInteger(buf, CAPSULE_TYPE_CLOSE_WEBTRANSPORT_SESSION);
        writeVariableLengthInteger(buf, 4);
        buf.writeInt(0);

        assertTrue(ch.writeInbound(buf));
        WebTransportCloseSessionCapsule capsule = ch.readInbound();
        assertNotNull(capsule);
        assertEquals(0, capsule.applicationErrorCode());
        assertEquals("", capsule.errorReason());
        assertFalse(ch.finish());
    }

    @Test
    void testDecodeCloseSessionCapsuleUtf8Reason() {
        EmbeddedChannel ch = new EmbeddedChannel(new WebTransportCapsuleDecoder());
        String reason = "\u00e9\u00e0\u00fc"; // é, à, ü — 2 bytes each in UTF-8
        byte[] reasonBytes = reason.getBytes(StandardCharsets.UTF_8);
        ByteBuf buf = Unpooled.buffer();
        writeVariableLengthInteger(buf, CAPSULE_TYPE_CLOSE_WEBTRANSPORT_SESSION);
        writeVariableLengthInteger(buf, 4 + reasonBytes.length);
        buf.writeInt(1);
        buf.writeBytes(reasonBytes);

        assertTrue(ch.writeInbound(buf));
        WebTransportCloseSessionCapsule capsule = ch.readInbound();
        assertNotNull(capsule);
        assertEquals(reason, capsule.errorReason());
        assertFalse(ch.finish());
    }

    @Test
    void testDecodeDrainSessionCapsule() {
        EmbeddedChannel ch = new EmbeddedChannel(new WebTransportCapsuleDecoder());
        ByteBuf buf = Unpooled.buffer();
        writeVariableLengthInteger(buf, CAPSULE_TYPE_DRAIN_WEBTRANSPORT_SESSION);
        writeVariableLengthInteger(buf, 0);

        assertTrue(ch.writeInbound(buf));
        Object capsule = ch.readInbound();
        assertSame(WebTransportDrainSessionCapsule.INSTANCE, capsule);
        assertFalse(ch.finish());
    }

    @Test
    void testDecodeUnknownCapsuleTypeSilentlyDiscarded() {
        EmbeddedChannel ch = new EmbeddedChannel(new WebTransportCapsuleDecoder());
        // type 0x01 is not a known WebTransport capsule type (DATA frame type, not a capsule)
        assertFalse(ch.writeInbound(encodedCapsule(0x01, new byte[]{5, 6})));
        assertNull(ch.readInbound());
        assertFalse(ch.finish());
    }

    @Test
    void testDecodeMultipleUnknownTypes() {
        EmbeddedChannel ch = new EmbeddedChannel(new WebTransportCapsuleDecoder());
        ByteBuf buf = Unpooled.buffer();
        // two unknown capsules concatenated
        ByteBuf capsule1 = encodedCapsule(0x01, new byte[]{1});
        ByteBuf capsule2 = encodedCapsule(0x02, new byte[]{2, 3});
        buf.writeBytes(capsule1);
        buf.writeBytes(capsule2);
        capsule1.release();
        capsule2.release();

        assertFalse(ch.writeInbound(buf));
        assertNull(ch.readInbound());
        assertFalse(ch.finish());
    }

    @Test
    void testDecodeFragmented() {
        EmbeddedChannel ch = new EmbeddedChannel(new WebTransportCapsuleDecoder());
        ByteBuf full = encodedCapsule(CAPSULE_TYPE_DATAGRAM, new byte[]{7, 8, 9});

        // Feed one byte at a time
        boolean anyEmitted = false;
        while (full.isReadable()) {
            ByteBuf slice = full.readRetainedSlice(1);
            boolean emitted = ch.writeInbound(slice);
            if (emitted) {
                anyEmitted = true;
            }
        }
        full.release();
        assertTrue(anyEmitted);

        WebTransportDatagramCapsule capsule = ch.readInbound();
        assertNotNull(capsule);
        assertEquals(3, capsule.content().readableBytes());
        capsule.release();
        assertFalse(ch.finish());
    }

    @Test
    void testDecodeMultipleCapsules() {
        EmbeddedChannel ch = new EmbeddedChannel(new WebTransportCapsuleDecoder());
        ByteBuf combined = Unpooled.buffer();
        combined.writeBytes(encodedCapsule(CAPSULE_TYPE_DATAGRAM, new byte[]{1}));
        combined.writeBytes(encodedCapsule(CAPSULE_TYPE_DATAGRAM, new byte[]{2}));

        assertTrue(ch.writeInbound(combined));

        WebTransportDatagramCapsule first = ch.readInbound();
        assertNotNull(first);
        assertEquals(1, first.content().readByte());
        first.release();

        WebTransportDatagramCapsule second = ch.readInbound();
        assertNotNull(second);
        assertEquals(2, second.content().readByte());
        second.release();

        assertFalse(ch.finish());
    }

    @Test
    void testDecodeTruncatedCapsuleWaitsForMore() {
        EmbeddedChannel ch = new EmbeddedChannel(new WebTransportCapsuleDecoder());
        ByteBuf partial = Unpooled.buffer();
        writeVariableLengthInteger(partial, CAPSULE_TYPE_DATAGRAM);
        writeVariableLengthInteger(partial, 4); // claims 4 bytes but we won't provide them yet
        partial.writeBytes(new byte[]{1, 2}); // only 2 of 4 bytes

        assertFalse(ch.writeInbound(partial));
        assertNull(ch.readInbound());

        // Now provide the remaining 2 bytes
        assertTrue(ch.writeInbound(Unpooled.wrappedBuffer(new byte[]{3, 4})));
        WebTransportDatagramCapsule capsule = ch.readInbound();
        assertNotNull(capsule);
        assertEquals(4, capsule.content().readableBytes());
        capsule.release();
        assertFalse(ch.finish());
    }

    @Test
    void testDecodeCapsulesWithLargeVarintLength() {
        EmbeddedChannel ch = new EmbeddedChannel(new WebTransportCapsuleDecoder());
        // Use a 2-byte varint for the length (length = 64, encoded as 2-byte varint 0x4040)
        byte[] payload = new byte[64];
        for (int i = 0; i < 64; i++) {
            payload[i] = (byte) i;
        }
        // Build capsule manually with 2-byte length varint
        ByteBuf buf = Unpooled.buffer();
        writeVariableLengthInteger(buf, CAPSULE_TYPE_DATAGRAM);
        writeVariableLengthInteger(buf, 64); // 64 requires 2-byte varint
        buf.writeBytes(payload);

        assertTrue(ch.writeInbound(buf));
        WebTransportDatagramCapsule capsule = ch.readInbound();
        assertNotNull(capsule);
        assertEquals(64, capsule.content().readableBytes());
        capsule.release();
        assertFalse(ch.finish());
    }

    @Test
    void testDecodeCloseWithTooShortPayload() {
        EmbeddedQuicChannel parent = new EmbeddedQuicChannel(true);
        EmbeddedQuicStreamChannel ch = new EmbeddedQuicStreamChannel(
                parent, false, QuicStreamType.BIDIRECTIONAL, 0,
                new WebTransportCapsuleDecoder());

        ByteBuf buf = Unpooled.buffer();
        writeVariableLengthInteger(buf, CAPSULE_TYPE_CLOSE_WEBTRANSPORT_SESSION);
        writeVariableLengthInteger(buf, 3); // only 3 bytes, need at least 4
        buf.writeBytes(new byte[]{1, 2, 3});

        ch.writeInbound(buf);
        Http3TestUtils.verifyClose(Http3ErrorCode.H3_MESSAGE_ERROR, parent);
        assertFalse(ch.finish());
        assertFalse(parent.finish());
    }

    // ---- Round-trip tests ----

    @Test
    void testRoundTripDatagram() {
        EmbeddedChannel encoder = new EmbeddedChannel(WebTransportCapsuleEncoder.INSTANCE);
        EmbeddedChannel decoder = new EmbeddedChannel(new WebTransportCapsuleDecoder());

        byte[] payload = {100, 101, 102, 103};
        WebTransportDatagramCapsule original =
                new WebTransportDatagramCapsule(Unpooled.wrappedBuffer(payload));

        assertTrue(encoder.writeOutbound(original));
        ByteBuf encoded = encoder.readOutbound();
        assertNotNull(encoded);

        assertTrue(decoder.writeInbound(encoded));
        WebTransportDatagramCapsule decoded = decoder.readInbound();
        assertNotNull(decoded);
        assertEquals(4, decoded.content().readableBytes());
        for (byte b : payload) {
            assertEquals(b, decoded.content().readByte());
        }
        decoded.release();

        assertFalse(encoder.finish());
        assertFalse(decoder.finish());
    }

    @Test
    void testRoundTripClose() {
        EmbeddedChannel encoder = new EmbeddedChannel(WebTransportCapsuleEncoder.INSTANCE);
        EmbeddedChannel decoder = new EmbeddedChannel(new WebTransportCapsuleDecoder());

        WebTransportCloseSessionCapsule original = new WebTransportCloseSessionCapsule(12345, "test reason");

        assertTrue(encoder.writeOutbound(original));
        ByteBuf encoded = encoder.readOutbound();
        assertNotNull(encoded);

        assertTrue(decoder.writeInbound(encoded));
        WebTransportCloseSessionCapsule decoded = decoder.readInbound();
        assertNotNull(decoded);
        assertEquals(12345, decoded.applicationErrorCode());
        assertEquals("test reason", decoded.errorReason());

        assertFalse(encoder.finish());
        assertFalse(decoder.finish());
    }
}
