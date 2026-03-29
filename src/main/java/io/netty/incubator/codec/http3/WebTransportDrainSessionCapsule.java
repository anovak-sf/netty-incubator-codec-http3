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
 * A DRAIN_WEBTRANSPORT_SESSION capsule that signals the sender's intent to close the WebTransport session soon.
 * Recipients should avoid opening new streams or sending new datagrams upon receipt of this capsule.
 *
 * @see <a href="https://datatracker.ietf.org/doc/draft-ietf-webtrans-http3/">WebTransport over HTTP/3</a>
 */
public final class WebTransportDrainSessionCapsule implements WebTransportCapsule {

    /**
     * Singleton instance — this capsule carries no payload.
     */
    public static final WebTransportDrainSessionCapsule INSTANCE = new WebTransportDrainSessionCapsule();

    private WebTransportDrainSessionCapsule() {
    }

    @Override
    public long capsuleType() {
        return WebTransportCodecUtils.CAPSULE_TYPE_DRAIN_WEBTRANSPORT_SESSION;
    }

    @Override
    public String toString() {
        return "WebTransportDrainSessionCapsule{}";
    }
}
