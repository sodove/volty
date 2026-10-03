# Nearby session HTTP foundation

Backend lifecycle Task4a, 2026-10-03. Routes exist; production dependency remains
null and returns503 until community membership and SFU readiness are wired.
Roster, media confirmation and product UI cutover are separate unfinished work.

Authenticated routes under `/v2/nearby`:

- POST operations/{operationId}/join: destination UUID, replace=false by default.
- GET operations/{operationId}: status without participant credentials.
- POST operations/{operationId}/cancel: durable cancellation without sessionId.
- POST sessions/{sessionId}/heartbeat and /disconnect: actor-owned exact session.

Verified access JWT plus existing account supplies the actor. Client account/room
names cannot select the actor or SFU destination. UUID validation precedes the
service call. Actual room resolution remains a supplied authorization port.
v2 participates in the existing request limiter. Responses carry no-store.
Participant credential DTO toString is redacted. Registration yields CONNECTING,
not a claim that media, microphone capture or speaker playback is confirmed.

Retraction of the repository foundation's implicit replacement behavior: the
approved product contract requires second-device/operation join to return busy
unless replacement is explicit. An actual PostgreSQL failing check demonstrated
the earlier automatic claim takeover (run2bc87a54). Busy/defaultfalse is now
decided under the actor lock, not through a race-prone read before the transaction.
Existing ownership and rollback fixtures state replacement=true explicitly.
An expired claim can yield to a new operation while retaining old media cleanup.

Actual PostgreSQL37 passed:21 repository and16 coordinator/endpoint tests,
zero failures/errors/skips, run04761770-6f9b-4f33-9b3e-9194e6bcc52d. Exact competing
operations had one winner; old-session disconnect preserved replacement ownership;
foreign actor session lookup issued no media effects. Status expired/cleaned a
session without issuing credentials. Busy join did not register/revoke/issue a
token; explicit replacement then succeeded. Owned disposable container stopped.

Fresh backend unit85 passed, including8 HTTP boundary checks. Bounded Luna review
and fresh Android2573 passed before commit. The review
passed actor/room and old-session targeting; it did not run tests and explicitly
recorded default503 production integration. Missing-port and missing-adapter
compilation REDs are preserved in the ledger; they are not runtime race evidence.

Current injected membership lookup is not atomic production membership fencing:
community removal can race preflight resolution. Keep the production dependency
disabled until transaction-level membership/ban/channel generation checks exist.
No production schema deployment, service restart or client cutover was performed.
