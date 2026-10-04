# Typed voice destinations before direct-call media

Prerequisite ff42c91f: durable ringing only. No accepted call writer or API is activated.
Session destination identity is (kind, UUID), never UUID alone. Introduce CHANNEL and
DIRECT_CALL kinds in the pure operation and durable session/outbox rows. Existing rows
backfill CHANNEL; cancel-before-join tombstones keep no destination/kind. The production
channel entry point remains channel-only and must reject cross-kind operation replay.

V13 is additive, retains media evidence/deadlines/claims/cleanup/outbox revisions, and
explicitly rejects null/unknown kinds on concrete destinations. Existing low-level SQL
fixtures creating channel operations retain CHANNEL defaults. New writers explicitly
persist kind. The durable event reader carries kind, without exposing new public events.

Channel roster and all community/channel-specific negative writers filter CHANNEL.
An account's own deletion still closes every owned voice target. Deleting a server or
channel cannot close another participant's colliding direct-call session. Channel access
cannot issue credentials from a DIRECT_CALL row, even with deliberately inconsistent
room metadata. Call authorization, accepted shared claims, SFU cleanup/credentials and
public endpoints remain the next step.

TDD: pure same-UUID cross-kind replay and kind-preserving cancellation; real PostgreSQL
deliberately colliding call/channel rows exercising roster, deletion/departure, account
cleanup and credential producer admission; migration preserving preexisting rows/outbox.
Missing API/runtime RED before changes. Fresh whole PostgreSQL, backend unit and Android
suites, exact XML counts, parent manual review before commit. No device/deployment claim.
