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
import io.netty.buffer.DefaultByteBufHolder;

/**
 * A WebTransport DATAGRAM capsule (capsule type {@code 0x00}) that carries an unreliable datagram payload.
 * <p>
 * This capsule is sent over the WebTransport session stream and provides datagram delivery for peers
 * that do not support the QUIC DATAGRAM extension (RFC 9221).
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9297">RFC 9297 - HTTP Datagrams and the Capsule Protocol</a>
 */
public final class WebTransportDatagramCapsule extends DefaultByteBufHolder implements WebTransportCapsule {

    /**
     * Creates a new DATAGRAM capsule with the given payload.
     *
     * @param data the datagram payload (ownership is transferred to this capsule)
     */
    public WebTransportDatagramCapsule(ByteBuf data) {
        super(data);
    }

    @Override
    public long capsuleType() {
        return WebTransportCodecUtils.CAPSULE_TYPE_DATAGRAM;
    }

    @Override
    public WebTransportDatagramCapsule copy() {
        return new WebTransportDatagramCapsule(content().copy());
    }

    @Override
    public WebTransportDatagramCapsule duplicate() {
        return new WebTransportDatagramCapsule(content().duplicate());
    }

    @Override
    public WebTransportDatagramCapsule retainedDuplicate() {
        return new WebTransportDatagramCapsule(content().retainedDuplicate());
    }

    @Override
    public WebTransportDatagramCapsule replace(ByteBuf content) {
        return new WebTransportDatagramCapsule(content);
    }

    @Override
    public WebTransportDatagramCapsule retain() {
        super.retain();
        return this;
    }

    @Override
    public WebTransportDatagramCapsule retain(int increment) {
        super.retain(increment);
        return this;
    }

    @Override
    public WebTransportDatagramCapsule touch() {
        super.touch();
        return this;
    }

    @Override
    public WebTransportDatagramCapsule touch(Object hint) {
        super.touch(hint);
        return this;
    }
}
