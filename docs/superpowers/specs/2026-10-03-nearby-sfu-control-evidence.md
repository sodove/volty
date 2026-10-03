# Nearby SFU control protocol evidence

2026-10-03. Backend plan task3a: versioned readiness and control adapter completed;
durable coordinator, v2 endpoints and production deployment remain unfinished.

Retraction recorded during coordinator integration: protocol1's request-start
+30s receipt was insufficient for an idempotent register replay after a lost
response. The gate preserved the original deadline, but the adapter invented a
later one. Protocol2 reports actual remaining milliseconds. Actual supervised
singleton process restart is now measured separately. This document preserves
the historical run; see [coordinator evidence](2026-10-03-nearby-coordinator-evidence.md)
for the corrected protocol and later runtime checks.

The single-node overlay exposes authenticated exact-room POST readiness protocol1:
node incarnation, sweep health, registry slots/capacity, maximum lease duration.
Node incarnation comes from 128 bits of startup cryptographic randomness and is
required on register/renew/revoke. Missing or different incarnation rejects the
command before it mutates the gate. Sweep failure or no successful sweep within
2 s blocks new register/renew/admission; revoke stays available for cleanup.
Unknown health is never represented as successful cleanup. Registry capacity
includes terminal tombstones; full capacity does not evict existing fences.

This is an admission interlock, not proof of hard real-time expiry under load:
the prototype sweep can wait on entry locks/cleanup. Scheduling and capacity
characterization remain production requirements. Redis remains rejected.

## Fresh runtime

Workspace `.superpowers/sdd/2026-10-03-nearby-backend-session-lifecycle/sfu-ready-2afb446f-1daf-42a7-b3af-965bf4459dab`.
RTP run `run-9554c208-a4f6-4a7a-b934-5c7f465e0e7f`; room nonce
`64e14af6-8328-4816-8209-7615469e2f66`; node incarnation
`46cdbd74cda75ec5c8c2fc4cd44983f1`.

- Readiness protocol1, capacity4096, ready=true; participant and wrong-room admin
  cannot inspect or mutate controls.
- Old-node register denied and valid participant JWT still denied admission.
- Old/missing-node revoke and old-node renew denied; current duplex media continued.
- Baseline RTP: B receive24, C fromB11, A fromC14. Revoke ACK600ms; after drain,
  1s receive and forward deltas0, survivors duplex. Original/refreshed JWT denied.
- Pending admission/revoke barrier passed. Expiry without client stop passed;
  late renew denied. Refreshed JWT remained denied after original expiry.
- Synthetic RTP only; no Android capture, rendering or audibility measurement.
  Owned process stopped. Actual SFU restart/WHIP/Android resume remain unmeasured.

Binary SHA256: SFU
`2E89D874AD305F28CCDCE2B31803D782AD8FE03FD46E4CAD7510A22105D21A86`;
probe `7362B00017EE569943AF4D5D9336445ABA4ACAA98A1A58AAFCBD5D71ADD626F9`.
Fresh overlay preparation reproduced all nine modified Go files byte-for-byte.

## Kotlin adapter

`HttpSfuLeaseClient` verifies version/fields and keeps node health/capacity explicit.
Commands include expected incarnation and immutable session identity; admin JWT
is scoped to the requested room. Non-204 control is failure. Errors expose only
static categories, never server text or credentials. Lease confirmation uses
request-start time +30s, so a delayed ACK cannot extend the application deadline.

HTTP connect timeout3s, complete response timeout6s by default, response size
limit4096 bytes. A dedicated bounded body subscriber cancels oversized responses.
An actual test showed request timeout alone did not bound a stalled body after
headers arrived (RED Future TimeoutException); complete-future timeout plus body
cancellation fixed it. Request-issued and partial-body-issued preconditions are
asserted. Interrupted calls restore the thread interrupt flag.

11 adapter HTTP tests passed; full backend unit suite72 passed, no skips. Go18
test pass events (including subcases) passed. Android2573 passed fresh. Kotlin
client then ran against the actual owned SFU executable: 2 tests, no failures,
errors or skips, proving inspect/register/renew/revoke and terminal/node rejection
wire compatibility. Evidence `kotlin-run-3a784ef0-271a-4d22-bfb6-5c10a6a01575`;
owned SFU stopped and logs checked for credential-looking content.

This adapter is not connected to production routes. Coordinator must recheck
durable claim/operation after ACK and cleanup before replacement registration.
An observation or an adapter success alone never authorizes issuing a participant
token from a cancelled operation. No production checkout/services were changed.
