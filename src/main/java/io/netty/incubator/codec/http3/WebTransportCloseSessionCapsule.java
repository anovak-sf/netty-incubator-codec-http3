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
import io.netty.util.internal.StringUtil;

/**
 * A CLOSE_WEBTRANSPORT_SESSION capsule that signals the termination of a WebTransport session
 * with an application error code and an optional human-readable reason string.
 *
 * @see <a href="https://datatracker.ietf.org/doc/draft-ietf-webtrans-http3/">WebTransport over HTTP/3</a>
 */
public final class WebTransportCloseSessionCapsule implements WebTransportCapsule {

    private final int applicationErrorCode;
    private final String errorReason;

    /**
     * Creates a new CLOSE_WEBTRANSPORT_SESSION capsule.
     *
     * @param applicationErrorCode the application-defined 32-bit error code
     * @param errorReason          the human-readable reason string (must not be {@code null}, may be empty,
     *                             UTF-8 encoded, max {@value WebTransportCodecUtils#WT_CLOSE_REASON_MAX_LEN} bytes)
     */
    public WebTransportCloseSessionCapsule(int applicationErrorCode, String errorReason) {
        this.applicationErrorCode = applicationErrorCode;
        this.errorReason = ObjectUtil.checkNotNull(errorReason, "errorReason");
    }

    /**
     * Returns the application-defined error code.
     *
     * @return 32-bit error code
     */
    public int applicationErrorCode() {
        return applicationErrorCode;
    }

    /**
     * Returns the human-readable error reason string.
     *
     * @return the reason string (never {@code null}, may be empty)
     */
    public String errorReason() {
        return errorReason;
    }

    @Override
    public long capsuleType() {
        return WebTransportCodecUtils.CAPSULE_TYPE_CLOSE_WEBTRANSPORT_SESSION;
    }

    @Override
    public String toString() {
        return StringUtil.simpleClassName(this) +
                "(errorCode=" + applicationErrorCode +
                ", reason='" + errorReason + "')";
    }
}
