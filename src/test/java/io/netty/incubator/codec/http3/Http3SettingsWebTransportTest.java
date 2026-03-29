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

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class Http3SettingsWebTransportTest {

    @Test
    void testDefaultWebTransportNotSet() {
        // defaultSettings() does not include ENABLE_WEBTRANSPORT, so returns null
        assertNull(Http3Settings.defaultSettings().webTransportEnabled());
    }

    @Test
    void testNullWhenNotSet() {
        assertNull(new Http3Settings().webTransportEnabled());
    }

    @Test
    void testEnableWebTransport() {
        assertTrue(new Http3Settings().enableWebTransport(true).webTransportEnabled());
    }

    @Test
    void testDisableWebTransport() {
        Http3Settings s = new Http3Settings().enableWebTransport(true);
        assertTrue(s.webTransportEnabled());
        s.enableWebTransport(false);
        assertFalse(s.webTransportEnabled());
    }

    @Test
    void testInvalidNegativeValue() {
        assertThrows(IllegalArgumentException.class, () ->
                new Http3Settings().put(
                        Http3SettingIdentifier.HTTP3_SETTINGS_WEBTRANSPORT_ENABLE.id(), -1L));
    }

    @Test
    void testSettingIdentifierValue() {
        assertEquals(0x2b603742L, Http3SettingIdentifier.HTTP3_SETTINGS_WEBTRANSPORT_ENABLE.id());
    }

    @Test
    void testWebTransportSettingRoundTripViaFrame() {
        Http3SettingsFrame frame = new DefaultHttp3SettingsFrame();
        frame.put(Http3SettingIdentifier.HTTP3_SETTINGS_WEBTRANSPORT_ENABLE.id(), 1L);
        assertEquals(1L, frame.get(Http3SettingIdentifier.HTTP3_SETTINGS_WEBTRANSPORT_ENABLE.id()));
    }

    @Test
    void testWebTransportSettingAppearsInIterator() {
        Http3Settings s = new Http3Settings().enableWebTransport(true);
        boolean found = false;
        for (Map.Entry<Long, Long> e : s) {
            if (e.getKey().equals(Http3SettingIdentifier.HTTP3_SETTINGS_WEBTRANSPORT_ENABLE.id())) {
                found = true;
            }
        }
        assertTrue(found);
    }

    @Test
    void testZeroValueReturnsFalse() {
        Http3Settings s = new Http3Settings();
        s.put(Http3SettingIdentifier.HTTP3_SETTINGS_WEBTRANSPORT_ENABLE.id(), 0L);
        assertFalse(s.webTransportEnabled());
    }

    @Test
    void testPositiveValueReturnsTrue() {
        Http3Settings s = new Http3Settings();
        s.put(Http3SettingIdentifier.HTTP3_SETTINGS_WEBTRANSPORT_ENABLE.id(), 1L);
        assertTrue(s.webTransportEnabled());
    }
}
