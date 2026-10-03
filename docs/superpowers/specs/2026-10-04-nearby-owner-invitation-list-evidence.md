# Owner invitation list evidence

Current active server owner reads deterministic UUID pages in a repeatable-read transaction.
Limit1..50/canonical cursor and exact parent filtering; summaries include expired/revoked
metadata but no token/hash. New owner can inspect inherited links, former owner and ordinary
members cannot. GET uses JWT/store actor, no-store, optional503 and generic404, rejecting
duplicate or invalid page parameters before calling the reader.

Actual PG4 first observed missingAPI RED then fresh PASS run1845d406-b16d-406e-9407-a5c76c39ef15,
zero failures/errors/skips, all6 tasks executed, owned_container_stopped=true. Tests cover
paging/foreign filter/no secrets or effects, new/current/former owner, used/revoked metadata,
deleted user/server/unknown denial and bounded input. Repository source unchanged after this
run; HTTP DTO/port addition separately covered by three recording route tests, first
missingAPI RED then fresh backend161 PASS9s/all6 tasks, exactzero.

Five client MockEngine cases observed missingAPI RED before implementation. Client checks
exact server, bounded items, canonical unique ascending IDs after cursor, TTL/quota/uses,
positive creation revision and paired revocation fields. Expired/revoked rows remain valid.
Retraction: an early test draft capped all pages at1, so duplicate/order cases could stop at
the cap guard; changed those fixtures tolimit2 with no cursor BEFORE implementation, and
asserted all issued requests. Timeout/cancellation drivers assert actual issue/no retry.
Fresh full Android2703 PASS31s/all35 tasks, exactzero. Narrow Luna backend/list-route and
client-specific source reviews passed. These are ordinary tests, no mutation/device proof.

No secret recovery/regeneration or membership/voice/sharing side effects. Secure Android
draft creation/persistence, production factory/auth/retry/logging configuration, authorized
roster publication/sharing, runtime integration and UI/device validation remain pending.
No deployment or legacy group transfer.
