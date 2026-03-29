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
import io.netty.incubator.codec.http3.Http3FrameCodec.Http3FrameCodecFactory;
import io.netty.incubator.codec.quic.QuicChannel;
import io.netty.incubator.codec.quic.QuicStreamChannel;
import org.jetbrains.annotations.Nullable;

import java.util.function.LongFunction;
import java.util.function.Supplier;

/**
 * Client-side unidirectional stream handler with WebTransport support.
 * <p>
 * Extends the standard client unidirectional handler to support WebTransport unidirectional
 * streams (stream type {@code 0x54}). When such a stream is received from the server, this handler
 * looks up the associated {@link WebTransportSession} and notifies the session listener.
 */
final class Http3UnidirectionalStreamInboundWebTransportClientHandler
        extends Http3UnidirectionalStreamInboundHandler {

    private final LongFunction<ChannelHandler> pushStreamHandlerFactory;

    Http3UnidirectionalStreamInboundWebTransportClientHandler(
            Http3FrameCodecFactory codecFactory,
            Http3Settings.NonStandardHttp3SettingsValidator nonStandardSettingsValidator,
            Http3ControlStreamInboundHandler localControlStreamHandler,
            Http3ControlStreamOutboundHandler remoteControlStreamHandler,
            @Nullable LongFunction<ChannelHandler> unknownStreamHandlerFactory,
            @Nullable LongFunction<ChannelHandler> pushStreamHandlerFactory,
            Supplier<ChannelHandler> qpackEncoderHandlerFactory,
            Supplier<ChannelHandler> qpackDecoderHandlerFactory) {
        super(codecFactory, nonStandardSettingsValidator,
                localControlStreamHandler, remoteControlStreamHandler, unknownStreamHandlerFactory,
                qpackEncoderHandlerFactory, qpackDecoderHandlerFactory);
        this.pushStreamHandlerFactory = pushStreamHandlerFactory == null ? __ -> ReleaseHandler.INSTANCE :
                pushStreamHandlerFactory;
    }

    @Override
    void initPushStream(ChannelHandlerContext ctx, long pushId) {
        // Same logic as Http3UnidirectionalStreamInboundClientHandler.
        Long maxPushId = remoteControlStreamHandler.sentMaxPushId();
        if (maxPushId == null) {
            Http3CodecUtils.connectionError(ctx, Http3ErrorCode.H3_ID_ERROR,
                    "Received push stream before sending MAX_PUSH_ID frame.", false);
        } else if (maxPushId < pushId) {
            Http3CodecUtils.connectionError(ctx, Http3ErrorCode.H3_ID_ERROR,
                    "Received push stream with ID " + pushId + " greater than the max push ID " + maxPushId
                            + '.', false);
        } else {
            final ChannelHandler pushStreamHandler = pushStreamHandlerFactory.apply(pushId);
            ctx.pipeline().replace(this, null, pushStreamHandler);
        }
    }

    @Override
    void initWebTransportUnidirectionalStream(ChannelHandlerContext ctx, long sessionId) {
        QuicChannel quicChannel = (QuicChannel) ctx.channel().parent();
        WebTransportSessionRegistry registry = WebTransportSessionRegistry.get(quicChannel);
        WebTransportSession session = registry != null ? registry.find(sessionId) : null;

        if (session == null) {
            Http3CodecUtils.connectionError(ctx, Http3ErrorCode.H3_ID_ERROR,
                    "WebTransport unidirectional stream references unknown session ID: " + sessionId, false);
            return;
        }

        QuicStreamChannel streamChannel = (QuicStreamChannel) ctx.channel();
        ctx.pipeline().remove(this);
        session.listener().onUnidirectionalStream(session, streamChannel);
    }
}
