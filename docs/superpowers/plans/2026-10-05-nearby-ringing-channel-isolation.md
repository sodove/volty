# Preserve ringing ownership against stale channel requests

Prerequisite f1e2fa15. A RING caller operation is permanently bound to a call, but no
session-journal row exists until acceptance. Channel Service.join must consult this
binding before room resolution/SFU, including declined/terminal ring history. Return
operation conflict without touching ring/reservation or inventing a channel tombstone.

Preflight alone cannot guard a binding committed during channel room resolution. JDBC
autonomous invalidation must re-read actor ownership under its single-actor lock and
reject newly discovered call bindings without mutation. Known original CALL media loss
still delegates to accepted-pair owner; explicit generic caller cancel still ends rings.
Channel membership denial handles missing-state conflict without checkNotNull failure.

Tests before source: live ring with allowed and denied channel resolver, terminal ring
nonce before room/SFU, deterministic creation inside room resolver after initial missing
binding before null-room denial. Assert persisted ring/claims/no media/network, not timing.
Full exact fresh PG/backend/Android suites before commit. No call route/native activation.
