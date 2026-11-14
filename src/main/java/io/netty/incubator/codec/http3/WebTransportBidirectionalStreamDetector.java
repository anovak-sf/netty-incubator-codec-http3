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
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.quic.QuicStreamChannel;

import java.util.List;
import java.util.function.BiConsumer;

import static io.netty.incubator.codec.http3.Http3CodecUtils.numBytesForVariableLengthInteger;
import static io.netty.incubator.codec.http3.Http3CodecUtils.readVariableLengthInteger;
import static io.netty.incubator.codec.http3.WebTransportCodecUtils.WT_STREAM_TYPE_BIDIRECTIONAL;

/** Detects a WebTransport stream prefix before handing the remaining bytes to the application. */
final class WebTransportBidirectionalStreamDetector extends ByteToMessageDecoder {

    private final BiConsumer<ChannelPipeline, QuicStreamChannel> http3Initializer;

    WebTransportBidirectionalStreamDetector(Http3WebTransportServerConnectionHandler connectionHandler) {
        this(connectionHandler::initHttp3RequestPipeline);
    }

    WebTransportBidirectionalStreamDetector(BiConsumer<ChannelPipeline, QuicStreamChannel> http3Initializer) {
        this.http3Initializer = http3Initializer;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (!in.isReadable()) {
            return;
        }
        int start = in.readerIndex();
        int typeLength = numBytesForVariableLengthInteger(in.getByte(start));
        if (in.readableBytes() < typeLength) {
            return;
        }
        long type = readVariableLengthInteger(in, typeLength);
        if (type != WT_STREAM_TYPE_BIDIRECTIONAL) {
            in.readerIndex(start);
            http3Initializer.accept(ctx.pipeline(), (QuicStreamChannel) ctx.channel());
            ctx.pipeline().remove(this);
            return;
        }
        if (!in.isReadable()) {
            in.readerIndex(start);
            return;
        }
        int sessionIdLength = numBytesForVariableLengthInteger(in.getByte(in.readerIndex()));
        if (in.readableBytes() < sessionIdLength) {
            in.readerIndex(start);
            return;
        }
        long sessionId = readVariableLengthInteger(in, sessionIdLength);
        WebTransportSessionRegistry.initStream(ctx, sessionId, true);
    }
}
