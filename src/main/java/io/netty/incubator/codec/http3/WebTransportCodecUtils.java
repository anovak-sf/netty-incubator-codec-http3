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

/**
 * Constants for the WebTransport over HTTP/3 protocol.
 *
 * @see <a href="https://datatracker.ietf.org/doc/draft-ietf-webtrans-http3/">WebTransport over HTTP/3</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9297">RFC 9297 - HTTP Datagrams and the Capsule Protocol</a>
 */
final class WebTransportCodecUtils {

    /**
     * Stream type byte that prefixes WebTransport bidirectional streams.
     * Written as the first byte of a QUIC bidirectional stream opened for WebTransport use,
     * followed by the session ID as a variable-length integer.
     */
    static final int WT_STREAM_TYPE_BIDIRECTIONAL = 0x41;

    /**
     * Unidirectional stream type for WebTransport streams.
     * Written as the stream-type varint of a QUIC unidirectional stream,
     * followed by the session ID as a variable-length integer.
     */
    static final int WT_STREAM_TYPE_UNIDIRECTIONAL = 0x54;

    /**
     * Capsule type for DATAGRAM capsules (RFC 9297).
     * Carries an unreliable datagram payload for a WebTransport session.
     */
    static final long CAPSULE_TYPE_DATAGRAM = 0x00L;

    /**
     * Capsule type for CLOSE_WEBTRANSPORT_SESSION capsules.
     * Carries a 32-bit application error code and an optional reason string.
     */
    static final long CAPSULE_TYPE_CLOSE_WEBTRANSPORT_SESSION = 0x2843L;

    /**
     * Capsule type for DRAIN_WEBTRANSPORT_SESSION capsules.
     * Signals that the endpoint wishes to close the session soon.
     */
    static final long CAPSULE_TYPE_DRAIN_WEBTRANSPORT_SESSION = 0x78aeL;

    /**
     * Maximum length in bytes of a CLOSE_WEBTRANSPORT_SESSION reason string.
     */
    static final int WT_CLOSE_REASON_MAX_LEN = 1024;

    private WebTransportCodecUtils() {
    }
}
