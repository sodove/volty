# Nearby communities and atomic access

Continue the approved voice-spaces specification after roster foundation
`5c48d752`. Primary implements transactions; only simple scoped Luna/xhigh
reviews, no additional Astra. Read the execution ledger before replaying tasks.
No production deployment or enabled session dependencies in this slice.

## Task1: Additive schema and migration

- Count existing V1..V6, add V7 and deliberately update migration expectations.
- Communities carry immutable ID, owner, PRIVATE/PUBLIC visibility, name,
  description, creation time and ACTIVE/DELETED state. Channels carry immutable
  ID/parent and ACTIVE/DELETED state. Permanent membership is its own table;
  bans and invitations are separate from voice sessions.
- Backfill every legacy group as PRIVATE with the same ID/name/owner/time and
  one deterministic default channel. Preserve original groups/friends/sharing
  tables. Copy memberships, ensure the recorded owner has membership, preserve
  join times. The owner/membership relationship is a deferred foreign key so
  create and explicit transfer can commit atomically; role is derived from owner.
- A common persisted revision clock also sequences community outbox changes.
  Community events contain resource identifiers/kind, no coordinates or tokens.
  Do not fabricate a session/outbox row for a membership change.
- Migration is data/schema preparation, not cutover. No active sharing consent
  is copied into channels. Existing invitation records remain intact until the
  explicitly designed invitation compatibility step.
- Actual PostgreSQL tests: populated V1 to V7, repeat migration, preservation,
  missing legacy owner membership repair, stable default channel, deferred owner
  invariant and transaction rollback. Never edit old migrations.

## Task2: Repository and product operations

- Atomic create defaults PRIVATE and creates owner membership/default channel.
  Idempotent create uses caller-supplied UUID: exact actor/payload replay returns
  current resource without a second revision; conflict cannot mutate another.
- Member detail includes channels; outsiders can discover only PUBLIC summaries.
  Catalog is bounded, stable pagination/search, excludes private/deleted/banned
  servers and never includes private channels/membership/positions. Public join
  is explicit/idempotent, checks ban/current visibility within mutation lock.
- Owner-only edit/channel create/delete/moderation. Member leave is independent
  of voice disconnect. Owner leave with others requires an explicitly selected
  current non-banned successor; owner transfer plus leave is one transaction.
  Last owner deletes the server explicitly. No implicit arbitrary successor.
- Soft deletion preserves identifiers for delayed request fencing. Default channel
  cannot silently become another channel. A channel deletion/ban/member leave
  also terminalizes affected exact claims and revokes sharing in the same SQL
  transaction; committed cleanup is retried outside locks.
- Do not expose membership-removal writes before this cleanup integration exists.
  Foundation reads/create/join may be tested while routes remain disabled.

### Durable departure replay refinement

Network loss after committed leave must not turn retry into an ambiguous denied
request, and replay after rejoining must not leave the new membership again.
Add counted V8 (V1..V7 now implemented), not an edit to V7: actor/operation-owned
departure receipt stores immutable community/successor/revision and exact cleanup
session targets. The caller keeps operation UUID across retries. Same payload
returns the same committed effect; conflicting payload fails without mutation.
Do not retarget a replay to the current voice claim or a new channel membership.

Departure transaction holds UPDATE access fence, checks current active member/
owner/successor, locks affected actor claims in sorted order, terminalizes only
current exact claims within that community, transfers ownership if explicitly
requested, removes membership, revokes matching legacy sharing/position records,
and appends community mutation/receipt/targets atomically. No target exists when
departing voice was in another community. Owner membership foreign key commits
only with an eligible successor; last owner receives an explicit delete-required
result. A missing/denied request does not leak private resource state.

Return exact durable cleanup targets for network work outside the transaction.
SQL membership removal is not proof that SFU media has stopped: the eventual
HTTP service must report cleanup pending until strict SFU acknowledgement, with
durable retry. Production v2 sharing is not implemented yet and must join this
transaction before any public removal endpoint is enabled. Tests cover outbox
rollback of membership/claim/ownership/receipt, lost-response replay after rejoin,
explicit owner successor, last-owner rejection, unrelated voice preservation,
and separate voice disconnect retaining permanent membership.

## Task3: Atomic session/snapshot authorization

- Preflight resolveRoom alone is insufficient. Use a persisted singleton access
  fence: normal access-sensitive session transactions hold SHARE before actor
  locks; ACL mutation holds UPDATE before sorted affected actor locks. This
  deliberately serializes ACL writes across servers, while normal sessions can
  hold SHARE concurrently. It is a conservative initial scalability tradeoff,
  with finite SQL deadlines, not network work under SQL locks.
- Lock order: access fence, sorted actor claims, resource/membership rows, common
  revision clock. No path may acquire the fence after an actor lock. Inspect all
  existing session mutations and add the same order before enabling the adapter.
- Validate membership, ban, community/channel state and immutable destination
  inside begin/admit/renew/credential transactions. Denied replacement must not
  stop an unrelated current claim. Terminal cleanup remains possible after access
  is removed. Direct-call access will later supply its own accepted-call boundary.
- Make access denial a distinct repository rejection before beginning/replacing
  a claim. Do not dereference a missing state in SessionService. Existing denied
  pending/active operations are terminalized with durable cleanup; cancellation
  and cleanup remain usable after membership loss. Include post-network admit,
  observation/version commit, connected renewal receipt and CPU JWT producer.
  A register response delayed until after removal cannot restore the operation.
- The existing account deletion path currently writes users.deleted_at without
  the access fence. Integrate it into the same transaction/lock order and cleanup
  before production enablement; otherwise a JWT check can race account deletion.
  Account deletion closes owned communities and affected current sessions, while
  membership-only departure never closes an unrelated community/session.
- Authorized roster snapshots read ACL, revision and peers in one repeatable-read
  view, never authorize on another connection. Revoked access returns no rows.
- Actual competing-query barriers prove join/JWT vs removal serialization,
  rollback of cleanup/ownership/membership/outbox, old request fencing, owner
  transfers racing leave, and delayed media cleanup preserving replacement.

## Task4: HTTP/realtime/legacy cutover

- Authenticated `/v2/nearby/communities` catalog/create/detail/join/leave/channel/
  moderation endpoints in separate route file. Bound input and return explicit
  conflicts/actions required; private unknown/denied does not reveal existence.
- Connect owned roster cursor transport with exact ACK after publication and
  cancellation on access denial. Durable community events trigger authorized
  refresh; do not leak raw events or claim an in-memory hub is durable authority.
- Legacy groups read map to migrated private/default channels where compatible.
  At actual cutover legacy voice/sharing writes return client_upgrade_required;
  delayed v1 stop cannot affect v2 claims. Migration does not perform rollout.
- V7 is an initial backfill, not a live replication mechanism for legacy creates.
  At cutover quiesce old instances, perform an idempotent final reconciliation of
  groups created after initial migration, then enable the compatibility write
  boundary. New legacy-compatible group creation must update both representations
  atomically or require an upgrade. Do not silently orphan groups written by a
  still-running old instance after V7.
- Client communities repository/components/UI and real voice adapter follow this
  backend contract. Public join never captures microphone or shares location.

Run meaningful fresh backend checks and exact actual PostgreSQL counts for each
slice; Android suite before every commit; diff check. Record narrower verification
honestly. PostgreSQL green does not prove Android routes, Xiaomi or audibility.
