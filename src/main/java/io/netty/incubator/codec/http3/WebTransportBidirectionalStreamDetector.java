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
import io.netty.channel.ChannelPipeline;
import io.netty.incubator.codec.quic.QuicChannel;
import io.netty.incubator.codec.quic.QuicStreamChannel;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import static io.netty.incubator.codec.http3.Http3CodecUtils.numBytesForVariableLengthInteger;
import static io.netty.incubator.codec.http3.Http3CodecUtils.readVariableLengthInteger;
import static io.netty.incubator.codec.http3.WebTransportCodecUtils.WT_STREAM_TYPE_BIDIRECTIONAL;

/**
 * Installed first in the pipeline of every incoming bidirectional stream when WebTransport is enabled.
 * <p>
 * Peeks at the first byte to determine the stream type:
 * <ul>
 *   <li>{@code 0x41} — WebTransport bidirectional stream: reads the session ID varint, finds the
 *       {@link WebTransportSession} in the {@link WebTransportSessionRegistry}, installs the
 *       listener's handlers, removes itself, then fires any remaining bytes (application data that
 *       arrived in the same packet as the WT stream header) through the new pipeline.</li>
 *   <li>Anything else — regular HTTP/3 request stream: replaces itself with the standard HTTP/3
 *       pipeline (codec + state validators + validation handler + request stream handler).</li>
 * </ul>
 * <p>
 * This handler intentionally does <em>not</em> extend {@link io.netty.handler.codec.ByteToMessageDecoder}
 * so that remaining-bytes forwarding is explicit and not dependent on
 * {@code ByteToMessageDecoder.handlerRemoved} internals.
 */
final class WebTransportBidirectionalStreamDetector extends ChannelInboundHandlerAdapter {

    private static final InternalLogger logger =
            InternalLoggerFactory.getInstance(WebTransportBidirectionalStreamDetector.class);

    private final Http3WebTransportServerConnectionHandler connectionHandler;
    /** Accumulation buffer for bytes received before the stream type is determined. */
    private ByteBuf cumulation;

    WebTransportBidirectionalStreamDetector(Http3WebTransportServerConnectionHandler connectionHandler) {
        this.connectionHandler = connectionHandler;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!(msg instanceof ByteBuf)) {
            ctx.fireChannelRead(msg);
            return;
        }

        ByteBuf data = (ByteBuf) msg;
        // Append to cumulation buffer.
        if (cumulation == null) {
            cumulation = data;
        } else {
            cumulation = ctx.alloc().buffer(cumulation.readableBytes() + data.readableBytes())
                    .writeBytes(cumulation)
                    .writeBytes(data);
            data.release();
        }

        if (!cumulation.isReadable()) {
            return;
        }

        // The WebTransport stream signal is a QUIC variable-length integer (draft-ietf-webtrans-http3
        // §4.1). The bidi signal value is 0x41 (65), which exceeds the 1-byte varint range (0..63) and
        // therefore encodes as two bytes on the wire: 0x40 0x41. Read it as a varint.
        int typeVarintLen = numBytesForVariableLengthInteger(cumulation.getByte(cumulation.readerIndex()));
        if (cumulation.readableBytes() < typeVarintLen) {
            logger.debug("stream={} waiting for stream-type varint ({} bytes needed, {} available)",
                    ctx.channel(), typeVarintLen, cumulation.readableBytes());
            return;
        }
        int typeStartIndex = cumulation.readerIndex();
        long streamType = readVariableLengthInteger(cumulation, typeVarintLen);
        logger.warn("stream={} streamType=0x{} (varintLen={}b) cumulated={}b",
                ctx.channel(), Long.toHexString(streamType), typeVarintLen,
                cumulation.readableBytes() + typeVarintLen);

        if (streamType == WT_STREAM_TYPE_BIDIRECTIONAL) {
            // Wait until we have enough bytes for the session ID varint.
            if (!cumulation.isReadable()) {
                logger.debug("stream={} waiting for session-ID varint", ctx.channel());
                cumulation.readerIndex(typeStartIndex);
                return;
            }
            int sidLen = numBytesForVariableLengthInteger(cumulation.getByte(cumulation.readerIndex()));
            if (cumulation.readableBytes() < sidLen) {
                logger.debug("stream={} waiting for session-ID varint ({} bytes needed, {} available)",
                        ctx.channel(), sidLen, cumulation.readableBytes());
                cumulation.readerIndex(typeStartIndex);
                return;
            }
            long sessionId = readVariableLengthInteger(cumulation, sidLen);
            logger.warn("stream={} detected WT bidi stream, sessionId={}, remaining={}b",
                    ctx.channel(), sessionId, cumulation.readableBytes());

            // Look up the session.
            QuicChannel quicChannel = (QuicChannel) ctx.channel().parent();
            WebTransportSessionRegistry registry = WebTransportSessionRegistry.get(quicChannel);
            WebTransportSession session = registry != null ? registry.find(sessionId) : null;

            if (session == null) {
                logger.warn("stream={} unknown WT session ID={}, registry={}",
                        ctx.channel(), sessionId, registry);
                Http3CodecUtils.connectionError(ctx, Http3ErrorCode.H3_ID_ERROR,
                        "WebTransport bidirectional stream references unknown session ID: " + sessionId, false);
                return;
            }

            // Snapshot remaining bytes (application data that arrived with the WT stream header).
            int remainingBytes = cumulation.readableBytes();
            ByteBuf remaining = remainingBytes > 0
                    ? cumulation.readRetainedSlice(remainingBytes)
                    : null;
            cumulation.release();
            cumulation = null;
            logger.warn("stream={} calling onBidirectionalStream, remaining={}b", ctx.channel(), remainingBytes);

            // Install application handlers first, then remove ourselves so the pipeline is
            // ready before we fire remaining bytes.
            QuicStreamChannel streamChannel = (QuicStreamChannel) ctx.channel();
            session.listener().onBidirectionalStream(session, streamChannel);
            ctx.pipeline().remove(this);
            logger.warn("stream={} detector removed, pipeline={}", ctx.channel(), ctx.pipeline().names());

            // Forward any bytes that arrived in the same packet as the WT stream header.
            if (remaining != null) {
                logger.debug("stream={} firing remaining {}b to pipeline", ctx.channel(), remainingBytes);
                ctx.fireChannelRead(remaining);
            }
        } else {
            // Regular HTTP/3 request stream — install the full HTTP/3 pipeline.
            // Rewind so the HTTP/3 codec re-reads the stream-type varint as its first frame type.
            cumulation.readerIndex(typeStartIndex);
            ByteBuf remaining = cumulation;
            cumulation = null;
            long streamId = ((QuicStreamChannel) ctx.channel()).streamId();
            logger.debug("stream={} (id={}) detected HTTP/3 request stream, streamType=0x{}, replaying {}b",
                    ctx.channel(), streamId,
                    Long.toHexString(streamType),
                    remaining.readableBytes());

            ChannelPipeline pipeline = ctx.pipeline();
            QuicStreamChannel streamChannel = (QuicStreamChannel) ctx.channel();
            connectionHandler.initHttp3RequestPipeline(pipeline, streamChannel);
            pipeline.remove(this);

            // Replay accumulated bytes through the new HTTP/3 pipeline.
            ctx.fireChannelRead(remaining);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        if (cumulation != null) {
            cumulation.release();
            cumulation = null;
        }
        ctx.fireChannelInactive();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (cumulation != null) {
            cumulation.release();
            cumulation = null;
        }
        ctx.fireExceptionCaught(cause);
    }

    @Override
    public boolean isSharable() {
        return false;
    }
}
