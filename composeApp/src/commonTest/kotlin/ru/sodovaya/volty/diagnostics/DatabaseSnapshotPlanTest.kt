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

    private class FakeExporter : DatabaseSnapshotExporter {
        val calls = mutableListOf<Boolean>()
        override suspend fun share(recording: Boolean): DatabaseShareOutcome {
            calls += recording
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
