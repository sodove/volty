# Nearby durable operation journal evidence

2026-10-03. Additive local backend implementation, not a production deployment.
V2 voice API, SFU coordinator and roster are not wired yet.

`JdbcSessionRepository` serializes mutations through a PostgreSQL actor row.
Cancellation persists before an operation has a session. Replays preserve session
identity/deadline. Replacement commits old terminal cleanup intent and new claim
in one transaction. Media ACK must match operation, session, room epoch, node
incarnation and current claim. Cancel/expire releases only the matching claim.
Cleanup ACK clears only its terminal row and never clears a later claim.
SFU effects are not issued inside SQL transactions.

Room generation and node incarnation are separate immutable values. New SFU
incarnation does not revive a previously issued session. Confirmation bounds
backend deadline to the confirmed SFU deadline. Pending admission and terminal
cleanup remain durable work flags; a later coordinator must reconcile them.

Migration V2 creates separate journal/claim tables and leaves legacy accounts,
friendships, groups and membership intact. Migrator holds a transaction-scoped
advisory lock across schema-table creation and version application; failures roll
back both DDL and version records. Actor transactions set database lock timeout
5 s, statement timeout 10 s, and idle transaction timeout 15 s.

## Actual PostgreSQL tests

17 integration tests, zero failures/errors/skips. Fresh disposable fixture nonce
`6728394a-5bb3-4182-8d3a-1e44293d2230`; evidence under
`.superpowers/sdd/2026-10-03-nearby-backend-session-lifecycle/postgres-6728394a-5bb3-4182-8d3a-1e44293d2230`.
Cached PostgreSQL17 Alpine image identity:
`sha256:1bea307dfb3ee30541a7acf7de14b58bcd6948da98e5d31a04c627c4d35ec64b`.
Fresh database uses tmpfs, disposable credentials and loopback-only forwarded port.
No production database, volume, checkout or running service was changed.
Container ownership was checked by nonce label before stopping; cleanup confirmed.

Coverage: cancel before join and repository restart; lost-response replay;
cancel after join and durable cleanup; old cleanup after replacement; independent
room/node fences; missing current claim; exact deadline heartbeat; pending expiry;
rollback after previous claim was changed; pending/due work queries; V1→V2 upgrade
with legacy membership preserved; replayed/concurrent migrations; failed DDL.

Join/cancel are tested in both measured lock orders. A test-only JDBC proxy waits
after the first real FOR UPDATE acquires its lock and records the competing query
before allowing commit. Both futures are unfinished at that point. No timing-only
assertion substitutes for issuing the path. The ordinary concurrent-start test
provides a separate unscripted race. Timeout test first failed with a Future
timeout; after adding DB limits it observes SQLSTATE55P03 and verifies rollback
preserves the prior session. Initial missing-repository/epoch API RED logs remain.

Fresh backend unit suite: 61 tests, no failures/errors/skips. Android suite:
2573 tests, no failures/errors. Both use rerun-tasks with build/config caches off.
These do not prove Android audibility, SFU process restart or distributed behavior.

## Remaining integration requirements

The coordinator must check authenticated SFU incarnation/readiness, reconcile
older cleanup before registering replacement media, recheck claim after each ACK,
never issue credentials from a terminal operation and bound renewal/expiry work.
Read-only journal queries are observations, not authorization to issue effects.
Roster revisions and destination membership authorization remain separate tasks.
This repository supports one backend-owned claim; it does not itself guarantee
no simultaneous external media during replacement. That needs coordinator/runtime
proof, including partial cleanup and backend/SFU restart.
