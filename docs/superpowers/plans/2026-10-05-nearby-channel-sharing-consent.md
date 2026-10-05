# Channel-session sharing consent and durable revoke

Start sharing only for the actor's actual ACTIVE CHANNEL journal session and current claim,
with current channel membership/ACL. Direct calls and mere server membership grant nothing.
Original session UUID and explicit generation UUID bind consent; original 30-minute/2-hour/
8-hour TTL and LOCATION/RIDE/FULL mode are immutable under replay. No coordinates or earned
telemetry are introduced in this slice; payload publisher/read audience follow afterward.

Add migration V16, leaving V1–V15 and legacy sharing_sessions/groups untouched. Store exact
actor/session/generation receipts, including revoke-before-start tombstones. Never resurrect
a revoked or expired generation after restart; new explicit generation required. One live
generation per session. Actor/fence serialization follows existing session journal locks.

Sharing transaction takes SHARE access fence, then actor claim FOR UPDATE, then consent rows.
An internal journal authorization helper uses this same SQL connection: typed CHANNEL first,
finite lease reconciliation, current original claim and SQL ACL. No nested transaction,
network call or DIRECT_CALL pair lock under an actor lock. Journal terminal persistence
revokes exact-session consent in the same transaction on disconnect, replacement, timeout
or ACL loss; explicit hide revokes sharing only and leaves voice claim/membership untouched.

Actual disposable PostgreSQL tests: active original session and immutable replay, membership
without active session, wrong actor/session, revoke-before-start/restart, hide/new generation
and old revoke isolation, exact deadline expiry, original disconnect/new session isolation,
ACL loss and typed call exclusion. Use fresh artifact hashes/exact PG XML counts. Full default
backend plus Android suite before commit; update exact migration-count assertions to 16.
Parent review only, no deployment/native/coordinate/audibility claims.
