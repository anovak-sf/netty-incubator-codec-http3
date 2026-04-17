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
 * Per draft-ietf-webtrans-http3 §4.1 the prefix is two QUIC variable-length integers:
 * {@code [streamType(varint)][sessionId(varint)]}. The stream type is {@code 0x41} for
 * bidirectional streams and {@code 0x54} for unidirectional streams. Both values exceed the 1-byte
 * varint range (0..63) and are therefore encoded as 2-byte varints on the wire.
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
     * @param streamTypeByte the stream type varint value to write ({@code 0x41} for bidirectional,
     *                       {@code 0x54} for unidirectional WT streams)
     * @param sessionId      the WebTransport session ID to encode as a varint after the type varint
     */
    WebTransportStreamOutboundPrefixHandler(int streamTypeByte, long sessionId) {
        this.streamTypeByte = streamTypeByte;
        this.sessionId = sessionId;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        // The WebTransport stream signal is a QUIC variable-length integer
        // (draft-ietf-webtrans-http3 §4.1). Values 0x41 (bidi) and 0x54 (uni) both exceed the 1-byte
        // varint range (0..63) and must be encoded as 2-byte varints on the wire.
        int typeVarintLen = numBytesForVariableLengthInteger(streamTypeByte);
        int sidVarintLen = numBytesForVariableLengthInteger(sessionId);
        ByteBuf prefix = ctx.alloc().buffer(typeVarintLen + sidVarintLen);
        writeVariableLengthInteger(prefix, streamTypeByte);
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
