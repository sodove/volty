# Nearby authoritative roster journal

Continuation of backend lifecycle Task4b. Primary implements complex transactions;
only bounded Luna/xhigh reviews. No Astra or production rollout. Existing approved
voice-spaces design remains the product specification.

## Contract

A full roster snapshot comes from PostgreSQL, never an SDK participant count.
Membership, registered admission and confirmed SFU connection remain separate.
Pending/registered entries must not imply microphone capture or audible playback.
Each visible mutation advances a persisted revision and appends an outbox record
in the same transaction. Replay and cleanup-only acknowledgements do not invent
another roster mutation. Restart does not reset revision or erase undelivered work.

The current injected membership resolver is a foundation port, not sufficient
production authorization: removal can race the preflight lookup. Community wiring
must check current membership/ban/channel generation within the transaction that
authorizes admission and credential issuance. Keep production sessions disabled
until this atomic integration exists; do not enable using a legacy preflight shim.

## Task1: Additive persisted revision and outbox

Files: V3 migration, SchemaMigrator default list, journal integration/tests.

- Count migrations first: V1 legacy, V2 operation journal. Add V3; preserve legacy
  accounts/groups/friends and existing operation/cleanup state.
- Persist domain revision and durable outbox. Session phase/deadline/destination/
  identity changes must produce a revision in their existing SQL transaction.
  Flags used only for admission/cleanup retry are not a visible roster mutation.
- Use a consistent lock order: actor locks before revision lock; never call SFU
  under those locks. Outbox records contain resource/session identifiers, no JWT,
  coordinates or authorization granted to readers.
- Actual PostgreSQL tests: unchanged replay, real mutation, transaction rollback,
  competing actors, restart replay, ordered cursor and migration from V2.
  Update exact migration-count expectations deliberately.

## Task2: Snapshot and authorized HTTP boundary

Files: RosterRepository, DTOs, routes and PostgreSQL/HTTP tests.

- Read revision and roster rows in one repeatable snapshot. Join exact current
  claims so one account cannot appear twice. Include session identity and lease
  deadline, not permanent membership count. Pending differs from registered.
- Server time and lease deadlines remain explicit; clock passage cannot silently
  alter the payload while reusing a revision. Expiry is a persisted lifecycle
  change; local clients stop honoring an expired lease even before delivery.
- Roster access requires the same channel membership boundary; unauthorized or
  unknown channels do not reveal roster/room existence. Default dependency null.
- Test concurrent join/stop/snapshot, duplicate snapshots, old-session cleanup and
  actor in another channel. No SDK/media claims from SQL admission alone.

## Task3: Authenticated media observations

Files: WebhookVerifier, media observation journal/coordinator, route/tests.

- Inspect the pinned io.livekit server SDK before using WebhookReceiver. Receive
  original bytes/text and Authorization; verify signature/body digest, never skip
  authorization. Bound body size before allocating the whole request.
- Deduplicate eventId durably; resolve immutable session identity and room epoch.
  Participant joined confirms transport only. Left/room-close for an old session
  cannot remove a newer claim. Terminal state cannot resurrect on delayed joined.
- Missing hooks are reconciled from authenticated SFU state plus leases. Record
  what is observed; no device audibility, microphone or routing inference.
- Actual signed/invalid/tampered-body HTTP checks and PostgreSQL event replay/
  reordered delivery tests. Test provenance on intentionally incoherent fixtures.

## Task4: Delivery cursor and reconnect

- Build durable outbox cursor consumption with complete authorized snapshots.
  Register subscription before initial read; buffer then discard revisions no
  newer than initial snapshot. Repeated delivery is safe.
- Consumer error/restart must not lose pending revisions; never claim an in-memory
  hub is sufficient for multiple backend processes.
- Fresh backend/Android checks before commit; record counts and remaining limits.

After this foundation, implement communities/catalog/channel ownership and its
atomic membership integration, then client/UI cutover and direct-call signaling.
