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
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.util.CharsetUtil;

import java.util.List;

import static io.netty.incubator.codec.http3.Http3CodecUtils.numBytesForVariableLengthInteger;
import static io.netty.incubator.codec.http3.Http3CodecUtils.readVariableLengthInteger;
import static io.netty.incubator.codec.http3.WebTransportCodecUtils.CAPSULE_TYPE_CLOSE_WEBTRANSPORT_SESSION;
import static io.netty.incubator.codec.http3.WebTransportCodecUtils.CAPSULE_TYPE_DATAGRAM;
import static io.netty.incubator.codec.http3.WebTransportCodecUtils.CAPSULE_TYPE_DRAIN_WEBTRANSPORT_SESSION;

/**
 * Decodes the RFC 9297 capsule protocol from a byte stream.
 * <p>
 * Each capsule has the format:
 * <pre>
 *   [Capsule Type (variable-length integer)]
 *   [Capsule Length (variable-length integer)]
 *   [Capsule Value (Capsule Length bytes)]
 * </pre>
 * <p>
 * Known capsule types are decoded into {@link WebTransportCapsule} subtypes and emitted.
 * Unknown capsule types are silently discarded per RFC 9297 §3.2.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9297">RFC 9297 - HTTP Datagrams and the Capsule Protocol</a>
 */
final class WebTransportCapsuleDecoder extends ByteToMessageDecoder {

    static final int MAX_DATAGRAM_CAPSULE_LENGTH = 65535;

    private long bytesToDiscard;
    private boolean closeReceived;
    private boolean failed;

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        if (failed) {
            in.skipBytes(in.readableBytes());
            return;
        }
        if (!in.isReadable()) {
            return;
        }
        if (closeReceived) {
            fail(ctx, in);
            return;
        }
        if (bytesToDiscard != 0) {
            discard(in);
            return;
        }

        int start = in.readerIndex();
        int typeLen = numBytesForVariableLengthInteger(in.getByte(start));
        if (in.readableBytes() < typeLen) {
            return;
        }
        long type = readVariableLengthInteger(in, typeLen);
        if (!in.isReadable()) {
            in.readerIndex(start);
            return;
        }
        int lenLen = numBytesForVariableLengthInteger(in.getByte(in.readerIndex()));
        if (in.readableBytes() < lenLen) {
            in.readerIndex(start);
            return;
        }
        long capsuleLength = readVariableLengthInteger(in, lenLen);

        if (type == CAPSULE_TYPE_CLOSE_WEBTRANSPORT_SESSION) {
            if (capsuleLength < 4 || capsuleLength > 4 + WebTransportCodecUtils.WT_CLOSE_REASON_MAX_LEN) {
                fail(ctx, in);
                return;
            }
        } else if (type == CAPSULE_TYPE_DRAIN_WEBTRANSPORT_SESSION) {
            // RFC 9297 requires exactly the fields defined by the capsule type.
            if (capsuleLength != 0) {
                fail(ctx, in);
                return;
            }
            out.add(WebTransportDrainSessionCapsule.INSTANCE);
            return;
        } else if (type != CAPSULE_TYPE_DATAGRAM || capsuleLength > MAX_DATAGRAM_CAPSULE_LENGTH) {
            // Skip unknown and unusably large datagrams incrementally. Buffering their entire
            // value can exhaust memory or the flow-control window (RFC 9297 sections 3.2 and 3.5).
            bytesToDiscard = capsuleLength;
            discard(in);
            return;
        }

        int length = (int) capsuleLength;
        if (in.readableBytes() < length) {
            in.readerIndex(start);
            return;
        }
        if (type == CAPSULE_TYPE_DATAGRAM) {
            out.add(new WebTransportDatagramCapsule(in.readRetainedSlice(length)));
        } else {
            int errorCode = in.readInt();
            int reasonLen = length - 4;
            if (!ByteBufUtil.isText(in, in.readerIndex(), reasonLen, CharsetUtil.UTF_8)) {
                fail(ctx, in);
                return;
            }
            String reason = in.readCharSequence(reasonLen, CharsetUtil.UTF_8).toString();
            closeReceived = true;
            out.add(new WebTransportCloseSessionCapsule(errorCode, reason));
            // Check before delivering the close capsule: its consumer can close the channel.
            if (in.isReadable()) {
                fail(ctx, in);
            }
        }
    }

    private void discard(ByteBuf in) {
        int discarded = (int) Math.min(bytesToDiscard, in.readableBytes());
        in.skipBytes(discarded);
        bytesToDiscard -= discarded;
    }

    @Override
    protected void decodeLast(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        super.decodeLast(ctx, in, out);
        // RFC 9297 section 3.3 also requires rejecting a partial capsule at clean EOF.
        if (!failed && (in.isReadable() || bytesToDiscard != 0)) {
            fail(ctx, in);
        }
    }

    private void fail(ChannelHandlerContext ctx, ByteBuf in) {
        failed = true;
        bytesToDiscard = 0;
        in.skipBytes(in.readableBytes());
        // Capsule errors are malformed HTTP messages, scoped to this CONNECT stream.
        QuicStreamChannel stream = (QuicStreamChannel) ctx.channel();
        WebTransportSessionRegistry registry = WebTransportSessionRegistry.get(stream.parent());
        if (registry != null) {
            WebTransportSession session = registry.find(stream.streamId());
            if (session != null) {
                session.terminate(0, "");
            }
        }
        stream.shutdown(Http3ErrorCode.H3_MESSAGE_ERROR.code);
    }

    @Override
    public boolean isSharable() {
        return false;
    }
}
