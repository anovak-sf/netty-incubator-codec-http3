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

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.socket.ChannelInputShutdownEvent;
import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.util.ReferenceCountUtil;

/**
 * Handles capsules received on the WebTransport session stream and dispatches them to the
 * {@link WebTransportSessionListener}.
 * <p>
 * This handler receives capsules decoded from HTTP/3 DATA frames on the CONNECT stream.
 */
final class WebTransportSessionStreamHandler extends ChannelInboundHandlerAdapter {

    private final WebTransportSession session;
    private int closeErrorCode;
    private String closeReason = "";

    WebTransportSessionStreamHandler(WebTransportSession session) {
        this.session = session;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        // Fire the session established event now that we are properly in the pipeline.
        ctx.fireUserEventTriggered(new WebTransportSessionEstablishedEvent(session));
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof WebTransportDatagramCapsule) {
            WebTransportDatagramCapsule capsule = (WebTransportDatagramCapsule) msg;
            try {
                if (!session.isClosed()) {
                    session.listener().onDatagram(session, capsule.content());
                }
            } finally {
                ReferenceCountUtil.release(capsule);
            }
        } else if (msg instanceof WebTransportCloseSessionCapsule) {
            WebTransportCloseSessionCapsule close = (WebTransportCloseSessionCapsule) msg;
            closeErrorCode = close.applicationErrorCode();
            closeReason = close.errorReason();
            session.terminate(closeErrorCode, closeReason);
            ((QuicStreamChannel) ctx.channel()).shutdownOutput();
        } else if (msg instanceof WebTransportDrainSessionCapsule) {
            if (!session.isClosed()) {
                session.listener().onSessionDraining(session);
            }
        } else {
            ReferenceCountUtil.release(msg);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        session.terminate(closeErrorCode, closeReason);
        ctx.fireChannelInactive();
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof ChannelInputShutdownEvent) {
            session.terminate(closeErrorCode, closeReason);
            ((QuicStreamChannel) ctx.channel()).shutdownOutput();
        }
        ctx.fireUserEventTriggered(evt);
    }

    @Override
    public boolean isSharable() {
        return false;
    }
}
