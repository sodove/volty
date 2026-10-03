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

Execution slices:3a live gate-owned SFU observation (complete e2502b34);
3b signed bounded receiver and durable supported-event inbox;3c actor-locked
media observation versions, reconciliation and connected-only renewal;3d
room-finished handling plus remaining-grant client/server adaptation. These
slices do not individually complete Task3 or enable production.

- Inspect the pinned io.livekit server SDK before using WebhookReceiver. Receive
  original bytes/text and Authorization; verify signature/body digest, never skip
  authorization. Bound body size before allocating the whole request.
- Deduplicate eventId durably; resolve immutable session identity and room epoch.
  Participant joined confirms transport only. Left/room-close for an old session
  cannot remove a newer claim. Terminal state cannot resurrect on delayed joined.
- Missing hooks are reconciled from authenticated SFU state plus leases. Record
  what is observed; no device audibility, microphone or routing inference.
- Registered admission without observed transport must not renew indefinitely:
  heartbeat cannot extend the initial30s window while media is unconfirmed.
  Missing joined hooks may be resolved by an authenticated, incarnation-fenced
  current participant observation. Keep SFU network work outside SQL locks.
- Renew must validate the expected current participant SID and ACTIVE state
  within the SFU entry lock that extends the lease. A backend observation followed
  by an unconstrained renewal is insufficient: a new unconfirmed SID can replace
  the observed transport between those calls. Unsupported conditional renewal
  fails closed; no fallback to the generic prototype admin renewal.
- Persist an observation version under the actor lock. A network lookup uses its
  captured version; a response that loses to a newer observation retries rather
  than overwriting that SID/state. Snapshot media changes share the roster
  revision/outbox transaction. Repeated identical media observations must not
  invent visible revisions; revision protects payload, observation version
  protects concurrent reconciliation. Unknown current media cannot extend its
  admission window merely because an older stored observation was ACTIVE.
- Same logical session may reconnect with a new participant SID. A delayed left
  hook for the prior SID must not end the new transport; when hooks are missing,
  reconcile current SFU SID instead of choosing the newest event timestamp.
- Participant credentials/heartbeat responses need explicit remaining lease at
  server authorization time. The client SessionPort must derive a conservative
  monotonic deadline from request-start, not phone wall time or a fresh fixed30s
  credit on every replay/status ACK. The current client core's fixed window is
  a foundation requiring this adaptation before production integration.
- Actual signed/invalid/tampered-body HTTP checks and PostgreSQL event replay/
  reordered delivery tests. Test provenance on intentionally incoherent fixtures.

### Room-finished durable targets (Task3d)

Pinned LiveKit1.13.5 `pkg/telemetry/events.go` RoomEnded sends an event with
Room and no Participant. Therefore participant identity/SID is optional only
for ROOM_FINISHED, never synthesized from client data. The signed room name is
the immutable room epoch; room-finished is a reconciliation hint, not a blanket
stop command. A room can have newer transports by the time the event arrives.

- Count V1..V5 and add V6. Expand the supported kind constraint and allow a
  null session/SID pair only for ROOM_FINISHED. Keep JOINED/LEFT pairs required.
- Persist the parent event and immutable per-session targets atomically on first
  receipt. ROOM_FINISHED captures exact current claim/session/room/node joins
  at receipt; participant events have one target even if its session is unknown.
  Duplicate matching event ID/digest never adds later joins to its target set.
  Conflicting digest changes nothing. Zero-target room events are already done.
- Backfill targets for existing unprocessed V4/V5 participant events; preserve
  processed parents, sequence ordering and hashes. No SQL network callbacks.
- Pending work is ordered by parent sequence and target identity. Complete one
  exact parent ID/digest/target only after durable reconciliation; finish parent
  when all its targets are done. Lock the parent row before target completion
  and the all-targets check: concurrent ACKs for different children otherwise
  can both observe the other's uncommitted child and leave an unfinished parent
  with no pending targets. Acknowledging one peer cannot consume another.
- Reuse current-SFU refresh without renewal/JWT. Terminal/foreign-room targets
  cannot touch replacements. A delayed room event with current live ACTIVE SID
  preserves it; failed lookup retains that target. New joins after receipt are
  reconciled by heartbeat/later events, not added by replay of an old room event.
- Tests: signed real-shaped room event without participant, malformed paired
  provenance, migration backfill, zero/multiple targets, duplicate receipt after
  new join, exact-target ACK, lost ACK replay, transient observation failure and
  delayed room event preserving new current media. Run fresh backend, exact
  PostgreSQL counts and Android before commit.

## Task4: Delivery cursor and reconnect

- Client pure replica slice may run while independent backend integration tests
  run. Tag every roster subscription with channel and its own generation. A full
  snapshot replaces rows only for that tag and a newer revision; same-revision
  replay is harmless only when peer content agrees (row order is irrelevant).
  Reject duplicate actors/sessions or CONNECTED without confirmed SID; request
  resync without presenting last-known rows as a current count. Old-channel and
  retired-subscription replies cannot clear another subscription. This slice
  does not implement transport, authorization or local peer lease expiry yet.
- Build durable outbox cursor consumption with complete authorized snapshots.
  Register subscription before initial read; buffer then discard revisions no
  newer than initial snapshot. Repeated delivery is safe.
- Consumer error/restart must not lose pending revisions; never claim an in-memory
  hub is sufficient for multiple backend processes.
- Fresh backend/Android checks before commit; record counts and remaining limits.

After this foundation, implement communities/catalog/channel ownership and its
atomic membership integration, then client/UI cutover and direct-call signaling.
