# Nearby explicit leave/delete HTTP boundary

Authenticated POST community/leave and community/delete use a separate optional
port. Production defaults remain503. JWT owns the actor; canonical operation and
successor IDs are validated before repository delegation. Stable operation IDs
are forwarded unchanged on repeated requests. Owner departure returns explicit
successor_required, delete_required or invalid_successor conflicts. Last-owner
leave never invokes deletion as a fallback.

The routes reuse creation's strictUTF8, streamed16KiB, finite ten-second body
reader. Response fields are operationId, communityId, revision and cleanupPending.
No other participants' session IDs, SFU epochs or credentials are returned.
SQL membership/removal confirmation is independent of media stop confirmation:
cleanupPending aggregates actual durable journal flags, including a deliberately
incoherent terminal/null-session fixture. It is not inferred from session shape.

Eight new tests first produced missing-API compile RED; targeted19 PASS covers
these plus the preceding eleven creation/read/join cases after reader refactor.
The invalidUTF8 fixture is otherwise valid under replacement decoding; a direct
precondition proves that lenient decoding would reach mutation. Streamed oversize
has no Content-Length. Calls, canonical payloads, authenticated actor, replay ID,
owner conflicts, private denied response and safe result fields are asserted.

Fresh full backend143 PASS62823 and Android2633 PASS36463, zero failures/errors/
skips and exact counts asserted. Gradle executed all6 backend/all35 Android tasks
with rerun-tasks and caches disabled. No mutation-sweep claim.

Narrow Luna source review passed JWT ownership, canonical payload IDs, explicit
last-owner conflict, safe DTO fields, actual cleanup flag and shared reader usage.
That review did not execute tests or inspect SQL/transaction behavior.

Repository/schema unchanged; earlier actual PostgreSQL131 evidence remains scoped
to atomic departure/deletion, not to these route fakes. Client removal adapter,
UI confirmations, production dependency installation, account-deletion fencing,
v2 sharing and SFU worker/device acceptance remain separate prerequisites.
