# Nearby room-finished durable reconciliation targets

Pinned LiveKit1.13.5 `pkg/telemetry/events.go` RoomEnded emits a webhook with
Room and no Participant. The verified model now supports ROOM_FINISHED with
null session/SID, retaining required canonical UUID/SID for JOINED/LEFT. A room
event with nonempty participant identity or SID is rejected. Original-byte
signature/digest, raw Authorization JWT, strict UTF8 and64KiB bounds remain.

V6 counts the prior five migrations, adds nullable room-event provenance and a
required participant pair constraint, then backfills unprocessed participant
events into durable per-session targets. Existing processed receipts stay done.
First receipt commits parent and targets together. Room targets capture exact
current actor claim/operation/session/room/node joins. Matching replay cannot
add later joins; conflicting hashes change nothing; zero-target rooms are done.
Receipt does not alter roster, extend admission or mint credentials.

Completion matches event ID, digest and target session, and locks the parent
before completing a child/checking remaining children. This prevents two
concurrent child ACKs from leaving an unfinished parent with no pending target.
Worker reconciles the captured session against current live SFU state. A room
event never commands blind stop and cannot revoke a replacement claim.

Evidence: initial missing-API compile RED; fresh backend113 PASS, including two
verifier cases and room-event HTTP durability-before204. Targeted actual
PostgreSQL7 PASS e6df4fc6-074c-4e80-84af-9ca4e03097e3, zero errors/skips and
owned fixture cleanup: captured exact claims, late joins/replay, zero targets,
target constraint failure rolling back parent, issued competing real parent-lock
queries for different ACKs, incoherent node epoch/null SID, and V5 backfill.
Full actual PostgreSQL87 run29db724a-d29d-4305-bbfb-f13bba9c3278 initially
failed one historical V4 migration fixture: it called the current V6 inbox
producer before the target table existed. The fixture now seeds the historical
V4 receipt directly with SQL, retains all preservation assertions and adds an
exact migrated-target assertion. No production compatibility fallback was added.
Targeted media10 PASS 2d4d8ada-c9ff-4681-8b40-61d5b7e28591; corrected full
PostgreSQL87 PASS fedf8de9-40b5-4ae0-b38a-2a141f779169, zero failures/errors/
skips and owned pool/fixture cleanup confirmed. This includes the two new room
worker cases: delayed close preserves current new SID/session; timeout retains
intent and lost ACK replays committed SID/version without another visible
revision or JWT. Fresh backend113 and Android2584 PASS (Android includes the
separate five-test client replica slice).

Narrow Luna source review PASS only for verifier signature/provenance branches;
reviewer ran no builds/tests and did not review SQL/worker or all Nearby.
Primary owns transaction review. Scheduling/backoff, production authorization,
actual SFU webhook-to-backend deployment, roster delivery and client/UI cutover
are pending; production dependencies remain null. No phone audio claim.
