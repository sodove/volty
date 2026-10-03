# Nearby atomic access and departure foundation

The new repositories can enforce channel access inside the session transaction
and leave a community without confusing permanent membership with voice intent.
This is foundation code; production DI/routes remain disabled.

- Optional `SessionAccessPolicy` in the isolated session prototype becomes
  `JdbcChannelAccess` for community-backed sessions. It checks active account,
  community, channel, exact room binding, membership and ban in the same SQL
  transaction. Public catalog visibility does not grant voice admission.
- Guarded session transactions acquire the SHARE access fence before actor locks.
  ACL writes acquire UPDATE before actor locks. Denied new replacement preserves
  an unrelated current claim; denied existing admission/observation/renewal/JWT
  work terminalizes its exact operation and retains durable cleanup. No SFU
  network call is made while holding the SQL transaction.
- Authorized roster reads pin revision, access and peers on one repeatable-read
  connection. A snapshot pinned before removal may show that coherent old view;
  the next snapshot is denied. This is not instant push-delivery proof.
- Counted additive V8 stores actor/operation-owned departure receipts and immutable
  exact session targets. Matching replay after rejoin returns its old result;
  conflicting community/successor cannot mutate the new membership or claim.
- Departure cancels only the current exact claim in that community, revokes its
  legacy sharing/location, removes membership, and appends common revisions and
  receipt/targets atomically. An incoherent claim rolls back instead of guessing
  a target. An owner must select an eligible successor, or explicitly delete the
  server when alone. Explicit transfer also synchronizes legacy ownership.

Fresh checks (no build/configuration cache):

- Backend unit suite:123 tests, zero failures/errors/skips.
- Actual PostgreSQL full suite:123 tests, zero failures/errors/skips, run
  `97c595e9-dcd5-4b56-9477-1e7f53c7fac3`, final V8/access/departure source.
  This includes103 previous cases,11 access cases and9 departure cases.
  Gradle executed6 tasks in14m25s. Owned disposable PostgreSQL fixture and SSH
  tunnel were cleaned up; production database was not used.
- Earlier scoped access11 run `412ab42c-d424-48b0-9be8-6bd4f0cc08e8` and departure8
  run `0152345f-2089-4390-8509-59d3eb8c30d9` passed. The ninth departure case was
  added afterward and is covered by the full123, not by that earlier8.
- Access concurrency tests assert the competing prepared fence query was issued
  before releasing its blocker, in both lock orders. Departure rollback forces
  an outbox constraint failure; receipt replay fixture rejoins and starts a new
  voice session before replaying the old departure.
- Fresh Android2595 passed before the subsequent independent client-owner edits.
  Final fresh Android2604 then passed, including9 new client-owner tests, with
  exact count and zero failures/errors/skips before commit.
- Narrow Luna replay review covered only actor/operation/payload matching and
  immutable target replay; it was not a full transaction/lock review.

SQL departure is not an SFU revocation acknowledgement. Production v2 sharing
cleanup, account-deletion fence/cleanup, owner resource deletion, moderation,
invitations, HTTP services, realtime scheduling, Android/UI integration and the
legacy-write cutover/final catch-up remain required. No Xiaomi, microphone route,
playback or audibility claim follows from these checks. No mutation sweep was run.
