# Nearby account deletion evidence

The existing JdbcStore.deleteAccount path now uses the UPDATE access-fenced
repository. All affected actor locks are acquired in sorted order before any
roster revision write. The deleted actor's exact current claim closes regardless
of destination; other claims close only inside owned communities. Incoherent
claim/operation/epoch identity rolls back instead of guessing a target.

Owned communities/channels become tombstones; historical owner membership remains
for the deferred FK. Membership elsewhere, legacy position, legacy groups and
refresh tokens are revoked in the same transaction as the user tombstone and
community outbox. Durable cleanup flags remain independent of terminal intent.
No SFU/network call occurs under these SQL locks. Repeated deletion returns0
without targeting new work or inventing revisions. No schema migration was added.

Six real PostgreSQL tests were authored before the repository API; missing API
compile RED observed. The existing-store integration case then failed on actual
PostgreSQL in runff5100c6-703f-4628-a480-4cbfc349b1ad: delete left currentClaim
nonnull. After wiring, run1847f5db-adf8-4da3-80ca-01132550a126 ran6 with one failure:
the fixture's deadline30100ms was already expired against JdbcStore's actual epoch
clock, so policy correctly preserved EXPIRED instead of expected CANCELLED. That
expectation is retracted in the test, with explicit stale deadline/prestate proof.
The separately clocked live SHARE-first case asserts CANCELLED plus cleanup.

Final disposable PostgreSQL run6b75601a-e380-45c6-a198-6eb122d284ae: exact6 tests,
zero failures/errors/skips. Includes issued-query SHARE/UPDATE barriers in both
orders, post-delete credential producer not called, unrelated peer preservation,
final-outbox rollback, replay and incoherent claims. All6 Gradle tasks executed;
owned container stoppedtrue for all three runs. Production database untouched.

Fresh full Android2680 PASS38584, exact zero failures/errors/skips, all35 tasks
executed. Fresh backend143 passed, exact zero failures/errors/skips, all6 tasks
executed. Both used rerun-tasks and disabled build/configuration caches. Previous
PostgreSQL131 baseline was not rerun here; the fresh PG claim is these6 tests,
not a combined137-test run or mutation sweep.

Narrow Luna source review passed lock order, exact target selection, rollback,
actual JdbcStore delegation and the clock-based fixture retraction. Production
v2 sharing, final legacy cutover, Android/account runtime retirement and device
audibility remain pending. This change was not deployed.
