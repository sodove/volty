# Nearby signed webhook inbox foundation

V4 adds durable JOINED/LEFT reconciliation intent, separate from session claims,
media confirmation and roster revisions. An original SDK-signed body is decoded
with strict UTF8 and verified with pinned io.livekit WebhookReceiver0.15.0. No
skipAuth overload is used. The pinned SFU protocol URLNotifier.send signs exact
protobuf JSON bytes using SHA256/Base64 and sends raw JWT Authorization, without
a Bearer prefix. The HTTP receiver preserves original bytes and bounds streaming
input to65536+1 bytes, including absent Content-Length; oversized payloads fail
before signature parsing or inbox invocation. Production dependency stays null.

The verifier emits only eventId, digest, immutable room/session/participant SID
and supported kind. No event timestamp orders observations. Neither a joined
notification nor a left notification alone grants authority to change a claim or
assert transport. A future consumer must reconcile the current gate-owned SID,
expected SFU incarnation and actor-locked journal before completing work.

Supported events return204 only after inbox insert/dedup commits. Replayed eventId
with the same digest succeeds without requeueing completed work; different digest
returns409 without replacing the original intent. Failed durability returns503;
private port exceptions are neither logged nor returned. Completion requires exact
eventId+digest. Signed unsupported events (e.g. track_published) are deliberately
ignored with204 without inbox persistence; they produce no effects. The durable
receipt invariant applies to supported events. Room-finished processing remains
pending rather than being presented as implemented by this foundation.

## Evidence so far

- Verifier missing-API compile RED before implementation; seven fresh tests PASS
  for actual signatures, original-byte whitespace/tampering, expiry/issuer/secret,
  invalidUTF8/body size, incoherent provenance, and authenticated ignored events.
- HTTP missing-port compile RED before implementation; six fresh Ktor tests PASS.
  An unknown-length exact65536-byte signed stream returns204 and a65537-byte
  stream returns413. Invalid/missing signature never invokes event port; retry,
  collision and private-exception outcomes remain explicit.
- Inbox missing-API compile RED before implementation; actual PostgreSQL5 PASS
  run da561b9e-c4c8-4339-8da4-4cc57f4446b5 in an owned disposable fixture.
  Durable restart/replay, completed-event replay, same-ID/hash collision,
  actual concurrent different-body one-winner insert, exact completion ownership,
  and additive upgrade fromV3 preserving the roster clock are covered.
- CountedV1,V2,V3 before addingV4. Default migration count changed3→4 deliberately.
- Narrow Luna source review confirms signature/body bounds/exception privacy,
  default disabled wiring and exact digest ownership. Its unsupported-event
  receipt question is resolved by the explicit no-effects ignored-event contract
  above. Reviewer did not run tests; no broader review is claimed.

Fresh full backend107, PostgreSQL53 and Android2573 PASS. Full actual PostgreSQL
run bf92af83-bd38-4bdc-aea7-6a22e761b728 has zero failures/errors/skips; the owned
fixture is cleaned up. This foundation does not implement the
media consumer, room-finished events, lease renewal gating or client/UI cutover.
