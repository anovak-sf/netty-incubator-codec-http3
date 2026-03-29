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

import io.netty.util.internal.ObjectUtil;

/**
 * User event fired on the session stream channel after a WebTransport session has been successfully established.
 * <p>
 * After this event is fired the session stream's pipeline has been rewired to use the RFC 9297
 * capsule protocol instead of HTTP/3 framing. Applications can use the associated
 * {@link WebTransportSession} to open streams, send datagrams, or close the session.
 */
public final class WebTransportSessionEstablishedEvent {

    private final WebTransportSession session;

    /**
     * Creates a new event for the given session.
     *
     * @param session the established WebTransport session (never {@code null})
     */
    public WebTransportSessionEstablishedEvent(WebTransportSession session) {
        this.session = ObjectUtil.checkNotNull(session, "session");
    }

    /**
     * Returns the established WebTransport session.
     *
     * @return the session (never {@code null})
     */
    public WebTransportSession session() {
        return session;
    }

    @Override
    public String toString() {
        return "WebTransportSessionEstablishedEvent{session=" + session + '}';
    }
}
