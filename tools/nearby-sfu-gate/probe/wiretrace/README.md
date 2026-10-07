# Passive signaling receipt

`wiretrace` observes IPv4 TCP on **lo, 127.0.0.1:17880 only**. It opens an
AF_PACKET socket and does not write network packets, alter routes, or proxy a
connection. The Linux executable requires NET_RAW. Duration is bounded to
1–120 seconds, connections to 64, and each stream's logical buffered payload to
1 MiB. The per-byte TCP reorder map has additional heap overhead; it is reset
after draining. The container memory limit is the total-memory guard, so an
overloaded observer can fail without establishing missing delivery.

Output is JSON containing connection ordinals, observation timestamps,
offer/answer kind, audio-section counts, and fixed capture-health counters.
HTTP headers, credentials, identities, raw signaling, SDP and packet payloads
remain in transient memory and must never be added to diagnostics. There is no
pcap output. Protobuf and SDP errors must not be printed with their input.

TCP streams start only at an observed SYN. The parser handles segmented headers,
out-of-order bytes, retransmissions, masked WebSocket frames and fragmented
binary messages. Upgrade headers and canonical frame lengths are validated;
the server accept is correlated with the captured client key. Close payloads
are validated and data following Close is never emitted.
Compression, text messages, malformed framing and conflicting
buffered overlap fail closed. Capturing an existing connection is incomplete.
Only the observed binary LiveKit protobuf transport is supported.

Run parser/privacy tests from the containing Go module:

```powershell
go test ./wiretrace -count=1
$env:GOOS='linux'
$env:GOARCH='amd64'
$env:CGO_ENABLED='0'
go build -o <absolute-owned-artifact-path> ./wiretrace
```

The server diagnostic runs in an owned disposable container with host networking,
read-only filesystem, all capabilities dropped except NET_RAW, no new privileges,
64 MiB memory, CPU/pid limits, and only the observer executable bind-mounted read
only. Use an existing verified image; do not install capture software or mount
host configuration. The image's ordinary non-root user may not retain NET_RAW,
so this diagnostic explicitly uses UID 0 inside that restricted container. Check
startup before starting clients and ensure the container is removed afterward.

A complete answer event proves that its bytes were observed on the loopback
connection. It does **not** prove that LiveKit consumed the frame, committed the
answer, or bound the track. Docker's published-port forwarding is another
boundary. Correlate ordinals with owned client joins and SFU negotiation state.
No event, even with zero final capture counters, is not by itself proof that the
client failed to send an answer. This tool does not test Android or audibility.
