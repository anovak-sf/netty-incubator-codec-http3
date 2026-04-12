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
import io.netty.incubator.codec.quic.QuicChannel;
import io.netty.incubator.codec.quic.QuicStreamChannel;

import java.util.List;

import static io.netty.incubator.codec.http3.Http3CodecUtils.numBytesForVariableLengthInteger;
import static io.netty.incubator.codec.http3.Http3CodecUtils.readVariableLengthInteger;
import static io.netty.incubator.codec.http3.WebTransportCodecUtils.WT_STREAM_TYPE_BIDIRECTIONAL;

/**
 * Installed first in the pipeline of every incoming bidirectional stream when WebTransport is enabled.
 * <p>
 * Peeks at the first byte to determine the stream type:
 * <ul>
 *   <li>{@code 0x41} — WebTransport bidirectional stream: reads the session ID varint, finds the
 *       {@link WebTransportSession} in the {@link WebTransportSessionRegistry}, replaces itself with
 *       a pass-through, and notifies {@link WebTransportSessionListener#onBidirectionalStream}.</li>
 *   <li>Anything else — regular HTTP/3 request stream: replaces itself with the standard HTTP/3
 *       pipeline (codec + state validators + validation handler + request stream handler).</li>
 * </ul>
 */
final class WebTransportBidirectionalStreamDetector extends ByteToMessageDecoder {

    private final Http3WebTransportServerConnectionHandler connectionHandler;

    WebTransportBidirectionalStreamDetector(Http3WebTransportServerConnectionHandler connectionHandler) {
        this.connectionHandler = connectionHandler;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        if (!in.isReadable()) {
            return;
        }

        byte firstByte = in.getByte(in.readerIndex());

        if ((firstByte & 0xFF) == WT_STREAM_TYPE_BIDIRECTIONAL) {
            // Consume the 0x41 type byte.
            in.skipBytes(1);

            // Need at least one more byte for the session ID varint length.
            if (!in.isReadable()) {
                in.readerIndex(in.readerIndex() - 1);
                return;
            }
            int sidLen = numBytesForVariableLengthInteger(in.getByte(in.readerIndex()));
            if (in.readableBytes() < sidLen) {
                in.readerIndex(in.readerIndex() - 1);
                return;
            }
            long sessionId = readVariableLengthInteger(in, sidLen);

            // Look up the session.
            QuicChannel quicChannel = (QuicChannel) ctx.channel().parent();
            WebTransportSessionRegistry registry = WebTransportSessionRegistry.get(quicChannel);
            WebTransportSession session = registry != null ? registry.find(sessionId) : null;

            if (session == null) {
                Http3CodecUtils.connectionError(ctx, Http3ErrorCode.H3_ID_ERROR,
                        "WebTransport bidirectional stream references unknown session ID: " + sessionId, false);
                return;
            }

            // Notify the listener FIRST so it can install its inbound handlers before we
            // remove ourselves.  ByteToMessageDecoder.handlerRemoved() fires any remaining
            // buffered bytes (e.g. the first application frame that arrived in the same
            // packet as the WT stream header) via ctx.fireChannelRead().  At that point
            // ctx.next still points to the first handler added by the listener, so the
            // bytes are delivered correctly instead of being dropped on the tail.
            QuicStreamChannel streamChannel = (QuicStreamChannel) ctx.channel();
            session.listener().onBidirectionalStream(session, streamChannel);

            // Remove the detector after handlers are installed.
            ctx.pipeline().remove(this);
        } else {
            // Regular HTTP/3 request stream — install the full HTTP/3 pipeline.
            ChannelPipeline pipeline = ctx.pipeline();
            QuicStreamChannel streamChannel = (QuicStreamChannel) ctx.channel();
            connectionHandler.initHttp3RequestPipeline(pipeline, streamChannel);
            pipeline.remove(this);
        }
    }

    @Override
    public boolean isSharable() {
        return false;
    }
}
