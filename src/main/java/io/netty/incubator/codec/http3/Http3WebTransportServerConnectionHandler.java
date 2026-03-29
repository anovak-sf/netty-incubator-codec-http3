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

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.incubator.codec.quic.QuicChannel;
import io.netty.incubator.codec.quic.QuicStreamChannel;
import io.netty.util.internal.ObjectUtil;
import org.jetbrains.annotations.Nullable;

import java.util.function.LongFunction;

/**
 * HTTP/3 connection handler for servers that support WebTransport.
 * <p>
 * Use this handler instead of {@link Http3ServerConnectionHandler} when your server needs to
 * accept WebTransport sessions. It installs a {@link WebTransportBidirectionalStreamDetector}
 * for each incoming bidirectional stream so that WT streams (prefixed with {@code 0x41}) are
 * routed to the registered {@link WebTransportSessionListener} without going through the
 * HTTP/3 frame codec.
 * <p>
 * <strong>Required settings:</strong> Pass a settings frame with
 * {@code ENABLE_CONNECT_PROTOCOL}, {@code H3_DATAGRAM}, and {@code ENABLE_WEBTRANSPORT} all set
 * to {@code 1} as the {@code localSettings} argument (see example below):
 * <pre>{@code
 * Http3SettingsFrame settings = new DefaultHttp3SettingsFrame(
 *     Http3Settings.defaultSettings()
 *         .enableConnectProtocol(true)
 *         .enableH3Datagram(true)
 *         .enableWebTransport(true));
 *
 * channel.pipeline().addLast(new Http3WebTransportServerConnectionHandler(
 *     acceptor,          // decides whether to accept each WT session
 *     null,              // no fallback for non-WT requests (returns HTTP 405)
 *     null,              // no custom control-stream handler
 *     null,              // no unknown-stream handler
 *     settings,
 *     true));
 * }</pre>
 *
 * @see Http3ServerConnectionHandler
 * @see WebTransportServerHandler
 * @see WebTransportSessionAcceptor
 */
public final class Http3WebTransportServerConnectionHandler extends Http3ConnectionHandler {

    private final ChannelHandler requestStreamHandler;

    /**
     * Creates a new instance with only a {@link WebTransportSessionAcceptor} and default settings.
     * Non-WebTransport requests receive HTTP 405.
     *
     * @param acceptor the session acceptor (never {@code null})
     */
    public Http3WebTransportServerConnectionHandler(WebTransportSessionAcceptor acceptor) {
        this(acceptor, null, null, null, null, true);
    }

    /**
     * Creates a new instance.
     *
     * @param acceptor                          decides whether to accept each incoming WebTransport session
     * @param fallbackRequestStreamHandler      {@link ChannelHandler} for non-WebTransport HTTP/3 requests,
     *                                          or {@code null} to return HTTP 405 for such requests
     * @param inboundControlStreamHandler       optional handler notified about control-stream frames,
     *                                          or {@code null}
     * @param unknownInboundStreamHandlerFactory optional factory for unknown stream types, or {@code null}
     * @param localSettings                     the local SETTINGS frame to send to the peer, or {@code null}
     *                                          to use the default settings. <b>Must</b> include
     *                                          {@code ENABLE_CONNECT_PROTOCOL=1}, {@code H3_DATAGRAM=1},
     *                                          and {@code ENABLE_WEBTRANSPORT=1} for WebTransport to work.
     * @param disableQpackDynamicTable          {@code true} to disable the QPACK dynamic table
     */
    public Http3WebTransportServerConnectionHandler(
            WebTransportSessionAcceptor acceptor,
            @Nullable ChannelHandler fallbackRequestStreamHandler,
            @Nullable ChannelHandler inboundControlStreamHandler,
            @Nullable LongFunction<ChannelHandler> unknownInboundStreamHandlerFactory,
            @Nullable Http3SettingsFrame localSettings,
            boolean disableQpackDynamicTable) {
        super(true, inboundControlStreamHandler, unknownInboundStreamHandlerFactory,
                localSettings, disableQpackDynamicTable, null);
        ObjectUtil.checkNotNull(acceptor, "acceptor");
        this.requestStreamHandler = new WebTransportServerHandler(acceptor, fallbackRequestStreamHandler);
    }

    /**
     * Returns the request stream handler used for non-WT bidirectional streams.
     */
    ChannelHandler requestStreamHandler() {
        return requestStreamHandler;
    }

    /**
     * Adds the standard HTTP/3 pipeline components to the given pipeline for a request stream.
     * Called by the bidirectional stream detector when it determines the stream is a regular HTTP/3 request.
     */
    void initHttp3RequestPipeline(ChannelPipeline pipeline, QuicStreamChannel streamChannel) {
        Http3RequestStreamEncodeStateValidator encodeStateValidator = new Http3RequestStreamEncodeStateValidator();
        Http3RequestStreamDecodeStateValidator decodeStateValidator = new Http3RequestStreamDecodeStateValidator();
        pipeline.addLast(newCodec(encodeStateValidator, decodeStateValidator));
        pipeline.addLast(encodeStateValidator);
        pipeline.addLast(decodeStateValidator);
        pipeline.addLast(newRequestStreamValidationHandler(streamChannel, encodeStateValidator, decodeStateValidator));
        pipeline.addLast(requestStreamHandler);
    }

    @Override
    void initBidirectionalStream(ChannelHandlerContext ctx, QuicStreamChannel streamChannel) {
        // Install the detector first; it will set up the correct pipeline once it sees the first byte.
        streamChannel.pipeline().addLast(new WebTransportBidirectionalStreamDetector(this));
    }

    @Override
    void initUnidirectionalStream(ChannelHandlerContext ctx, QuicStreamChannel streamChannel) {
        final long maxTableCapacity = maxTableCapacity();
        streamChannel.pipeline().addLast(
                new Http3UnidirectionalStreamInboundWebTransportServerHandler(
                        codecFactory, nonStandardSettingsValidator,
                        localControlStreamHandler, remoteControlStreamHandler,
                        unknownInboundStreamHandlerFactory,
                        () -> new QpackEncoderHandler(maxTableCapacity, qpackDecoder),
                        () -> new QpackDecoderHandler(qpackEncoder)));
    }
}
