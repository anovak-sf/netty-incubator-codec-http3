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
import io.netty.handler.codec.ByteToMessageDecoder;
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
 * Unknown capsule types are silently discarded per RFC 9297 §3.3.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9297">RFC 9297 - HTTP Datagrams and the Capsule Protocol</a>
 */
final class WebTransportCapsuleDecoder extends ByteToMessageDecoder {

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        // Need at least 1 byte to determine the type length.
        if (!in.isReadable()) {
            return;
        }

        // Read capsule type (varint)
        int typeLen = numBytesForVariableLengthInteger(in.getByte(in.readerIndex()));
        if (in.readableBytes() < typeLen) {
            return;
        }
        // Peek the type without consuming yet — we need to know how many bytes to skip for unknown types.
        long type = readVariableLengthInteger(in.slice(in.readerIndex(), typeLen), typeLen);

        // Advance past type
        in.skipBytes(typeLen);

        // Read capsule length (varint)
        if (!in.isReadable()) {
            // Roll back — not enough for length varint
            in.readerIndex(in.readerIndex() - typeLen);
            return;
        }
        int lenLen = numBytesForVariableLengthInteger(in.getByte(in.readerIndex()));
        if (in.readableBytes() < lenLen) {
            in.readerIndex(in.readerIndex() - typeLen);
            return;
        }
        long capsuleLength = readVariableLengthInteger(in, lenLen);
        if (capsuleLength < 0 || capsuleLength > Integer.MAX_VALUE) {
            // Length cannot be negative or more than we can handle.
            in.readerIndex(in.readerIndex() - typeLen - lenLen);
            Http3CodecUtils.connectionError(ctx, Http3ErrorCode.H3_MESSAGE_ERROR,
                    "Capsule length out of range: " + capsuleLength, false);
            return;
        }

        int length = (int) capsuleLength;

        // Ensure all capsule value bytes are available before consuming
        if (in.readableBytes() < length) {
            // Roll back to beginning of this capsule
            in.readerIndex(in.readerIndex() - typeLen - lenLen);
            return;
        }

        // Dispatch
        if (type == CAPSULE_TYPE_DATAGRAM) {
            ByteBuf payload = in.readRetainedSlice(length);
            out.add(new WebTransportDatagramCapsule(payload));
        } else if (type == CAPSULE_TYPE_CLOSE_WEBTRANSPORT_SESSION) {
            if (length < 4) {
                Http3CodecUtils.connectionError(ctx, Http3ErrorCode.H3_MESSAGE_ERROR,
                        "CLOSE_WEBTRANSPORT_SESSION capsule too short: " + length, false);
                return;
            }
            int errorCode = in.readInt();
            int reasonLen = length - 4;
            String reason = reasonLen > 0 ? in.readCharSequence(reasonLen, CharsetUtil.UTF_8).toString() : "";
            out.add(new WebTransportCloseSessionCapsule(errorCode, reason));
        } else if (type == CAPSULE_TYPE_DRAIN_WEBTRANSPORT_SESSION) {
            if (length != 0) {
                // Spec says DRAIN has empty payload; skip unexpected bytes but still deliver the capsule.
                in.skipBytes(length);
            }
            out.add(WebTransportDrainSessionCapsule.INSTANCE);
        } else {
            // Unknown capsule type — silently discard per RFC 9297 §3.3.
            in.skipBytes(length);
        }
    }

    @Override
    public boolean isSharable() {
        return false;
    }
}
