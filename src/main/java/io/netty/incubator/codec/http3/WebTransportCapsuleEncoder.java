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
import io.netty.buffer.CompositeByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;
import io.netty.util.CharsetUtil;

import static io.netty.incubator.codec.http3.Http3CodecUtils.numBytesForVariableLengthInteger;
import static io.netty.incubator.codec.http3.Http3CodecUtils.writeVariableLengthInteger;

/**
 * Encodes {@link WebTransportCapsule} objects into the RFC 9297 capsule wire format:
 * <pre>
 *   [Capsule Type (variable-length integer)]
 *   [Capsule Length (variable-length integer)]
 *   [Capsule Value (Capsule Length bytes)]
 * </pre>
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9297">RFC 9297 - HTTP Datagrams and the Capsule Protocol</a>
 */
@ChannelHandler.Sharable
final class WebTransportCapsuleEncoder extends MessageToByteEncoder<WebTransportCapsule> {

    static final WebTransportCapsuleEncoder INSTANCE = new WebTransportCapsuleEncoder();

    private WebTransportCapsuleEncoder() {
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, WebTransportCapsule capsule, ByteBuf out) throws Exception {
        long type = capsule.capsuleType();

        if (capsule instanceof WebTransportDatagramCapsule) {
            ByteBuf payload = ((WebTransportDatagramCapsule) capsule).content();
            int payloadLen = payload.readableBytes();
            writeVariableLengthInteger(out, type);
            writeVariableLengthInteger(out, payloadLen);
            out.writeBytes(payload, payload.readerIndex(), payloadLen);
        } else if (capsule instanceof WebTransportCloseSessionCapsule) {
            WebTransportCloseSessionCapsule close = (WebTransportCloseSessionCapsule) capsule;
            byte[] reasonBytes = close.errorReason().getBytes(CharsetUtil.UTF_8);
            long valueLen = 4L + reasonBytes.length; // 4 bytes for error code
            writeVariableLengthInteger(out, type);
            writeVariableLengthInteger(out, valueLen);
            out.writeInt(close.applicationErrorCode());
            out.writeBytes(reasonBytes);
        } else if (capsule instanceof WebTransportDrainSessionCapsule) {
            writeVariableLengthInteger(out, type);
            writeVariableLengthInteger(out, 0L); // zero-length value
        } else {
            throw new IllegalArgumentException("Unsupported capsule type: " + capsule.getClass().getName());
        }
    }

    @Override
    public boolean isSharable() {
        return true;
    }
}
