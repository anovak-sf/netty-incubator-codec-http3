# netty-incubator-codec-http3
HTTP/3 and WebTransport fork using the maintained [Netty 4.2 QUIC codec](https://netty.io/4.2/api/io/netty/handler/codec/quic/package-summary.html).

The HTTP/3 implementation and regression tests incorporate the changes from
[Netty 4.2.17.Final](https://github.com/netty/netty/tree/netty-4.2.17.Final/codec-http3),
merged against this fork's upstream baseline `78b7bea`, while retaining the custom
WebTransport implementation and its fixes. The artifact is
`io.netty.incubator:netty-incubator-codec-http3:0.0.33.0.wtransport-netty42-SNAPSHOT`.

The HTTP/3 package remains `io.netty.incubator.codec.http3`. Applications must update QUIC
imports to `io.netty.handler.codec.quic` and align their unshaded Netty dependencies to
**4.2.17.Final**, including the matching native QUIC artifacts. Use `io.netty:netty-bom`
for version alignment. Upstream defaults now limit header field sections to 8 KiB and
buffered unknown frame payloads to 32 KiB; configure larger limits explicitly when required.
The minimum Java version remains 8. This dependency migration does not implement draft-16.

## How Can I use it ?

For some example usage please checkout our
[server example](src/test/java/io/netty/incubator/codec/http3/example/Http3ServerExample.java) and
[client example](src/test/java/io/netty/incubator/codec/http3/example/Http3ClientExample.java).

WebTransport support was built using Claude. It read webtransport spec and created initial version. After some iterations, webtransport server works with an application in Chrome.

This implementation uses the legacy `:protocol=webtransport` and
`SETTINGS_ENABLE_WEBTRANSPORT=0x2b603742` wire format. It is **not an implementation of
[WebTransport over HTTP/3 draft-16](https://www.ietf.org/archive/id/draft-ietf-webtrans-http3-16.html)**,
which uses different negotiation and requires reliable stream resets. Multiplexing sessions
also requires session flow control.
Do not advertise a newer draft's settings without implementing its remaining requirements.
See [draft-16 prerequisites and Chrome compatibility](docs/webtransport-draft16.md) for the
QUIC dependency changes needed for an upgrade and an optional browser interoperability test.

Capsules on the CONNECT stream are carried inside HTTP/3 DATA frames, as required by
[RFC 9297](https://www.rfc-editor.org/rfc/rfc9297.html#section-3.1).
`WebTransportSession.sendDatagram()` sends native QUIC datagrams; both endpoints must configure
QUIC datagram support. The session acceptor must validate any `Origin` header against the
application's allowed origins before accepting a browser session. Session listeners receive
`onSessionDraining()` when the peer asks them to finish using the session.

Streams and datagrams arriving before their CONNECT request are buffered with count and byte
limits. A native QUIC stream that closes before its CONNECT arrives cannot be replayed later;
such pending stream data is released when the channel closes.

The biggest pain point is that Chrome requires certificates signed by public CAs, imported CAs are ignored. They work with some developer settings, though.

So the way to have a working application is that you must implement certificate pinning. In that case, certificate can be even self signed, but it cannot have longer validity than 14 days.

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
                                true, // disableQpackDynamicTable: use static QPACK tables in this example.
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
