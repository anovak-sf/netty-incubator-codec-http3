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
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.handler.codec.quic.QuicStreamType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

class WebTransportHandshakeTest {

    @Test
    void testClientServerHandshakeAndCapsulesThroughHttp3Codec() {
        EmbeddedQuicChannel clientParent = new EmbeddedQuicChannel(false);
        EmbeddedQuicChannel serverParent = new EmbeddedQuicChannel(true);
        List<Integer> clientDatagrams = new ArrayList<>();
        List<Integer> serverDatagrams = new ArrayList<>();
        ChannelPromise established = clientParent.newPromise();
        Http3Headers request = new DefaultHttp3Headers().method("CONNECT").scheme("https")
                .authority("example.com").path("/wt")
                .add(Http3Headers.PseudoHeaderName.PROTOCOL.value(), "webtransport");
        EmbeddedQuicStreamChannel client = stream(clientParent, false,
                new WebTransportClientSessionHandler(request, listener(clientDatagrams), established));
        EmbeddedQuicStreamChannel server = stream(serverParent, true,
                new WebTransportServerHandler(headers -> listener(serverDatagrams)));
        try {
            server.writeInbound(drainBytes(client));
            // A capsule may follow the response HEADERS in the same QUIC read.
            server.writeOutbound(new WebTransportDatagramCapsule(Unpooled.buffer().writeByte(42)));
            client.writeInbound(drainBytes(server));
            assertTrue(established.isSuccess());
            assertEquals(java.util.Collections.singletonList(42), clientDatagrams);
            assertNotNull(client.pipeline().get(Http3FrameCodec.class));
            assertNotNull(server.pipeline().get(Http3FrameCodec.class));
            assertNotNull(client.pipeline().get(Http3RequestStreamDecodeStateValidator.class));
            assertNotNull(server.pipeline().get(Http3RequestStreamDecodeStateValidator.class));

            client.writeOutbound(new WebTransportDatagramCapsule(Unpooled.buffer().writeByte(43)));
            ByteBuf wire = drainBytes(client);
            // HTTP/3 DATA frame, length 3, then DATAGRAM capsule, length 1, payload 43.
            assertEquals(0, wire.getUnsignedByte(wire.readerIndex()));
            assertEquals(3, wire.getUnsignedByte(wire.readerIndex() + 1));
            while (wire.isReadable()) {
                server.writeInbound(wire.readRetainedSlice(1));
            }
            wire.release();
            assertEquals(java.util.Collections.singletonList(43), serverDatagrams);
        } finally {
            assertFalse(client.finishAndReleaseAll());
            assertFalse(server.finishAndReleaseAll());
            clientParent.finishAndReleaseAll();
            serverParent.finishAndReleaseAll();
        }
    }

    @Test
    void testCoalescedResponseDrainAndCloseCapsulesThroughHttp3Codec() {
        EmbeddedQuicChannel clientParent = new EmbeddedQuicChannel(false);
        EmbeddedQuicChannel serverParent = new EmbeddedQuicChannel(true);
        WebTransportSessionListener clientListener = mock(WebTransportSessionListener.class);
        ChannelPromise established = clientParent.newPromise();
        Http3Headers request = new DefaultHttp3Headers().method("CONNECT").scheme("https")
                .authority("example.com").path("/wt")
                .add(Http3Headers.PseudoHeaderName.PROTOCOL.value(), "webtransport");
        EmbeddedQuicStreamChannel client = stream(clientParent, false,
                new WebTransportClientSessionHandler(request, clientListener, established));
        EmbeddedQuicStreamChannel server = stream(serverParent, true,
                new WebTransportServerHandler(headers -> listener(new ArrayList<>())));
        try {
            server.writeInbound(drainBytes(client));
            WebTransportSession serverSession = WebTransportSessionRegistry.get(serverParent).find(0);
            assertNotNull(serverSession);
            server.writeOutbound(WebTransportDrainSessionCapsule.INSTANCE);
            assertTrue(serverSession.closeSession(7, "finished").isSuccess());
            assertTrue(server.isOutputShutdown());

            // One QUIC read contains response HEADERS and DATA frames holding both capsules.
            // FIN follows the last DATA frame, independently of its HTTP/3 frame boundaries.
            client.writeInboundWithFin(drainBytes(server));
            assertTrue(established.isSuccess());
            assertNotNull(client.pipeline().get(Http3FrameCodec.class));
            ArgumentCaptor<WebTransportSession> session = ArgumentCaptor.forClass(WebTransportSession.class);
            verify(clientListener).onSessionDraining(session.capture());
            verify(clientListener).onSessionClosed(same(session.getValue()), eq(7), eq("finished"));
            verifyNoMoreInteractions(clientListener);
            assertTrue(session.getValue().isClosed());
            assertEquals(0, client.outputShutdownError());
            assertTrue(clientParent.isActive());
        } finally {
            assertFalse(client.finishAndReleaseAll());
            assertFalse(server.finishAndReleaseAll());
            clientParent.finishAndReleaseAll();
            serverParent.finishAndReleaseAll();
        }
    }

    @Test
    void testCapsuleMaySpanDataFrames() {
        EmbeddedQuicChannel parent = new EmbeddedQuicChannel(true);
        List<Integer> datagrams = new ArrayList<>();
        EmbeddedQuicStreamChannel server = new EmbeddedQuicStreamChannel(parent, false,
                QuicStreamType.BIDIRECTIONAL, 0, new WebTransportServerHandler(headers -> listener(datagrams)));
        try {
            server.writeInbound(new DefaultHttp3HeadersFrame(new DefaultHttp3Headers().method("CONNECT")
                    .scheme("https").authority("example.com").path("/wt")
                    .add(Http3Headers.PseudoHeaderName.PROTOCOL.value(), "webtransport")));
            server.readOutbound();
            server.writeInbound(new DefaultHttp3DataFrame(Unpooled.buffer().writeByte(0).writeByte(1)));
            assertTrue(datagrams.isEmpty());
            server.writeInbound(new DefaultHttp3DataFrame(Unpooled.buffer().writeByte(44)));
            assertEquals(java.util.Collections.singletonList(44), datagrams);
        } finally {
            server.finishAndReleaseAll();
            parent.finishAndReleaseAll();
        }
    }

    private static EmbeddedQuicStreamChannel stream(EmbeddedQuicChannel parent, boolean server,
                                                    ChannelHandler handler) {
        QpackAttributes attributes = new QpackAttributes(parent, true);
        Http3.setQpackAttributes(parent, attributes);
        return new EmbeddedQuicStreamChannel(parent, !server, QuicStreamType.BIDIRECTIONAL, 0,
                new ChannelInitializer<QuicStreamChannel>() {
                    @Override
                    protected void initChannel(QuicStreamChannel ch) {
                        Http3RequestStreamEncodeStateValidator encode = new Http3RequestStreamEncodeStateValidator();
                        Http3RequestStreamDecodeStateValidator decode = new Http3RequestStreamDecodeStateValidator();
                        QpackDecoder decoder = new QpackDecoder(0, 0);
                        ch.pipeline().addLast(new Http3FrameCodec(Http3RequestStreamFrameTypeValidator.INSTANCE,
                                decoder, 8192, Http3CodecUtils.DEFAULT_MAX_UNKNOWN_FRAME_PAYLOAD_LENGTH,
                                new QpackEncoder(null), encode, decode, (id, value) -> false));
                        ch.pipeline().addLast(encode, decode);
                        ch.pipeline().addLast(server
                                ? Http3RequestStreamValidationHandler.newServerValidator(attributes, decoder,
                                        encode, decode)
                                : Http3RequestStreamValidationHandler.newClientValidator(() -> false, attributes,
                                        decoder, encode, decode));
                        ch.pipeline().addLast(handler);
                    }
                });
    }

    private static ByteBuf drainBytes(EmbeddedQuicStreamChannel channel) {
        ByteBuf result = Unpooled.buffer();
        ByteBuf bytes;
        while ((bytes = channel.readOutbound()) != null) {
            result.writeBytes(bytes);
            bytes.release();
        }
        return result;
    }

    private static WebTransportSessionListener listener(List<Integer> datagrams) {
        return new WebTransportSessionListener() {
            @Override
            public void onBidirectionalStream(WebTransportSession session, QuicStreamChannel stream) { }
            @Override
            public void onUnidirectionalStream(WebTransportSession session, QuicStreamChannel stream) { }
            @Override
            public void onDatagram(WebTransportSession session, ByteBuf payload) {
                datagrams.add((int) payload.getUnsignedByte(payload.readerIndex()));
            }
            @Override
            public void onSessionClosed(WebTransportSession session, int code, String reason) { }
        };
    }
}
