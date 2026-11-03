package ru.sodovaya.volty.diagnostics

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DatabaseSnapshotPlanTest {
    private val mib = 1024L * 1024

    @Test
    fun refusesWhileRecording() {
        // Incoherent on purpose: unlimited space, still refused.
        assertEquals(
            SnapshotPlan.Refuse(SnapshotRefusal.RECORDING),
            databaseSnapshotPlan(recording = true, dbBytes = 1, freeBytes = Long.MAX_VALUE),
        )
    }

    @Test
    fun refusesWithoutSpaceForCopyPlusMargin() {
        assertEquals(
            SnapshotPlan.Refuse(SnapshotRefusal.NO_SPACE),
            databaseSnapshotPlan(false, 100 * mib, 100 * mib + 16 * mib - 1),
        )
        assertEquals(SnapshotPlan.Copy, databaseSnapshotPlan(false, 100 * mib, 100 * mib + 16 * mib))
    }

    @Test
    fun fileNameIsTimestampedAndMarksCompression() {
        assertEquals("volty-db-2026-10-29_07-05-09.db", databaseSnapshotFileName(2026, 10, 29, 7, 5, 9, compressed = false))
        assertEquals("volty-db-2026-10-29_07-05-09.db.gz", databaseSnapshotFileName(2026, 10, 29, 7, 5, 9, compressed = true))
        assertEquals(false, shouldCompressSnapshot(SNAPSHOT_COMPRESS_FROM_BYTES - 1))
        assertEquals(true, shouldCompressSnapshot(SNAPSHOT_COMPRESS_FROM_BYTES))
    }

    @Test
    fun walTravelsWithTheDbUnlessTheCheckpointEmptiedIt() {
        assertEquals(SnapshotFiles.DbOnly, snapshotFilesDecision(checkpointBusy = false, walBytes = 0))
        assertEquals(SnapshotFiles.DbOnly, snapshotFilesDecision(checkpointBusy = true, walBytes = 0))
        assertEquals(SnapshotFiles.DbOnly, snapshotFilesDecision(checkpointBusy = null, walBytes = 0))
        assertEquals(SnapshotFiles.DbAndWal, snapshotFilesDecision(checkpointBusy = true, walBytes = 4096))
        assertEquals(SnapshotFiles.DbAndWal, snapshotFilesDecision(checkpointBusy = null, walBytes = 4096))
        // Incoherent: "checkpoint done" yet a WAL remains. No producer emits it; it must be refused.
        assertEquals(
            SnapshotFiles.Refuse(SnapshotRefusal.INCONSISTENT),
            snapshotFilesDecision(checkpointBusy = false, walBytes = 4096),
        )
    }

    @Test
    fun recordingSignalCoversRideAndRouteButNotAnIdleStatus() {
        val idle = ru.sodovaya.volty.domain.history.HistoryRecordingStatus()
        assertEquals(false, historyRecordingInProgress(idle))
        assertEquals(false, historyRecordingInProgress(idle.copy(route = ru.sodovaya.volty.domain.history.HistoryRecordingRouteStatus.UNAVAILABLE)))
        for (r in listOf(
            ru.sodovaya.volty.domain.history.HistoryRecordingRouteStatus.STARTING,
            ru.sodovaya.volty.domain.history.HistoryRecordingRouteStatus.RECORDING,
            ru.sodovaya.volty.domain.history.HistoryRecordingRouteStatus.STOPPING,
        )) assertEquals(true, historyRecordingInProgress(idle.copy(route = r)), r.name)
    }

    @Test
    fun zipNameWhenWalTravels() {
        assertEquals("volty-db-2026-10-29_07-05-09.zip", databaseSnapshotFileName(2026, 10, 29, 7, 5, 9, compressed = true, zipped = true))
    }

    @Test
    fun anUnopenedShareShipsEveryCompanionThatExists() {
        // After a killed migration: a hot journal beside a torn main file. Without the journal the copy is unusable (I1).
        assertEquals(listOf("-journal"), unopenedSnapshotCompanions { if (it == "-journal") 546_906_504L else null })
        // Incoherent on purpose: a rollback journal AND a WAL pair (no producer leaves both); all of them travel.
        assertEquals(listOf("-journal", "-wal", "-shm"), unopenedSnapshotCompanions { 4096L })
        // "Exists" is the rule, not "non-empty": an empty -wal or a truncated journal costs nothing to ship.
        assertEquals(listOf("-wal", "-shm"), unopenedSnapshotCompanions { if (it == "-journal") null else 0L })
        assertEquals(emptyList(), unopenedSnapshotCompanions { null })
    }

    private class FakeExporter : DatabaseSnapshotExporter {
        val calls = mutableListOf<Boolean>()
        override suspend fun share(isRecording: suspend () -> Boolean): DatabaseShareOutcome {
            calls += isRecording()
            return DatabaseShareOutcome.Shared
        }
    }

    @Test
    fun shareActionDelegatesExactlyOnceWithTheRecordingFact() = runTest {
        val exporter = FakeExporter()
        DatabaseShareAction(exporter) { true }.run()
        assertEquals(listOf(true), exporter.calls)
    }
}
