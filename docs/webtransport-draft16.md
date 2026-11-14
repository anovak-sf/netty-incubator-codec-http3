# WebTransport draft-16 prerequisites and Chrome compatibility

Status checked on 2026-09-09. **Draft-16 is not implemented.** This codec currently uses the
legacy `webtransport` CONNECT token and `SETTINGS_ENABLE_WEBTRANSPORT=0x2b603742`.

## Chrome

The installed Chrome **152.0.7977.83** supports WebTransport HTTP/3 draft-02 by default.
Its [client's supported-version list](https://chromium.googlesource.com/chromium/src/+/refs/tags/152.0.7977.83/net/quic/dedicated_web_transport_http3_client.cc)
contains draft-02 and conditionally draft-07. The
[draft-07 feature is disabled by default](https://chromium.googlesource.com/chromium/src/+/refs/tags/152.0.7977.83/net/base/features.cc).
Neither path implements draft-16; exposing the JavaScript `WebTransport` API does not imply
support for the latest wire protocol.

This explains the implementation's previous Chrome interoperability. Replacing its legacy
negotiation with draft-16 alone would lose compatibility with that browser version. A future
upgrade should retain the legacy profile, negotiate the highest mutually supported draft for
the connection, and validate its CONNECT token and transport capabilities.

The client sends `sec-webtransport-http3-draft02: 1` and the server responds with
`sec-webtransport-http3-draft: draft02`, as required by
[legacy draft negotiation](https://www.ietf.org/archive/id/draft-ietf-webtrans-http3-03.html#section-6).
Peers omitting those headers remain accepted for compatibility.

The opt-in test passed against Chrome 152.0.7977.83 on 2026-09-09, including session establishment,
bidirectional stream echo, datagram echo, and closure with the expected code and reason.
It launches an isolated headless Chrome profile against a local native QUIC server:

```sh
mvn -Dtest=WebTransportChromeTest \
  '-Dwebtransport.chrome=/Applications/Google Chrome.app/Contents/MacOS/Google Chrome' test
```

The test requires a Chrome executable and Node.js with the built-in WebSocket API. It exercises
session establishment, a bidirectional stream, a datagram, and session closure. It pins the
temporary server's short-lived EC certificate through the JavaScript `serverCertificateHashes`
option; it does not modify a trust store or existing Chrome profile, or enable experimental
WebTransport protocol versions. Normal test
runs skip this test unless `webtransport.chrome` is supplied.

## QUIC dependencies that block draft-16

This project now pins `io.netty:netty-codec-classes-quic` and the platform-specific
`io.netty:netty-codec-native-quic` artifacts at **4.2.17.Final**. Its public stream API cannot
request reliable resets or negotiate their transport parameter, and its outbound shutdown
methods accept only `int` error codes. Incoming reset exceptions can carry a `long` error code,
but that does not solve the outbound limitation.

The underlying Cloudflare quiche implementation has an
[open issue for RESET_STREAM_AT transport semantics](https://github.com/cloudflare/quiche/issues/2564).
Adding a frame constant, logging support, or a transport parameter alone would not implement
retransmission and reliable delivery. Google QUICHE, used by Chrome, is a different library.

The checked [Netty 4.2.17 stream API](https://netty.io/4.2/api/io/netty/handler/codec/quic/QuicStreamChannel.html)
also exposes `int` shutdown error codes and no reliable-reset operation. A version bump alone
does not resolve these requirements. A transport upgrade needs native implementation changes,
Java/JNI bindings, and rebuilt native artifacts for supported platforms.

## Required protocol work after the transport upgrade

[Draft-16](https://www.ietf.org/archive/id/draft-ietf-webtrans-http3-16.html) requires:

| Area | Required behavior |
| --- | --- |
| Negotiation (§3.1, §9.2) | `SETTINGS_WT_ENABLED=0x2c7cf000`, server `SETTINGS_ENABLE_CONNECT_PROTOCOL=1`, datagram support, and `reset_stream_at`. Draft clients also send WT_ENABLED; servers wait for client SETTINGS. |
| CONNECT (§3.2) | Use `:protocol=webtransport-h3`; validate HTTPS, authority, path, and Origin whenever present. Browsers must supply Origin. No initiation in 0-RTT. |
| Resetting streams (§4.4) | Send `RESET_STREAM_AT` with Reliable Size covering the encoded WebTransport header; map unsigned 32-bit application errors into `0x52e4a40fa8db..0x52e5ac983162`, skipping reserved codes. |
| Session limits (§5.1) | Without negotiated session flow control, allow at most one simultaneous session and reject additional requests with `H3_REQUEST_REJECTED`. |
| Flow control (§5) | Multiplexed sessions need byte/stream accounting, initial settings, and flow-control capsules, including accounting for reset final sizes. |
| Capsules and application protocols (§3.3, §5.4) | Reject prohibited per-stream flow-control capsules; validate any selected application protocol against the client's offer. |

A first implementation can omit session flow control, ignore its four capsule types, and enforce
the single-session limit including pending CONNECT requests. Reliable resets remain mandatory.
Verify both profiles independently, including reset-before-prefix delivery, negative negotiation,
and browser interoperability, before advertising draft-16.
