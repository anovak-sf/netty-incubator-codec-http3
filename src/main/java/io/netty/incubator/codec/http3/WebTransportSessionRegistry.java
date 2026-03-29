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

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-connection registry that maps WebTransport session IDs to active {@link WebTransportSession} instances.
 * <p>
 * The registry is stored as an attribute on the {@link io.netty.incubator.codec.quic.QuicChannel}.
 * It is created lazily when the first session is established and is safe to access from multiple
 * Netty event loop threads.
 */
final class WebTransportSessionRegistry {

    static final AttributeKey<WebTransportSessionRegistry> REGISTRY_KEY =
            AttributeKey.valueOf(WebTransportSessionRegistry.class, "WT_SESSION_REGISTRY");

    private final ConcurrentHashMap<Long, WebTransportSession> sessions = new ConcurrentHashMap<>();

    /**
     * Returns the registry associated with the given channel, creating it if absent.
     *
     * @param channel the QUIC channel
     * @return the registry (never {@code null})
     */
    static WebTransportSessionRegistry getOrCreate(Channel channel) {
        WebTransportSessionRegistry registry = channel.attr(REGISTRY_KEY).get();
        if (registry == null) {
            registry = new WebTransportSessionRegistry();
            WebTransportSessionRegistry existing = channel.attr(REGISTRY_KEY).setIfAbsent(registry);
            if (existing != null) {
                registry = existing;
            }
        }
        return registry;
    }

    /**
     * Returns the registry associated with the given channel, or {@code null} if none exists.
     *
     * @param channel the QUIC channel
     * @return the registry or {@code null}
     */
    @Nullable
    static WebTransportSessionRegistry get(Channel channel) {
        return channel.attr(REGISTRY_KEY).get();
    }

    /**
     * Registers a new session.
     *
     * @param sessionId the session ID (QUIC stream ID of the CONNECT stream)
     * @param session   the session object
     */
    void register(long sessionId, WebTransportSession session) {
        sessions.put(sessionId, session);
    }

    /**
     * Removes a session from the registry.
     *
     * @param sessionId the session ID to remove
     */
    void deregister(long sessionId) {
        sessions.remove(sessionId);
    }

    /**
     * Finds a session by ID.
     *
     * @param sessionId the session ID
     * @return the session, or {@code null} if not found
     */
    @Nullable
    WebTransportSession find(long sessionId) {
        return sessions.get(sessionId);
    }
}
