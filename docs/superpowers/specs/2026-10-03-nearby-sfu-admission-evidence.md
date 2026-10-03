# Nearby: single-node SFU admission evidence

2026-10-03. Local prototype result; no deployed fix or Android audibility claim.
Stock RemoveParticipant plus token TTL remains disproven.

## Reproduction

`tools/nearby-sfu-gate/prepare-overlay.ps1` checks four source hashes, copies
LiveKit 1.13.5 commit `3b9f118327b257301083a7c4aa46076c8012918a` and applies
the overlay without changing the original checkout. RTC-node StartSession,
including resume, serializes with revoke on `(room epoch, session identity)`.
Revoke marks terminal before cleanup and awaits subscription/transport closure.
Register replay does not renew; expiry and revoke cannot be resurrected. Unknown
sessions fail closed. Tombstones are retained; capacity rejects instead of evicting.
Redis configuration is rejected. Exact-room admin authorization protects controls;
loopback race barriers use the same authentication. Token errors are sanitized.

Fresh preparation in `reproduced-716b8487-46a5-4f5c-b485-6e1223058fe8` matches
all seven patched Go files byte-for-byte against the tested checkout.
`go test -count=1 -json ./lease`: 10 top-level tests and 3 subcases, zero failures.
Initial RED was a missing-implementation compilation failure. Review hardening
asserts deadline denial before sweep and asserts revoked admission never issues
the join callback. Fresh Android suite: 2573 tests expected, recorded separately.

## Runtime

Evidence: `.superpowers/sdd/2026-10-03-nearby-sfu-admission/run-579deade-2632-4df6-ad91-b614f703b6d9`.
Fresh room nonce: `2bc3c454-e742-4870-9306-7e6f2ee69160`.

| Check | Result |
|---|---|
| Unknown identity, valid JWT | Denied |
| Participant / wrong-room admin control | Both HTTP 403 |
| Three-peer baseline RTP | B receive 30; C from B 10; A from C 12 |
| Revoke B ACK after cleanup | 601 ms |
| B client kept running after ACK | Receive and forward deltas both 0 |
| A/C survivors | Duplex continued |
| Original and refreshed B JWT | Both denied |
| Terminal B register / renew | Denied |
| Admission held inside gate; concurrent revoke | No early ACK; participant absent at ACK; raced JWT denied |
| 8 s lease expires without client stop | Both media deltas 0; late renew and still-valid JWT denied |
| Refreshed JWT after original expiry | Denied |
| Owned SFU cleanup | Confirmed stopped |

Post-revoke counter window: 300 ms drain, then 1 s observation. Expiry checked
after 10 s plus a 1 s observation. Original JWT expiry 1791004546, refreshed
expiry 1791005086. Tokens stayed in memory. Synthetic encoded Opus/RTP measures
transport behavior, not speech audibility or Android capture/render.

| Artifact | SHA256 |
|---|---|
| SFU executable | `7FE203CF2F31D5AD96DA3B343E359ADF2555935785D24C467BE0CD79150526F1` |
| Probe executable | `8D120EA07A287ED483548C2893AAE49B92CD0A82194B22FA64D4E9373528C65F` |
| Probe main.go | `7F692541EF3415FF672D2DCC1389E32E327FE172A717F9F9F6DB967D66BE6237` |
| Probe go.sum | `4A9D33D0F9895DB55F4E6A6E78D0392C55832B75D167454697DD97A2D0345A57` |

## Decision and limits

Ready to design single-node backend integration; production readiness unproven.
Backend needs durable operation/claim transactions, terminal cancellation before
registration, authenticated node readiness, bounded heartbeat renewal, node epochs
and capacity handling. Restart must never replay terminal operations. No deployed
source, services, credentials or production rooms were changed.

Fresh-registry denial and same-SID ownership are unit-tested. Actual SFU restart,
WHIP, Android automatic reconnect and signalling resume were not runtime tested.
Source shows WHIP and signalling call StartSession; this is not runtime proof.
Cleanup failure remains terminal and retryable; failed cleanup cannot be reported
as successful disconnect. Expiry scheduling under saturation/process pauses is
uncharacterized and must be bounded and surfaced in the production implementation.
