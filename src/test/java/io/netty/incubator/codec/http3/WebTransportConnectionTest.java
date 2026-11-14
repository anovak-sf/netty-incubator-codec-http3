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
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.handler.codec.quic.QuicStreamType;
import io.netty.handler.codec.quic.QuicTransportParameters;
import io.netty.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static io.netty.incubator.codec.http3.Http3CodecUtils.writeVariableLengthInteger;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WebTransportConnectionTest {
    private Http3ClientConnectionHandler handler;
    private EmbeddedQuicChannel connection;
    private EmbeddedQuicStreamChannel sessionStream;

    @BeforeEach
    void setUp() {
        handler = new Http3ClientConnectionHandler(null, null, null, settings(), true);
        connection = new EmbeddedQuicChannel(false, handler);
        QuicTransportParameters parameters = mock(QuicTransportParameters.class);
        when(parameters.maxDatagramFrameSize()).thenReturn(65535L);
        connection.peerTransportParameters(parameters);
        sessionStream = new EmbeddedQuicStreamChannel(connection, true, QuicStreamType.BIDIRECTIONAL, 256);
    }

    @AfterEach
    void tearDown() {
        sessionStream.finishAndReleaseAll();
        connection.localControlStream().finishAndReleaseAll();
        connection.finishAndReleaseAll();
    }

    private static Http3SettingsFrame settings() {
        return new DefaultHttp3SettingsFrame(new Http3Settings()
                .enableWebTransport(true).enableH3Datagram(true).enableConnectProtocol(true));
    }

    private static Http3Headers headers() {
        return new DefaultHttp3Headers().method("CONNECT").protocol("webtransport")
                .scheme("https").authority("localhost").path("/");
    }

    @Test
    void testSessionWaitsForSettingsAndFailsUnsupportedPeer() {
        Future<WebTransportSession> future = Http3.newWebTransportSession(
                connection, headers(), mock(WebTransportSessionListener.class));
        assertFalse(future.isDone());
        handler.peerSettingsReceived(new DefaultHttp3SettingsFrame());
        assertTrue(future.isDone());
        assertFalse(future.isSuccess());
        assertNotNull(future.cause());
    }

    @Test
    void testSettingsAloneDoNotEnableDatagrams() throws Exception {
        // Even a positive transport-parameter snapshot is not proof: the bundled native
        // library's FFI layout can expose an unrelated parameter as maxDatagramFrameSize.
        assertFalse(handler.webTransportSupported(settings()));
        connection.peerTransportParameters(mock(QuicTransportParameters.class));
        assertFalse(handler.webTransportSupported(settings()));
        connection.peerTransportParameters(null);
        assertFalse(handler.webTransportSupported(settings()));
        connection.pipeline().fireUserEventTriggered(Http3TestUtils.datagramExtensionEvent());
        assertTrue(handler.webTransportSupported(settings()));
    }

    @Test
    void testConnectionCloseFailsSessionWaitingForSettings() {
        Future<WebTransportSession> future = Http3.newWebTransportSession(
                connection, headers(), mock(WebTransportSessionListener.class));
        connection.close();
        assertTrue(future.isDone());
        assertFalse(future.isSuccess());
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void testCreateStreamFailureCompletesSessionFuture(boolean synchronous) throws Exception {
        connection.pipeline().fireUserEventTriggered(Http3TestUtils.datagramExtensionEvent());
        handler.peerSettingsReceived(settings());
        QuicChannel failedConnection = mock(QuicChannel.class);
        when(failedConnection.eventLoop()).thenReturn(connection.eventLoop());
        when(failedConnection.pipeline()).thenReturn(connection.pipeline());
        IllegalStateException failure = new IllegalStateException("stream limit reached");
        if (synchronous) {
            when(failedConnection.createStream(eq(QuicStreamType.BIDIRECTIONAL), any(ChannelHandler.class)))
                    .thenThrow(failure);
        } else {
            when(failedConnection.createStream(eq(QuicStreamType.BIDIRECTIONAL), any(ChannelHandler.class)))
                    .thenReturn(connection.eventLoop().newFailedFuture(failure));
        }
        Future<WebTransportSession> future = Http3.newWebTransportSession(
                failedConnection, headers(), mock(WebTransportSessionListener.class));
        assertSame(failure, future.cause());
    }

    @Test
    void testSettingsComeFromPeerControlStream() {
        EmbeddedQuicStreamChannel control = new EmbeddedQuicStreamChannel(
                connection, false, QuicStreamType.UNIDIRECTIONAL, 3);
        try {
            connection.writeInbound(control);
            assertSame(control, connection.readInbound());
            ByteBuf bytes = Unpooled.buffer();
            writeVariableLengthInteger(bytes, 0);
            writeVariableLengthInteger(bytes, 4);
            writeVariableLengthInteger(bytes, 4);
            writeVariableLengthInteger(bytes, 0x33);
            writeVariableLengthInteger(bytes, 1);
            writeVariableLengthInteger(bytes, 8);
            writeVariableLengthInteger(bytes, 1);
            control.writeInbound(bytes);
            assertTrue(handler.peerSettings().isSuccess());
            assertEquals(1L, handler.peerSettings().getNow().get(0x33));
            assertFalse(handler.webTransportSupported(handler.peerSettings().getNow()));
        } finally {
            control.finishAndReleaseAll();
        }
    }

    @Test
    void testNativeDatagramIsDemultiplexedAndReleased() {
        int[] received = new int[1];
        WebTransportSessionListener listener = new WebTransportSessionListener() {
            @Override
            public void onBidirectionalStream(WebTransportSession s, QuicStreamChannel c) { }
            @Override
            public void onUnidirectionalStream(WebTransportSession s, QuicStreamChannel c) { }
            @Override
            public void onSessionClosed(WebTransportSession s, int code, String reason) { }
            @Override
            public void onDatagram(WebTransportSession s, ByteBuf data) {
                assertEquals(256, s.sessionId());
                assertEquals(1, data.readableBytes());
                assertEquals(42, data.readUnsignedByte());
                received[0]++;
            }
        };
        WebTransportSession session = new WebTransportSession(connection, sessionStream, 256, listener);
        WebTransportSessionRegistry.getOrCreate(connection).register(256, session);
        ByteBuf datagram = Unpooled.buffer();
        writeVariableLengthInteger(datagram, 64);
        datagram.writeByte(42);
        assertFalse(connection.writeInbound(datagram));
        assertEquals(1, received[0]);
        assertEquals(0, datagram.refCnt());
        assertNull(connection.readInbound());
    }

    @Test
    void testMalformedNativeDatagramClosesWithDatagramError() {
        ByteBuf datagram = Unpooled.buffer().writeByte(0x40);
        connection.writeInbound(datagram);
        assertEquals(0, datagram.refCnt());
        assertTrue(connection.closeErrorCodes().contains((int) Http3ErrorCode.H3_DATAGRAM_ERROR.code()));
    }
}
