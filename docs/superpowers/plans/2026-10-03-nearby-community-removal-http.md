# Nearby explicit community removal HTTP

Expose already-tested atomic V8 departure and V9 owner deletion through a separate
fakeable authenticated endpoint port. No SQL changes and no network under SQL
locks. Routes remain503 until their optional dependency is explicitly installed.

POST /v2/nearby/communities/{id}/leave accepts stable operationId and optional
successorId. POST /{id}/delete accepts stable operationId; delete is its own explicit
action, never an automatic fallback for last-owner leave. Canonical UUID payloads,
strictUTF8, bounded16KiB streamed body and ten-second read deadline share the
existing creation reader. JWT actor only, never a body-supplied actor/owner.

Response contains operationId/communityId/revision and cleanupPending. Do not
expose other participants' session IDs, SFU epochs or credentials. Membership/
deletion SQL commit is distinct from SFU removal: aggregate actual durable
cleanupPending flags, not session existence or terminal phase. Replay uses the
same receipt operation and current journal cleanup flags. Invalid owner choices
return409 successor_required/delete_required/invalid_successor; operation reuse
conflict409; unknown/denied uniform404. No implicit creation, join or sharing.

Tests assert actual issued port args and safe response projection, malformed/
oversized/invalidUTF8 body rejection before repository calls, authenticated and
disabled boundaries. Reuse PostgreSQL131 repository/schema evidence without
claiming these route fakes prove SQL or SFU cleanup. Fresh backend suite and
Android suite before commit. Client adapter and UI confirmations follow.
