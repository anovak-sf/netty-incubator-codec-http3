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
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class WebTransportStreamOutboundPrefixHandlerTest {

    @ParameterizedTest
    @ValueSource(ints = {0x41, 0x54})
    void testPrefixPrecedesWritesFromHandlerAdded(int streamType) {
        verifyPrefix(streamType, true);
    }

    @ParameterizedTest
    @ValueSource(ints = {0x41, 0x54})
    void testPrefixPrecedesWritesFromChannelRegistered(int streamType) {
        verifyPrefix(streamType, false);
    }

    private static void verifyPrefix(int streamType, boolean writeFromHandlerAdded) {
        EmbeddedChannel channel = new EmbeddedChannel(
                new WebTransportStreamOutboundPrefixHandler(streamType, 64),
                new ChannelInboundHandlerAdapter() {
                    @Override
                    public void handlerAdded(ChannelHandlerContext ctx) {
                        if (writeFromHandlerAdded) {
                            ctx.writeAndFlush(Unpooled.buffer().writeByte(42));
                        }
                    }

                    @Override
                    public void channelRegistered(ChannelHandlerContext ctx) {
                        if (!writeFromHandlerAdded) {
                            ctx.writeAndFlush(Unpooled.buffer().writeByte(42));
                        }
                        ctx.fireChannelRegistered();
                    }

                    @Override
                    public void channelActive(ChannelHandlerContext ctx) {
                        ctx.writeAndFlush(Unpooled.buffer().writeByte(43));
                        ctx.fireChannelActive();
                    }
                });
        try {
            ByteBuf prefix = channel.readOutbound();
            try {
                assertEquals(streamType, Http3CodecUtils.readVariableLengthInteger(prefix, 2));
                assertEquals(64, Http3CodecUtils.readVariableLengthInteger(prefix, 2));
                assertFalse(prefix.isReadable());
            } finally {
                prefix.release();
            }
            for (int payload : new int[] {42, 43}) {
                ByteBuf bytes = channel.readOutbound();
                try {
                    assertEquals(payload, bytes.readUnsignedByte());
                    assertFalse(bytes.isReadable());
                } finally {
                    bytes.release();
                }
            }
            assertNull(channel.readOutbound());
            assertNull(channel.pipeline().get(WebTransportStreamOutboundPrefixHandler.class));
        } finally {
            assertFalse(channel.finishAndReleaseAll());
        }
    }
}
