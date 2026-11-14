/*
 * Copyright 2020 The Netty Project
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
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.handler.codec.quic.QuicStreamType;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class Http3ClientConnectionHandlerTest extends AbtractHttp3ConnectionHandlerTest {

    public Http3ClientConnectionHandlerTest() {
        super(false);
    }

    @Override
    protected Http3ConnectionHandler newConnectionHandler() {
        return new Http3ClientConnectionHandler();
    }

    @Override
    protected void assertBidirectionalStreamHandled(EmbeddedQuicChannel channel, QuicStreamChannel streamChannel) {
        Http3TestUtils.verifyClose(Http3ErrorCode.H3_STREAM_CREATION_ERROR, channel);
    }

    @ParameterizedTest
    @EnumSource(QuicStreamType.class)
    void testIncomingWebTransportStreamsUseClientConnectionHandler(QuicStreamType type) {
        Http3SettingsFrame settings = new DefaultHttp3SettingsFrame(new Http3Settings()
                .enableWebTransport(true).enableH3Datagram(true).enableConnectProtocol(true));
        Http3ClientConnectionHandler connection = new Http3ClientConnectionHandler(null, null, null, settings, true);
        EmbeddedQuicChannel parent = new EmbeddedQuicChannel(false, connection);
        connection.peerSettingsReceived(settings);
        EmbeddedQuicStreamChannel connect = new EmbeddedQuicStreamChannel(parent, true,
                QuicStreamType.BIDIRECTIONAL, 0);
        AtomicInteger payload = new AtomicInteger(-1);
        WebTransportSessionListener listener = new WebTransportSessionListener() {
            private void initStream(QuicStreamChannel stream) {
                assertEquals(type, stream.type());
                stream.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelRead(ChannelHandlerContext ctx, Object msg) {
                        payload.set(((ByteBuf) msg).getUnsignedByte(((ByteBuf) msg).readerIndex()));
                        ReferenceCountUtil.release(msg);
                    }
                });
            }
            @Override
            public void onBidirectionalStream(WebTransportSession session, QuicStreamChannel stream) {
                initStream(stream);
            }
            @Override
            public void onUnidirectionalStream(WebTransportSession session, QuicStreamChannel stream) {
                initStream(stream);
            }
            @Override
            public void onDatagram(WebTransportSession session, ByteBuf bytes) { }
            @Override
            public void onSessionClosed(WebTransportSession session, int code, String reason) { }
        };
        WebTransportSessionRegistry.getOrCreate(parent).register(0,
                new WebTransportSession(parent, connect, 0, listener));
        EmbeddedQuicStreamChannel stream = new EmbeddedQuicStreamChannel(parent, false, type,
                type == QuicStreamType.BIDIRECTIONAL ? 1 : 3);
        try {
            parent.writeInbound(stream);
            assertSame(stream, parent.readInbound());
            ByteBuf data = Unpooled.buffer();
            Http3CodecUtils.writeVariableLengthInteger(data,
                    type == QuicStreamType.BIDIRECTIONAL ? 0x41 : 0x54);
            Http3CodecUtils.writeVariableLengthInteger(data, 0);
            data.writeByte(48);
            stream.writeInbound(data);
            assertEquals(48, payload.get());
            assertTrue(parent.isActive());
        } finally {
            stream.finishAndReleaseAll();
            connect.finishAndReleaseAll();
            parent.localControlStream().finishAndReleaseAll();
            parent.finishAndReleaseAll();
        }
    }

    @Test
    public void customSensitivityDetectorIsForwardedToQpackEncoder() {
        Http3ClientConnectionHandler handler = new Http3ClientConnectionHandler(
                null, null, null, null,
                true, null, QpackSensitivityDetector.ALWAYS_SENSITIVE,
                Http3CodecUtils.DEFAULT_MAX_UNKNOWN_FRAME_PAYLOAD_LENGTH);

        Http3Headers headers = new DefaultHttp3Headers(false);
        headers.add("x-custom", "value");
        ByteBuf out = Unpooled.buffer();
        try {
            handler.qpackEncoder.encodeHeaders(new QpackAttributes(null, true), out,
                    UnpooledByteBufAllocator.DEFAULT, 1L, headers);
            byte first = out.getByte(2);
            assertEquals(1, (first & 0b0001_0000) >>> 4,
                    "N bit must be set when ALWAYS_SENSITIVE is configured");
        } finally {
            out.release();
        }
    }
}
