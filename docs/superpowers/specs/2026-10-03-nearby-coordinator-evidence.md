# Nearby durable coordinator evidence

2026-10-03. Backend lifecycle task3b. This foundation does not expose production
v2 routes or replace the product Nearby UI. No Android audibility/routing claim.

SessionService commits pending intent before registration, rechecks the durable
operation and claim after the SFU response, and issues microphone-only participant
credentials under the actor lock. Cancellation is durable before external revoke;
failed cleanup stays retryable and blocks replacement registration. Reconciliation
does not mint credentials. Membership resolution remains an injected boundary.
Deadlines are checked again after external responses and actual lock acquisition.

## Corrections proven by failing checks

- Protocol1 adapter invented request-start +30s on register replay. The gate's
  idempotent replay intentionally does not renew. RED expected31000, actual39000.
  Protocol2 now returns actual remaining milliseconds, validated in 1..30000;
  adapter adds them to request-start, conservatively excluding response latency.
- A caller timestamp taken before waiting for the actor lock authorized a token
  after expiry. Actual PostgreSQL barrier test issued the competing query, moved
  the clock to the deadline, then released the lock: RED returned a credential.
  Repository mutations now evaluate the clock under the lock. Local issuance
  is CPU-only; no SFU/network request holds this lock.
- Credential issuer failure propagated a private exception and left registered
  media behind. RED demonstrated this; the coordinator now cancels and revokes,
  returning a static unavailable category.
- Node restart left old cleanup permanently pending. An explicitly configured
  managed singleton origin may acknowledge replacement after authenticated
  protocol2 inspection finds a different incarnation. Default configuration
  retains cleanup. This relies on exclusive single-process hosting, without a
  load balancer or replicas; metadata alone does not establish distributed fencing.

## Owned SFU runtime

Pinned LiveKit1.13.5 overlay, fresh workspace
`sfu-restart-74e71eeb-a611-435b-ab2d-8ee5fdd4953f` under the lifecycle ledger.
SFU SHA256 `44FD40405DA83A7922D7E9BA4EE18312D9FC93D88B060DC26560EA20276C678F`;
probe `2B74E60376C3677A0B030790D2736C0BE749E00A4B26325B957ABA833517C98F`.

Full matrix run `run-b03448a8-0501-46d7-b91e-d9661501f1c6`, fresh room nonce
`13f1c170-a734-48f3-a256-9d77c9222704`: protocol2 authenticated readiness,
receipt8000/replay8000 without renewal; baseline RTP21/10/20; stale-node controls
denied while current media continued; revoke ACK601ms; receive/forward deltas0
and surviving peers duplex. Pending-admission barrier, expiry without client stop,
original/refreshed token rejection and rejection after original expiry all passed.

Separate supervised restart run `restart-run-1ae3d2e1-1cfc-4756-b41b-c458512093f2`:
old process was stopped and its exit confirmed before replacement started on the
same loopback origin. Incarnation changed from594f62e21f9f29f5857c39872fef9728
to5badaf143647caeb1c17b27d2a653cb3. Baseline duplex11/20 packets; old participants
kept publishing but receive delta0. Still-valid original and captured refreshed
JWTs were denied; new immutable sessions achieved duplex; old JWT remained denied
after those new sessions. Both owned processes stopped afterward.

First restart run4550a956 failed the harness privacy check: SDK default reconnect
logging included four disposable token patterns. Those logs were sanitized in
place, and the failed run is not counted as a successful verification. Probe now
disables SDK logging before setup; fresh passing run had no credential patterns.
All credentials are synthetic local fixtures. Production was not probed/restarted.

Gate package has19 fresh test pass events including subcases. An attempted
`go test ./...` from the overlay template module failed because its service
templates depend on the pinned server module. Correct package command is
`go test -count=1 -json ./lease`; templates are compiled in the prepared server.

## Remaining limits

Final fresh checks: backend unit77; actual PostgreSQL31 (repository18,
coordinator13), zero failures/errors/skips, runf895c3dc-25d5-4c42-a13c-b52ce6dc5168.
The corrected Kotlin client passed2 actual SFU wire tests in
`kotlin-run-4c9ec92f-4716-4792-a8a8-fed2a36dbc6a`. Android2573 passed fresh.
All9 prepared overlay source files reproduced byte-for-byte in a new directory.
Bounded Luna review passed old-cleanup claim ownership/default singleton behavior;
it did not run tests or establish the deployment premise. Fixture cleanup confirmed.

These are synthetic RTP and transactional tests, not phone microphone/headphone
rendering, Xiaomi behavior, Android resume or WHIP measurements. Expiry sweep can
wait on entry cleanup; hard expiry under load and capacity sizing remain open.
No production enablement, schema rollout or client cutover was performed.
