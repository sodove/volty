# Protocol4 candidate image: synthetic media evidence

Measured 2026-10-07, owned disposable container only. Production services/checkout/credentials unchanged. No Android/phone/audio rendering claim.

- Exact candidate image `sha256:bba3a6329d7b3ae09e3094e642136ba17ba1d33545f735a400b8f589bae682b6`, same production overlay artifact as Stage145.
- Fresh successful run nonce `1e27acce-36af-42b7-b781-3f5c3e4db356`. Linux probe SHA256 `f7103ada862dfdbba47e6817461c5698b0e83599143682a1be5b6a3ac0a859bc`, pinned existing Go SDK dependencies; SFU/probe secrets are public disposable fixture values. Refresh credentials stay opaque in memory; checked logs contain no credential-shaped token.
- Synthetic Opus/RTP baseline: B received 11 packets from A, C received 18 from B, A received 18 from C. All actual publishers/subscribers were issued before asserting media.
- Exact ACTIVE participant observation and connected renewal checked against issued SID; registered-only, historical SID/node and unauthorized/wrong-room controls denied. Same logical session acquired a new SID and old SID could not renew it.
- Revoke ACK 605ms. Revoked client's publish/receive loops stayed running: after 300ms drain, one-second receive/forward deltas were both zero, surviving peers remained duplex. Both original and captured SFU-refreshed tokens denied rejoin; registration/renewal could not resurrect revoke.
- Eight-second lease expired without client stop. Actual duplex baseline earned before expiry; measured post-expiry media deltas zero, delayed renewal denied, original still-valid JWT denied. Captured refreshed JWT also denied after original JWT expiry.
- Authorized test-barrier route returned404. Deterministic pending-admission barrier race deliberately NOT run in production-image mode; prototype mode retains that test. This run does not substitute for prototype race/restart/resume or Internet/NAT/TURN checks.
- Owned host-loopback fixture ports were checked free; container restricted to512MiB/1CPU/read-only/temp tmpfs with public fixture config. Runner verified exact container/image/task label before removal, including failed-probe path. Cleanup JSON inspected and both owned containers absent afterwards.

Initial c941a2 driver run reached real duplex/revoke/token-denial checks but exited2 at prototype barrier arm. That is a reached failing driver path, not a successful full matrix. Added explicit production mode with absence assertion; default prototype race remains. Final c941a4 actualexit0/matrix_passed, complete expiry/refreshed-token cases above. An earlier relative-workdir read failed before editing/building; it is not runtime RED. Android c941a5 fresh3373zero/44s35executed, strict XML; unrelated Kotlin backend/PG unchanged.

Public Internet transport, Android microphone/render output, Xiaomi bilateral audibility and physical headset routing remain unmeasured. Connected-media revocation on this exact candidate is now measured; earlier Stage145 control-only limitation records the previous milestone.
