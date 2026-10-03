# Nearby authoritative snapshot reader

2026-10-03, roster journal Task2 foundation. Production roster/session ports remain
disabled until atomic community authorization and SFU integration exist.

JdbcRosterRepository reads revision and peers on one read-only repeatable-read
transaction, with finite5s statement/15s idle limits. Exact actor/operation/session/
room/node claim matching excludes obsolete or incoherent ownership. Current claims
represent sessions, not all server members. PENDING maps JOINING; registered ACTIVE
maps CONNECTING with media UNKNOWN. No SDK count, microphone or audibility inference.

Lease deadlines are explicit. Advancing the clock alone does not silently mutate
roster rows/revision; expiry is a lifecycle transaction. Snapshot server time is
observation metadata for interpreting the deadline, not a domain revision. Clients
must stop honoring expired leases, even before the expiry event arrives.

Five targeted actual PostgreSQL tests passed, run09b8483b-3fb4-46c3-8efc-6a8bf854f16d:
pending/registered semantics, replacement/cleanup, concurrent snapshot, expiry and
deliberately incoherent ACTIVE operation with older-node claim. The barrier pauses
after the actual revision SELECT, then a competing join commits. The first read
still returns revision1/one peer; a subsequent read returns revision2/two peers.
Assertions prove the queries/mutation were issued; no timing-only pass.

HTTP boundary authenticates the account and channel UUID, returns no-store, and
requires an authorized snapshot port. Unknown/forbidden channels return the same
membership denial. A wrong-channel injected snapshot initially produced200 (RED);
the route now rejects it with503 before serializing any private peer. Five HTTP
checks passed as part of fresh backend unit90. Actual membership/ban/channel
generation validation is the future production port's transaction-level duty;
no preflight shim was enabled or claimed sufficient.

Final full actual PostgreSQL48 passed, runa1f591f7-942f-43d3-9490-ab26d2c0195d:
21 repository,16 coordinator/endpoint,6 journal,5 snapshot. Zero failures/errors/
skips; fixture cleanup confirmed. Fresh Android2573 passed. Bounded Luna review
passed reader isolation/claim predicates/channel guard; membership integration
was explicitly outside its scope and no tests were run by the reviewer.

Test-only JDBC pooling reuses sockets while preserving independent actual
transactions, dirty schema/autocommit reset and explicit shutdown. Before snapshot
work, all43 existing PostgreSQL tests passed with pooling, including lock barriers/
timeouts/rollback/private schema migrations. Fresh runtime2m57s versus6m37s without
pooling; both were uncached actual database runs. Pool shutdown confirmed active0
and total0, then the owned disposable fixture was removed. No production connection
or database was used. Missing reader/boundary compilation REDs are preserved.

Media observations, durable delivery cursor and client SessionPort remain future
work. Before integration, unconfirmed media must not renew indefinitely, delayed
old participant-SID events must not end a resumed transport, and client deadlines
must use explicit remaining lease plus request-start monotonic time.
