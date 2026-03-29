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
import io.netty.channel.ChannelInboundHandlerAdapter;

import static io.netty.incubator.codec.http3.Http3CodecUtils.numBytesForVariableLengthInteger;
import static io.netty.incubator.codec.http3.Http3CodecUtils.writeVariableLengthInteger;

/**
 * One-shot handler that writes the WebTransport stream prefix on channel activation.
 * <p>
 * For bidirectional WT streams the prefix is {@code [0x41][sessionId(varint)]}.
 * For unidirectional WT streams the prefix is the stream type {@code 0x54} followed by
 * {@code [sessionId(varint)]} (the stream type itself is written by {@link WebTransportSession}
 * when creating the QUIC stream; this handler writes the session ID).
 * <p>
 * After writing the prefix the handler removes itself from the pipeline, leaving the user's
 * handler to process raw bytes.
 */
final class WebTransportStreamOutboundPrefixHandler extends ChannelInboundHandlerAdapter {

    private final int streamTypeByte;
    private final long sessionId;

    /**
     * Creates a new prefix handler.
     *
     * @param streamTypeByte the first byte to write ({@code 0x41} for bidirectional,
     *                       {@code 0x54} for unidirectional WT streams)
     * @param sessionId      the WebTransport session ID to encode as a varint after the type byte
     */
    WebTransportStreamOutboundPrefixHandler(int streamTypeByte, long sessionId) {
        this.streamTypeByte = streamTypeByte;
        this.sessionId = sessionId;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        // Allocate a buffer for the stream prefix: 1 byte type + up to 8 bytes session ID varint.
        ByteBuf prefix = ctx.alloc().buffer(1 + numBytesForVariableLengthInteger(sessionId));
        prefix.writeByte(streamTypeByte);
        writeVariableLengthInteger(prefix, sessionId);
        ctx.writeAndFlush(prefix);

        // Remove self — subsequent writes go directly to the user's handler.
        ctx.pipeline().remove(this);
        ctx.fireChannelActive();
    }

    @Override
    public boolean isSharable() {
        return false;
    }
}
