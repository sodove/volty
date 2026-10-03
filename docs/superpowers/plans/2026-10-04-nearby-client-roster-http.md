# Client authoritative roster HTTP plan

Continue the approved journal/replica foundation toward real synchronized voice presence.
This slice supplies strict authenticated snapshot transport and conservative local peer lease
credits. It does not claim final WebSocket publication authorization or production/UI wiring.

1. Meaningful MockEngine tests before API: exact channel GET/bearer and caller subscription
   tag; strict required JSON types, UUID actor/session/operation, duplicate actors/sessions,
   phase/media/SID consistency, foreign channel, malformed UTF-8/body bounds, denial/redirect/
   exceptions/cancellation/finite timeout, monotonic rollback/overflow and request ordinals
   despite equal clock ticks/concurrent response order.
2. VoiceRosterSnapshotPort returns tagged update plus server time, owned request ordinal,
   monotonic request start/receipt, and peer-local monotonic deadlines. Remaining credit is
   peer deadline minus server observed time, bounded at30s and charged from local request
   start, never phone wall time or response-receipt plus30s. Expired rows earn zero fresh
   credit but preserve persisted revision/content; local freshness is a separate projection.
3. HTTPS nonemptyhost/no credentials/query/fragment, no redirect/retry, whole10s timeout,
   bounded256KiB response (full roster), strict UTF-8, sanitized typed failures. Unknown-media
   SID may be absent; connected evidence requires SID and matching phase. Neither proves
   phone capture, routing or audibility.
4. Narrow Luna/xhigh transport-specific and lease/ordinal source reviews; fresh full Android/
   backend suites, evidence/commit. No backend source mutation or production activation.

Then generation-owned roster subscription/projection and authorized server publication;
v2 sharing revocation/runtime/Android integration, direct calls and later UI remain planned.
Current RR snapshot preflight alone must not enable production realtime delivery.
