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

import org.jetbrains.annotations.Nullable;

/**
 * Server-side callback that determines whether to accept an incoming WebTransport session request.
 * <p>
 * Implementations are called on the Netty event loop when a client sends an Extended CONNECT
 * request with {@code :protocol: webtransport}. Return a {@link WebTransportSessionListener} to
 * accept the session, or {@code null} to reject it (the server will respond with HTTP 400).
 */
public interface WebTransportSessionAcceptor {

    /**
     * Determines whether to accept the WebTransport session request described by {@code requestHeaders}.
     *
     * @param requestHeaders the HTTP/3 headers from the Extended CONNECT request;
     *                       includes {@code :method}, {@code :protocol}, {@code :scheme},
     *                       {@code :authority}, {@code :path}, and any custom headers
     * @return a {@link WebTransportSessionListener} to accept the session, or {@code null} to reject it
     */
    @Nullable
    WebTransportSessionListener accept(Http3Headers requestHeaders);
}
