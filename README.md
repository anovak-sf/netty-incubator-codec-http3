![Build project](https://github.com/netty/netty-incubator-codec-http3/workflows/Build%20project/badge.svg)

# netty-incubator-codec-http3
Experimental HTTP3 codec on top of our own [QUIC codec](https://github.com/netty/netty-incubator-codec-quic).

## How Can I use it ?

For some example usage please checkout our
[server example](https://github.com/netty/netty-incubator-codec-http3/blob/main/src/test/java/io/netty/incubator/codec/http3/example/Http3ServerExample.java) and
[client example](https://github.com/netty/netty-incubator-codec-http3/blob/main/src/test/java/io/netty/incubator/codec/http3/example/Http3ClientExample.java).

WebTransport support was built using Claude. It read webtransport spec and created initial version. After some iterations, webtransport server works with an application in Chrome.

The biggest pain point is that Chrome requires certificates signed by public CAs, imported CAs are ignored. They work with some developer settings, though.

So the way to have a working application is that you must implement certificate pinning. In that case, certificate can be even self signed, but it cannot have longer validity than 14 days.

Another thing to note is, that server must disable dynamic qpack table. Some bytes are going to the connect stream, while they should go to my bidirectional streams.

Finally, length prefixed messages are used in this example, you need to have corresponding decoding/coding on browser side.

```Kotlin
        val serverCodec = Http3.newQuicServerCodecBuilder()
            .sslContext(nettySniMapper.serverQuicSslContext)
            .maxIdleTimeout(30_000, TimeUnit.MILLISECONDS)
            .initialMaxData(10_000_000)
            .initialMaxStreamDataBidirectionalLocal(100_000)
            .initialMaxStreamDataBidirectionalRemote(100_000)
            .initialMaxStreamsBidirectional(1_000)
            // Required: advertise QUIC datagram support (RFC 9221/9297) so that RFC-compliant
            // clients accept our H3 SETTINGS that include H3_DATAGRAM=1 (needed for WebTransport).
            // We do not actually use QUIC datagrams for data transfer (only streams), so small
            // queues are fine.
            .datagram(128, 128)
            .tokenHandler(InsecureQuicTokenHandler.INSTANCE)
            .handler(
                object : ChannelInitializer<io.netty.channel.Channel>() {
                    override fun initChannel(ch: io.netty.channel.Channel) {
                        ch.pipeline().addLast(
                            Http3WebTransportServerConnectionHandler(
                                sessionAcceptor,
                                null,
                                null,
                                null,
                                settings,
                                true, // disableQpackDynamicTable: Chrome uses QPACK dynamic table which causes
                                      // QPACK blocking — the HEADERS frame is held waiting for dynamic table
                                      // updates, so pipeline surgery never completes. Meanwhile Chrome sends
                                      // capsule data on the CONNECT stream, which Http3FrameCodec reads as a
                                      // reserved HTTP/2 frame type (0x08). Setting this to true forces
                                      // QPACK_MAX_TABLE_CAPACITY=0 so Chrome falls back to static table only.
                            )
                        )
                    }
                }
            )
            .build()
```

where WebTransportSessionListener is something like:
```Kotlin
private fun newSessionListener(): WebTransportSessionListener {
    return object : WebTransportSessionListener {
        override fun onBidirectionalStream(session: WebTransportSession, streamChannel: QuicStreamChannel) {

            // Handlers must be installed synchronously on the EventLoop thread before data arrives.
            streamChannel.pipeline().addLast(
                LengthFieldBasedFrameDecoder(MAX_FRAME_BYTES, 0, LENGTH_FIELD_BYTES, 0, LENGTH_FIELD_BYTES),
                InboundFrameHandler(),
            )

            // handle 
        }

        override fun onUnidirectionalStream(session: WebTransportSession, streamChannel: QuicStreamChannel) {
            // some implementation 
        }

        override fun onDatagram(session: WebTransportSession, data: ByteBuf) {
            // some implementation
        }

        override fun onSessionClosed(session: WebTransportSession, errorCode: Int, reason: String) {
            // some implementation
        }
    }
}

/**
 * Decodes length-prefixed frames forwarded by [LengthFieldBasedFrameDecoder] (4-byte big-endian
 * length prefix already stripped) and pushes complete [ClientToGateway] protos into [inbound].
 */
private class InboundFrameHandler : SimpleChannelInboundHandler<ByteBuf>() {

    override fun channelRead0(ctx: ChannelHandlerContext, buf: ByteBuf) {
        val bytes = ByteArray(buf.readableBytes())
        buf.readBytes(bytes)
        // process bytes
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        super.channelInactive(ctx)
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        ctx.close()
    }
}
```