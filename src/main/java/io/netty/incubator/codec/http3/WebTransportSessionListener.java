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
import io.netty.incubator.codec.quic.QuicStreamChannel;

/**
 * Listener that receives events for an active {@link WebTransportSession}.
 * <p>
 * All methods are called from the Netty event loop thread associated with the session's connection.
 * Implementations must not block.
 */
public interface WebTransportSessionListener {

    /**
     * Called when a peer opens a new bidirectional WebTransport stream on this session.
     * <p>
     * The {@code streamChannel} carries raw byte data (no HTTP/3 framing). Add handlers
     * to {@code streamChannel.pipeline()} to process incoming data.
     *
     * @param session the WebTransport session this stream belongs to
     * @param streamChannel the QUIC stream channel for the bidirectional WT stream
     */
    void onBidirectionalStream(WebTransportSession session, QuicStreamChannel streamChannel);

    /**
     * Called when a peer opens a new unidirectional WebTransport stream on this session.
     * <p>
     * The {@code streamChannel} carries raw byte data. Add handlers to
     * {@code streamChannel.pipeline()} to process incoming data.
     *
     * @param session the WebTransport session this stream belongs to
     * @param streamChannel the QUIC stream channel for the unidirectional WT stream
     */
    void onUnidirectionalStream(WebTransportSession session, QuicStreamChannel streamChannel);

    /**
     * Called when a datagram is received for this session.
     * <p>
     * The {@code data} buffer's reference count is 1 and the caller will release it after this
     * method returns. Call {@code data.retain()} if you need to keep a reference.
     *
     * @param session the WebTransport session this datagram belongs to
     * @param data the datagram payload
     */
    void onDatagram(WebTransportSession session, ByteBuf data);

    /**
     * Called when the WebTransport session is closed.
     * <p>
     * A session closes when the underlying CONNECT stream is terminated (either cleanly via a
     * {@link WebTransportCloseSessionCapsule} or abruptly due to a stream/connection reset).
     *
     * @param session       the closed session
     * @param errorCode     the application error code (0 for clean close)
     * @param errorReason   the human-readable reason string (empty for clean close or abrupt close)
     */
    void onSessionClosed(WebTransportSession session, int errorCode, String errorReason);
}
