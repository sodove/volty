# Exact channel deletion evidence

Owner deletion tombstones only the specified active channel, including default or
last. Server/membership survive; no replacement/default promotion/voice move.
UPDATE access fence precedes every sorted affected actor lock and revision write.
Exact journal/claim/SID/epoch/room identity is required. V9 receipts retain original
actor/operation/community/channel/revision and immutable cleanup targets. Replay
after new voice never targets that voice; community deletion shares the namespace
and rejects conflicting channel receipts. Remote cleanup remains separate.

Ten actual-PG cases produced missing API compile RED before implementation.
Target PG10 PASS4673 runed92eb9e-1e45-4cbb-b90c-788ebf38811f, exactzero. Narrow Luna
review found a self-consistent journal/claim with foreign destination but deleted
channel room could be silently skipped. Added deliberately incoherent eleventh
fixture. Actual PG1 RED83525 run53c4383e-e567-42c4-80c6-2f26d0eca5f6 observed
unexpected Deleted(cleanupSessions=[]) instead of rollback. That skip assumption
is retracted in the source; helper now rejects matching target room on a foreign
destination. Review fix-loop passed the exact fixture path.

Final fresh PG26 PASS65078 run43f369ea-fec4-457a-ad4b-b05d35edf906: deletion11,
creation7 and community resource deletion8, exact zero failures/errors/skips.
Covers both actual issued-query SHARE/UPDATE orders, no credential producer after
deletion, empty-server/default policy, unrelated voice, replay after cleanup,
operation conflicts, tombstone admission, outbox rollback and incoherent claims.
All6 Gradle tasks executed; owned containers stoppedtrue for all three runs.
Schema remains V10. Fresh backend143 and Android2680 PASS5595, exactzero, all6/35
tasks executed with rerun-tasks and build/configuration caches disabled. Android
sources unchanged during the backend-only review fix. No mutation-sweep claim.

No production deployment or HTTP/client channel management is enabled. V2 sharing
revoke must join this deletion boundary before production removal enablement.
SDK/device audibility and Android retirement remain separate required work.
