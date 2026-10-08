# Nearby SFU answer boundary diagnostic

> Execute with superpowers:executing-plans. Complex diagnosis stays with the primary agent; a narrow Luna/xhigh review may check the parser's privacy contract.

**Goal:** Observe decoded subscriber answers at the service, participant dispatch and transport processing boundaries without exporting SDP or changing production.

**Architecture:** Use existing DEBUG statements in an owned disposable container running the exact production image. Disable Docker log persistence, attach before clients, parse output only in volatile memory and emit a fixed semantic whitelist. Reuse the verified Linux synthetic-media probe for a positive control. This diagnoses the observation mechanism; a passing isolated probe does not repair or reproduce the rare Internet failure.

**Evidence:** `docs/superpowers/specs/2026-10-07-nearby-live-transport.md`. Pinned generated source is `.superpowers/sdd/2026-10-04-nearby-authentication-ownership/c94194-production`.

## Constraints

- No production container, configuration, network, credential or DB changes; no phone claims.
- No raw log file, SDP, JWT, identity, ICE credential or error text leaves the parser. Unknown caller/message pairs are ignored; malformed relevant records fail evidence admission.
- Exact image/probe identity, fresh owned workspace/name, bounded time/memory/records, checked cleanup.
- Reading an answer, dispatching it and beginning transport processing are distinct observations. `processing answer` precedes `SetRemoteDescription`; never label it applied.
- Zero answer ID is accepted by this pinned server; no blind ID/target/timeout patch.

## Task 1: bounded semantic parser

- [x] Write tests for the three actual caller/message boundaries, secret-bearing nested fields, duplicate stages, foreign callers, invalid JSON, oversized records, bounded peers/events and timer-failure classification; run actual RED.
- [x] Implement `tools/nearby-sfu-gate/signal-boundary.py` and run fresh Python tests. Fixed integer ordinals replace identities; input errors never include their input.

## Task 2: actual disposable positive control

- [x] Add `run-signal-boundary-probe.py`: exact production image, Docker log driver `none`, volatile attach parser, loopback fixture, verified existing probe and exact cleanup. Never enable production debug.
- [x] Run on the VPS with a fresh nonce. Assert positive reads/dispatches/processes for the same owned peers; record capture health and limitations. Recheck unchanged production topology after cleanup.
- [x] Narrow parser/runner review; fresh full Android suite before any commit, exact 3407 tests/336 fresh XML suites/zero bad; update evidence and ledger.

Measured evidence and remaining investigation: [decoded answer boundaries](../specs/2026-10-08-nearby-sfu-answer-boundary-evidence.md).
