# Nearby client explicit leave/delete boundary

The typed removal adapter forwards caller-owned immutable community/operation/
successor to explicit POST leave or delete. It never retries, generates a new ID,
selects a successor or turns last-owner leave into deletion. Permanent membership
removal remains separate from disconnecting voice/trip while staying a member.

Owned HTTPS child client refuses redirects, uses an injected secure bearer and
limits the complete request to ten seconds and the body to16KiB. UTF8 is strict;
success requires the expected operation/community, nonnegative actual JSON long
revision and required actual JSON boolean cleanupPending. Results contain only
safe receipt fields or a typed failure. Owner-action conflicts remain explicit.

Eight missing-API cases first produced compile RED62111, then targeted8 PASS70299.
Added preconditions proving replacement-decoded invalidUTF8 would otherwise form
a valid receipt; final targeted8 PASS65230, exact zero failures/errors/skips.
Tests assert issued stable body/path/bearer, separate delete, safe owner failures,
foreign/missing/quoted fields, durable cleanup flag, response bounds, no redirect,
safe exceptions, cancellation after issue and finite timeout.

Fresh full Android2641 PASS94817, exact zero failures/errors/skips. All35 tasks
executed with rerun-tasks and build/configuration caches disabled. This is not
a mutation sweep. Backend143 and PostgreSQL131 evidence remain unchanged/scoped.

Narrow Luna source review passed stable IDs/payloads, absence of automatic delete/
retry/UUID generation, expected receipt identity, actual boolean and safe result
fields. That review did not run tests or validate the broader transport/runtime.

Receipt is SQL membership/deletion confirmation and the backend's cleanup status,
not proof of Android engine release or audible sound. UI confirmations, mutation
ownership, durable retries, production DI, account lifecycle integration, Xiaomi
and routing acceptance remain pending. Backend source/SQL unchanged in this slice.
