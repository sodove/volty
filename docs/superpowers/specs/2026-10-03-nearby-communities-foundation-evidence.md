# Nearby communities foundation

V7 follows counted V1..V6. It adds permanent communities/membership, channels,
bans, an access-fence row and community outbox sharing the persisted revision
clock. It does not alter old migration files or drop legacy groups/friends/
invitations/sharing. Legacy groups become private communities with the same ID
and a deterministic default channel named Общий. Missing recorded-owner
membership is repaired in the new table; original legacy membership is retained.
Owner membership is a deferred database foreign key, with no second mutable
owner role that could disagree with owner_id.

Repository foundation implements private-by-default atomic create, authenticated
member detail, stable bounded membership/catalog paging and explicit public join.
Create/member/default channel/common revision/outbox commit together. Replays
use immutable creator and original request hash; payload conflicts cannot edit a
server or mint a revision. Joining membership does not acquire/replace a voice
claim or publish location. Catalog excludes private/deleted/banned communities
and treats percent/underscore in search literally. Member-only channels are read
with revision and authorization on the same repeatable-read connection.

Validation so far:

- Migration runtime RED4/4 absent new tables, run
  `4549caa3-deec-4ded-ae24-7743e97410ff`, followed by actual PostgreSQL4 PASS,
  run `2c778207-de44-4811-8f28-db1e3b202cfb`.
- Tests cover populated V1 preservation, repeat startup/stable channel, missing
  legacy owner membership, deferred commit rejection and rollback of new schema/
  backfill when a later migration fails. Retrying migration restores all rows.
- Repository missing-API compile RED, actual PostgreSQL7 PASS, run
  `2a578231-7c42-4550-ad16-f33903d2220d`. Includes forced outbox constraint
  failure rolling back resource/member/channel/revision, catalog privacy, create
  conflict/replay and public join preserving an unrelated voice claim.
- Fresh backend123 and Android2584 PASS after the implementation. Full actual
  PostgreSQL103 PASS, zero failures/errors/skips, run
  `8933d014-c2d2-4df0-bdb4-c54f5fa9a5c2`; owned pool/fixture cleanup confirmed.
  Totals include previous delivery review fix and new migration/repository cases,
  with final membership pagination source. Fresh full Android2595 also PASS,
  including eleven independent client HTTP boundary tests.
- Narrow Luna source review PASS only catalog filters/literal search and
  member-detail ACL/connection reuse. Earlier plan-only review checked legacy
  preservation and no consent carry-over, not code or transactions.

No production route/session wiring, invites/moderation/owner transfer/leave or
deletion is claimed by this foundation. The persisted access fence is not yet
used by session transactions; it cannot be called an atomic admission permission
boundary until Task3 integration. New membership-removal writes are deliberately
absent until exact claim cleanup can commit with them. Old account deletion must
also acquire that boundary before enablement. V7 does not continuously mirror
new legacy groups: final cutover must quiesce old writers and reconcile late
groups. No device, audio route, Xiaomi or audibility verification occurred.
