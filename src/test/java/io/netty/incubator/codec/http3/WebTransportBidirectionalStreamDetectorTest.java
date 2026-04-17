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
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.incubator.codec.quic.QuicStreamChannel;
import io.netty.incubator.codec.quic.QuicStreamType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.concurrent.atomic.AtomicReference;

import static io.netty.incubator.codec.http3.Http3CodecUtils.writeVariableLengthInteger;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

public class WebTransportBidirectionalStreamDetectorTest {

    private EmbeddedQuicChannel quicChannel;
    private Http3WebTransportServerConnectionHandler connHandler;
    private EmbeddedQuicStreamChannel localControl;

    private void setup() throws Exception {
        quicChannel = new EmbeddedQuicChannel(true, new ChannelDuplexHandler());
        ChannelHandlerContext ctx = quicChannel.pipeline().firstContext();
        connHandler = new Http3WebTransportServerConnectionHandler(headers -> null);
        connHandler.handlerAdded(ctx);
        connHandler.channelRegistered(ctx);
        connHandler.channelActive(ctx);
        localControl = quicChannel.localControlStream();
        assertNotNull(localControl);
        localControl.releaseOutbound();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (quicChannel != null && connHandler != null) {
            ChannelHandlerContext ctx = quicChannel.pipeline().firstContext();
            if (ctx != null) {
                connHandler.channelInactive(ctx);
                connHandler.channelUnregistered(ctx);
                connHandler.handlerRemoved(ctx);
            }
            if (localControl != null) {
                localControl.finishAndReleaseAll();
            }
        }
    }

    /** Creates and routes a new bidirectional stream through the connection handler. */
    private EmbeddedQuicStreamChannel newBidiStream() throws Exception {
        EmbeddedQuicStreamChannel stream = (EmbeddedQuicStreamChannel)
                quicChannel.createStream(QuicStreamType.BIDIRECTIONAL, new ChannelDuplexHandler()).get();
        ChannelHandlerContext ctx = quicChannel.pipeline().firstContext();
        connHandler.channelRead(ctx, stream);
        return stream;
    }

    /** Registers a session with the given listener under sessionId. */
    private WebTransportSession registerSession(long sessionId, WebTransportSessionListener listener) {
        EmbeddedQuicStreamChannel sessionStream = new EmbeddedQuicStreamChannel(
                quicChannel, false, QuicStreamType.BIDIRECTIONAL, sessionId,
                new ChannelDuplexHandler());
        WebTransportSession session = new WebTransportSession(quicChannel, sessionStream, sessionId, listener);
        WebTransportSessionRegistry.getOrCreate(quicChannel).register(sessionId, session);
        return session;
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
    void testHttp3RequestStreamNot0x41() throws Exception {
        setup();
        EmbeddedQuicStreamChannel stream = newBidiStream();

        // First byte 0x00 (DATA frame type) — not a WT stream → HTTP/3 pipeline
        ByteBuf buf = Unpooled.buffer(1).writeByte(0x00);
        stream.writeInbound(buf);

        // Detector should be gone, Http3FrameCodec should be installed
        assertNull(stream.pipeline().get(WebTransportBidirectionalStreamDetector.class));
        assertNotNull(stream.pipeline().get(Http3FrameCodec.class));
        assertFalse(stream.finish());
    }

    @Test
    void testHttp3PipelineContentAfterDetector() throws Exception {
        setup();
        EmbeddedQuicStreamChannel stream = newBidiStream();

        ByteBuf buf = Unpooled.buffer(1).writeByte(0x01); // HEADERS type byte
        stream.writeInbound(buf);

        // HTTP/3 pipeline installed: codec + state validators + request handler
        assertNull(stream.pipeline().get(WebTransportBidirectionalStreamDetector.class));
        assertNotNull(stream.pipeline().get(Http3FrameCodec.class));
        assertNotNull(stream.pipeline().get(Http3RequestStreamEncodeStateValidator.class));
        assertNotNull(stream.pipeline().get(Http3RequestStreamDecodeStateValidator.class));
        assertFalse(stream.finish());
    }

    @Test
    void testWtBidirectionalStreamRouted() throws Exception {
        AtomicReference<QuicStreamChannel> notifiedStream = new AtomicReference<>();
        WebTransportSessionListener listener = new WebTransportSessionListener() {
            @Override
            public void onBidirectionalStream(WebTransportSession s, QuicStreamChannel c) {
                notifiedStream.set(c);
            }
            @Override
            public void onUnidirectionalStream(WebTransportSession s, QuicStreamChannel c) { }
            @Override
            public void onDatagram(WebTransportSession s, ByteBuf p) { }
            @Override
            public void onSessionClosed(WebTransportSession s, int code, String reason) { }
        };

        setup();
        long sessionId = 0L;
        WebTransportSession session = registerSession(sessionId, listener);

        EmbeddedQuicStreamChannel stream = newBidiStream();

        ByteBuf buf = Unpooled.buffer();
        writeVariableLengthInteger(buf, 0x41L); // WT_STREAM_TYPE_BIDIRECTIONAL (as varint → 2 bytes)
        writeVariableLengthInteger(buf, sessionId);
        stream.writeInbound(buf);

        // Detector removed, listener notified
        assertNull(stream.pipeline().get(WebTransportBidirectionalStreamDetector.class));
        assertNotNull(notifiedStream.get());
        assertFalse(stream.finish());
        assertFalse(((EmbeddedQuicStreamChannel) session.sessionStream()).finish());
    }

    @Test
    void testWtBidirectionalStreamUnknownSessionId() throws Exception {
        setup();
        EmbeddedQuicStreamChannel stream = newBidiStream();

        ByteBuf buf = Unpooled.buffer();
        writeVariableLengthInteger(buf, 0x41L);
        writeVariableLengthInteger(buf, 9999L); // unknown session
        stream.writeInbound(buf);

        Http3TestUtils.verifyClose(Http3ErrorCode.H3_ID_ERROR, quicChannel);
        assertFalse(stream.finish());
    }

    @Test
    void testWtBidirectionalStreamWaitsForSessionId() throws Exception {
        setup();
        registerSession(0L, noopListener());
        EmbeddedQuicStreamChannel stream = newBidiStream();

        // Write only the 2-byte type varint without the session ID.
        ByteBuf typeOnly = Unpooled.buffer();
        writeVariableLengthInteger(typeOnly, 0x41L);
        assertFalse(stream.writeInbound(typeOnly));

        // Detector still in pipeline (waiting)
        assertNotNull(stream.pipeline().get(WebTransportBidirectionalStreamDetector.class));
        assertFalse(stream.finish());
    }

    @ParameterizedTest(name = "{index}: sessionId={0}")
    @ValueSource(longs = {0L, 4L, 8L})
    void testWtBidirectionalSessionIdVarint1Byte(long sessionId) throws Exception {
        doWtStreamVarintTest(sessionId);
    }

    @ParameterizedTest(name = "{index}: sessionId={0}")
    @ValueSource(longs = {64L, 100L, 16383L})
    void testWtBidirectionalSessionIdVarint2Byte(long sessionId) throws Exception {
        doWtStreamVarintTest(sessionId);
    }

    @ParameterizedTest(name = "{index}: sessionId={0}")
    @ValueSource(longs = {16384L, 100000L, 1073741820L})
    void testWtBidirectionalSessionIdVarint4Byte(long sessionId) throws Exception {
        doWtStreamVarintTest(sessionId);
    }

    private void doWtStreamVarintTest(long sessionId) throws Exception {
        AtomicReference<QuicStreamChannel> notified = new AtomicReference<>();
        WebTransportSessionListener listener = new WebTransportSessionListener() {
            @Override
            public void onBidirectionalStream(WebTransportSession s, QuicStreamChannel c) {
                notified.set(c);
            }
            @Override
            public void onUnidirectionalStream(WebTransportSession s, QuicStreamChannel c) { }
            @Override
            public void onDatagram(WebTransportSession s, ByteBuf p) { }
            @Override
            public void onSessionClosed(WebTransportSession s, int code, String reason) { }
        };

        setup();
        WebTransportSession session = registerSession(sessionId, listener);

        EmbeddedQuicStreamChannel stream = newBidiStream();

        ByteBuf buf = Unpooled.buffer();
        writeVariableLengthInteger(buf, 0x41L);
        writeVariableLengthInteger(buf, sessionId);
        stream.writeInbound(buf);

        assertNotNull(notified.get());
        assertNull(stream.pipeline().get(WebTransportBidirectionalStreamDetector.class));
        assertFalse(stream.finish());
        assertFalse(((EmbeddedQuicStreamChannel) session.sessionStream()).finish());
    }
}
