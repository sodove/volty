# No legacy group transfer evidence

User scope changed 2026-10-04: preserve accounts/friends, create new servers,
defer detailed UI plans/mockups until assembly. Spec and community plan retain
retractions of the former group-transfer/final-reconciliation requirements.

Historical V7 is unchanged. prepareBackendSchema migrates, then retires its active
null-create-hash imports before AppDependencies.create constructs HTTP dependencies.
Native v2 creation always stores a hash and is preserved, including matching names.
Retirement uses UPDATE access fence and sorted actor locks before revisions;
exact imported current claims terminalize with durable cleanup. Resource tombstones
retain owner membership for the deferred FK. Accounts, friendships and legacy
groups/sharing/location tables are untouched. No final group backfill is performed.

Five real-PG cases produced missing-API compile RED before implementation.
Final run1f1f39a1-6c9e-426d-a3ec-9476a214f183 PASS21963: exact5, zero failures,
errors/skips, owned container stoppedtrue. Covers V1 bootstrap preservation and
absence of active imports, native server preservation/old-ID conflict, exact claim
cleanup/unrelated native voice, outbox rollback and late legacy groups/repeated
startup. Fresh backend143 PASS54017 and Android2680 PASS51255, exactzero; all6/35
tasks executed with rerun-tasks and build/configuration caches disabled.

Narrow Luna source review passed origin selection, native preservation, lock order
and actual bootstrap wiring. These are ordinary tests, not a mutation sweep or
fresh full-PG run. No production deployment, Android audibility or UI claim.
Legacy live-session termination and write-upgrade boundary remain rollout work;
old data surviving in legacy tables is not transfer into active new servers.
