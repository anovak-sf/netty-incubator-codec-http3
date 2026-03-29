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
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.incubator.codec.quic.QuicStreamChannel;
import io.netty.incubator.codec.quic.QuicStreamType;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongFunction;

import static io.netty.incubator.codec.http3.Http3CodecUtils.HTTP3_CONTROL_STREAM_TYPE;
import static io.netty.incubator.codec.http3.Http3CodecUtils.HTTP3_QPACK_DECODER_STREAM_TYPE;
import static io.netty.incubator.codec.http3.Http3CodecUtils.HTTP3_QPACK_ENCODER_STREAM_TYPE;
import static io.netty.incubator.codec.http3.Http3CodecUtils.writeVariableLengthInteger;
import static io.netty.incubator.codec.http3.WebTransportCodecUtils.WT_STREAM_TYPE_UNIDIRECTIONAL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

public class Http3UnidirectionalStreamWebTransportTest {

    private EmbeddedQuicChannel parent;
    private Http3ControlStreamOutboundHandler remoteControlStreamHandler;
    private Http3ControlStreamInboundHandler localControlStreamHandler;
    private QpackEncoder qpackEncoder;
    private QpackDecoder qpackDecoder;

    private void setup(boolean server) {
        parent = new EmbeddedQuicChannel(server);
        qpackEncoder = new QpackEncoder();
        qpackDecoder = new QpackDecoder(0, 0);
        remoteControlStreamHandler = new Http3ControlStreamOutboundHandler(
                server, new DefaultHttp3SettingsFrame(), new ChannelHandlerAdapter() { });
        localControlStreamHandler = new Http3ControlStreamInboundHandler(
                server, null, qpackEncoder, remoteControlStreamHandler);
    }

    @AfterEach
    void tearDown() {
        assertFalse(parent.finish());
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

    private WebTransportSession registerSession(long sessionId) {
        WebTransportSessionListener listener = noopListener();
        EmbeddedQuicStreamChannel sessionStream = new EmbeddedQuicStreamChannel(
                parent, false, QuicStreamType.BIDIRECTIONAL, sessionId,
                new ChannelHandlerAdapter() { });
        WebTransportSession session = new WebTransportSession(parent, sessionStream, sessionId, listener);
        WebTransportSessionRegistry.getOrCreate(parent).register(sessionId, session);
        return session;
    }

    private EmbeddedQuicStreamChannel newUniStreamChannel(boolean server,
            @Nullable LongFunction<io.netty.channel.ChannelHandler> unknownStreamHandlerFactory) throws Exception {
        Http3UnidirectionalStreamInboundHandler handler = server
                ? new Http3UnidirectionalStreamInboundWebTransportServerHandler(
                        (encState, decState, __, ___) -> new ChannelHandlerAdapter() { },
                        (id, v) -> false,
                        localControlStreamHandler, remoteControlStreamHandler,
                        unknownStreamHandlerFactory,
                        () -> new QpackEncoderHandler((long) Integer.MAX_VALUE, qpackDecoder),
                        () -> new QpackDecoderHandler(qpackEncoder))
                : new Http3UnidirectionalStreamInboundWebTransportClientHandler(
                        (encState, decState, __, ___) -> new ChannelHandlerAdapter() { },
                        (id, v) -> false,
                        localControlStreamHandler, remoteControlStreamHandler,
                        unknownStreamHandlerFactory,
                        pushId -> new Http3PushStreamClientInitializer() {
                            @Override
                            protected void initPushStream(QuicStreamChannel ch) {
                                ch.pipeline().addLast(new ChannelHandlerAdapter() { });
                            }
                        },
                        () -> new QpackEncoderHandler((long) Integer.MAX_VALUE, qpackDecoder),
                        () -> new QpackDecoderHandler(qpackEncoder));
        return (EmbeddedQuicStreamChannel) parent.createStream(QuicStreamType.BIDIRECTIONAL, handler).get();
    }

    private static ByteBuf wtUniStreamBuf(long sessionId) {
        ByteBuf buf = Unpooled.buffer();
        writeVariableLengthInteger(buf, WT_STREAM_TYPE_UNIDIRECTIONAL);
        writeVariableLengthInteger(buf, sessionId);
        return buf;
    }

    @ParameterizedTest(name = "{index}: server={0}")
    @ValueSource(booleans = {true, false})
    void testWtUnidirectionalStreamRoutedToListener(boolean server) throws Exception {
        setup(server);
        AtomicReference<QuicStreamChannel> notified = new AtomicReference<>();
        WebTransportSessionListener listener = new WebTransportSessionListener() {
            @Override
            public void onBidirectionalStream(WebTransportSession s, QuicStreamChannel c) { }
            @Override
            public void onUnidirectionalStream(WebTransportSession s, QuicStreamChannel c) {
                notified.set(c);
            }
            @Override
            public void onDatagram(WebTransportSession s, ByteBuf p) { }
            @Override
            public void onSessionClosed(WebTransportSession s, int code, String reason) { }
        };
        long sessionId = 0L;
        EmbeddedQuicStreamChannel sessionStream = new EmbeddedQuicStreamChannel(
                parent, false, QuicStreamType.BIDIRECTIONAL, sessionId,
                new ChannelHandlerAdapter() { });
        WebTransportSession session = new WebTransportSession(parent, sessionStream, sessionId, listener);
        WebTransportSessionRegistry.getOrCreate(parent).register(sessionId, session);

        EmbeddedQuicStreamChannel ch = newUniStreamChannel(server, null);
        assertFalse(ch.writeInbound(wtUniStreamBuf(sessionId)));

        assertNotNull(notified.get());
        assertNull(ch.pipeline().get(Http3UnidirectionalStreamInboundHandler.class));
        assertFalse(ch.finish());
        assertFalse(sessionStream.finish());
    }

    @ParameterizedTest(name = "{index}: server={0}")
    @ValueSource(booleans = {true, false})
    void testWtUnidirectionalStreamUnknownSessionId(boolean server) throws Exception {
        setup(server);
        EmbeddedQuicStreamChannel ch = newUniStreamChannel(server, null);
        assertFalse(ch.writeInbound(wtUniStreamBuf(9999L)));

        Http3TestUtils.verifyClose(Http3ErrorCode.H3_ID_ERROR, parent);
        assertFalse(ch.finish());
    }

    @ParameterizedTest(name = "{index}: server={0}")
    @ValueSource(booleans = {true, false})
    void testWtUnidirectionalStreamWaitsForSessionIdVarint(boolean server) throws Exception {
        setup(server);
        registerSession(0L);
        EmbeddedQuicStreamChannel ch = newUniStreamChannel(server, null);

        // Write stream type only (no session ID varint yet)
        ByteBuf typeOnly = Unpooled.buffer();
        writeVariableLengthInteger(typeOnly, WT_STREAM_TYPE_UNIDIRECTIONAL);
        assertFalse(ch.writeInbound(typeOnly));

        // Handler still present (waiting)
        assertNotNull(ch.pipeline().get(Http3UnidirectionalStreamInboundHandler.class));
        assertFalse(ch.finish());
    }

    @ParameterizedTest(name = "{index}: sessionId={0}")
    @ValueSource(longs = {0L, 4L, 63L})
    void testWtUnidirectionalStreamSessionIdVarint1Byte(long sessionId) throws Exception {
        doVarintSizeTest(sessionId);
    }

    @ParameterizedTest(name = "{index}: sessionId={0}")
    @ValueSource(longs = {64L, 1000L, 16383L})
    void testWtUnidirectionalStreamSessionIdVarint2Byte(long sessionId) throws Exception {
        doVarintSizeTest(sessionId);
    }

    @ParameterizedTest(name = "{index}: sessionId={0}")
    @ValueSource(longs = {16384L, 100000L, 1073741820L})
    void testWtUnidirectionalStreamSessionIdVarint4Byte(long sessionId) throws Exception {
        doVarintSizeTest(sessionId);
    }

    private void doVarintSizeTest(long sessionId) throws Exception {
        setup(true);
        AtomicReference<QuicStreamChannel> notified = new AtomicReference<>();
        WebTransportSessionListener listener = new WebTransportSessionListener() {
            @Override
            public void onBidirectionalStream(WebTransportSession s, QuicStreamChannel c) { }
            @Override
            public void onUnidirectionalStream(WebTransportSession s, QuicStreamChannel c) {
                notified.set(c);
            }
            @Override
            public void onDatagram(WebTransportSession s, ByteBuf p) { }
            @Override
            public void onSessionClosed(WebTransportSession s, int code, String reason) { }
        };
        EmbeddedQuicStreamChannel sessionStream = new EmbeddedQuicStreamChannel(
                parent, false, QuicStreamType.BIDIRECTIONAL, sessionId,
                new ChannelHandlerAdapter() { });
        WebTransportSession session = new WebTransportSession(parent, sessionStream, sessionId, listener);
        WebTransportSessionRegistry.getOrCreate(parent).register(sessionId, session);

        EmbeddedQuicStreamChannel ch = newUniStreamChannel(true, null);
        ch.writeInbound(wtUniStreamBuf(sessionId));

        assertNotNull(notified.get());
        assertFalse(ch.finish());
        assertFalse(sessionStream.finish());
    }

    @ParameterizedTest(name = "{index}: server={0}")
    @ValueSource(booleans = {true, false})
    void testControlStreamStillWorksWithWtHandler(boolean server) throws Exception {
        setup(server);
        EmbeddedQuicStreamChannel ch = newUniStreamChannel(server, null);

        ByteBuf buf = Unpooled.buffer(8);
        writeVariableLengthInteger(buf, HTTP3_CONTROL_STREAM_TYPE);
        assertFalse(ch.writeInbound(buf));

        assertNull(ch.pipeline().get(Http3UnidirectionalStreamInboundHandler.class));
        assertNotNull(ch.pipeline().get(Http3ControlStreamInboundHandler.class));
        assertFalse(ch.finish());
    }

    @ParameterizedTest(name = "{index}: server={0}")
    @ValueSource(booleans = {true, false})
    void testQpackEncoderStreamStillWorks(boolean server) throws Exception {
        setup(server);
        EmbeddedQuicStreamChannel ch = newUniStreamChannel(server, null);

        ByteBuf buf = Unpooled.buffer(8);
        writeVariableLengthInteger(buf, HTTP3_QPACK_ENCODER_STREAM_TYPE);
        assertFalse(ch.writeInbound(buf));

        assertNull(ch.pipeline().get(Http3UnidirectionalStreamInboundHandler.class));
        assertNotNull(ch.pipeline().get(QpackEncoderHandler.class));
        assertFalse(ch.finish());
    }

    @ParameterizedTest(name = "{index}: server={0}")
    @ValueSource(booleans = {true, false})
    void testQpackDecoderStreamStillWorks(boolean server) throws Exception {
        setup(server);
        EmbeddedQuicStreamChannel ch = newUniStreamChannel(server, null);

        ByteBuf buf = Unpooled.buffer(8);
        writeVariableLengthInteger(buf, HTTP3_QPACK_DECODER_STREAM_TYPE);
        assertFalse(ch.writeInbound(buf));

        assertNull(ch.pipeline().get(Http3UnidirectionalStreamInboundHandler.class));
        assertNotNull(ch.pipeline().get(QpackDecoderHandler.class));
        assertFalse(ch.finish());
    }

    @ParameterizedTest(name = "{index}: server={0}")
    @ValueSource(booleans = {true, false})
    void testUnknownStreamTypeUsesCustomHandler(boolean server) throws Exception {
        setup(server);
        long unknownType = 0x06L;
        AtomicReference<Long> receivedType = new AtomicReference<>();
        LongFunction<io.netty.channel.ChannelHandler> factory = type -> {
            receivedType.set(type);
            return new ChannelInboundHandlerAdapter();
        };

        EmbeddedQuicStreamChannel ch = newUniStreamChannel(server, factory);

        ByteBuf buf = Unpooled.buffer(8);
        writeVariableLengthInteger(buf, unknownType);
        assertFalse(ch.writeInbound(buf));
        assertEquals(0, buf.refCnt());

        assertNull(ch.pipeline().get(Http3UnidirectionalStreamInboundHandler.class));
        assertEquals(unknownType, receivedType.get());
        assertFalse(ch.finish());
    }
}
