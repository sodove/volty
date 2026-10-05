# Durable original sharing revoke intents

Persist only canonical actor/session/generation/request UUIDs. No bearer, coordinates, telemetry,
server-relative lease rebasing or inferred authentication epoch. One original record per actor/
tag, duplicate record preserves first nonce. Bound actor queue to 256 records and read batches
to 1..64. Fail closed at capacity before any new positive sharing is issued (integration follows).

Only exact original Negative(REVOKED/EXPIRED) acknowledged by the explicit authenticated actor
may delete its matching original request nonce. Failed/access denied/unauthorized/mismatched tag
cannot remove it. A delayed old ACK cannot delete a later record for the same original tag.
SQLite transactions protect insert admission and conditional ACK. Repository borrows database;
actual JDBC file close/reopen proves persistence. Actor selection is explicit, no global scan.

Counted local migrations 1..12 and snapshots through13: add13.sqm (13->14) plus .sq and generate
14.db. Only old SQLite-compatible CREATE TABLE/PK/SELECT/LIMIT/INSERT OR IGNORE/DELETE; no BLE/
vehicle table alteration or legacy groups import. Verify all migrations and fresh Android.

Ruling: this task stores cleanup intent only. Existing in-memory negative uses original captured
bearer unchanged. JWT has sub/kind/iat/exp/jti, no stable login-session identifier; do not pretend
a durable auth epoch exists. Later replay must be a separate negative-only capability for an
explicit verified matching actor, never a new account's positive voice/sharing control. No token
storage, automatic replay/DI activation or offline-completion claim in this journal task.
