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
 * Marker interface for WebTransport capsules as defined by
 * <a href="https://www.rfc-editor.org/rfc/rfc9297">RFC 9297 - HTTP Datagrams and the Capsule Protocol</a>.
 * <p>
 * Capsules are used on the WebTransport session stream (the HTTP/3 CONNECT stream) after the
 * session is established. They carry datagrams, session close signals, and other session-level messages.
 */
public interface WebTransportCapsule {

    /**
     * Returns the capsule type identifier.
     *
     * @return the capsule type as a variable-length integer value
     */
    long capsuleType();
}
