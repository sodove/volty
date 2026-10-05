# Server-relative ringing credit

Incoming notifications need finite credit independent of phone wall clock. Add optional
remainingRingMillis to status decision/response, earned only by STATUS of a still-live RING
inside the existing fenced SQL pair transaction, at its actual checked server time. Bound
credit to 0..45000; accepted/terminal/denied/unknown have none. No SFU or new migration.
Caller and callee status receive the same original deadline credit. The raw epoch deadline
remains display metadata, not phone clock authorization.

The client will project credit from original monotonic request issuance, not receipt, so
response delay cannot replenish ringing. A verifier without fresh credit cannot offer a
notification ring. First SQL/HTTP tests (actual expiry, friendship withdrawal, partial elapsed
time), then client decoder/verifier and notification lifetime. Fresh PG/default backend and
Android suites before commits. No Firebase provisioning, production deployment or device claim.

Client continuation: dedicated DirectCallRingObservation carries original call/revision/request
ordinal/start/receipt/deadline. Optional ring response credit is strict numeric 0..45000 and
RING-only; overflow/backward clocks are invalid, zero/delayed credit is never replenished.
Missing credit stays compatible with basic metadata reads but cannot offer a timed incoming
notification. Hint verifier requires an explicit platform monotonic clock and fresh bound
observation; its Ringing reply carries both original snapshot and finite observation. Add
actual HTTP delay/zero/malformed/terminal/overflow tests and verifier stale-response tests.
Publication remains separately owned and rechecked before notification/explicit acceptance.
