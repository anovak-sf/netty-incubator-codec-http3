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
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.util.ReferenceCountUtil;

/**
 * Carries capsule bytes in the CONNECT stream's HTTP/3 DATA frames (RFC 9297, Section 3.1).
 */
final class WebTransportHttp3CapsuleHandler extends ChannelDuplexHandler {

    static void install(ChannelHandlerContext ctx, WebTransportSession session) {
        ChannelPipeline pipeline = ctx.pipeline();
        pipeline.addBefore(ctx.name(), "wtHttp3Capsules", new WebTransportHttp3CapsuleHandler());
        pipeline.addBefore(ctx.name(), "wtCapsuleDecoder", new WebTransportCapsuleDecoder());
        pipeline.addBefore(ctx.name(), "wtCapsuleEncoder", WebTransportCapsuleEncoder.INSTANCE);
        pipeline.replace(ctx.name(), "wtSessionStream", new WebTransportSessionStreamHandler(session));
    }

    static boolean hasForbiddenHeaders(Http3Headers headers) {
        return headers.contains("content-length") || headers.contains("content-type")
                || headers.contains("transfer-encoding");
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof Http3DataFrame) {
            Http3DataFrame frame = (Http3DataFrame) msg;
            ByteBuf content = frame.content().retain();
            frame.release();
            ctx.fireChannelRead(content);
        } else if (msg instanceof Http3HeadersFrame || msg instanceof Http3PushPromiseFrame) {
            // A completed CONNECT only permits DATA and applicable extension frames (RFC 9114, Section 4.4).
            Http3FrameValidationUtils.frameTypeUnexpected(ctx, msg);
        } else {
            // HTTP frame boundaries and extension frames are not part of the capsule byte stream.
            ReferenceCountUtil.release(msg);
        }
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        if (msg instanceof ByteBuf) {
            ctx.write(new DefaultHttp3DataFrame((ByteBuf) msg), promise);
        } else {
            ctx.write(msg, promise);
        }
    }
}
