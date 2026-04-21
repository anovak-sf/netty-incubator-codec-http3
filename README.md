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

Another thing to note is, that server must disable dynamic qpack table. Some bytes are going to the control stream, while they should go to my bidirectional streams.
