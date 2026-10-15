package ru.sodovaya.volty.presentation.history

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.Lifecycle
import com.arkivanov.essenty.lifecycle.destroy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*
import ru.sodovaya.volty.domain.history.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryDetailComponentTest {
    private val key = HistorySeriesKey("pack", "voltage_v")
    private val other = HistorySeriesKey("pack", "current_a")
    private val range = HistoryRange(1000, 5000)
    private fun ride(id: String) = HistoryRide(id, "bike", HistoryTick(1000, 10, 1), HistoryTick(5000, 4010, 1), HistoryRideStatus.COMPLETED)
    private open inner class Repo : HistoryReadRepository {
        val reads = mutableListOf<Triple<String, HistorySeriesKey, HistoryRange>>()
        override suspend fun ride(rideId: String): HistoryRide? = this@HistoryDetailComponentTest.ride(rideId)
        override suspend fun sources(rideId: String) = listOf(HistorySource("pack", HistorySourceKind.BATTERY, "Recorded pack"))
        override suspend fun series(rideId: String, key: HistorySeriesKey, range: HistoryRange, maxPoints: Int): HistorySeries {
            reads += Triple(rideId, key, range)
            return HistorySeries(key, listOf(HistorySample(HistoryTick(2000, 1010, 1), key, 42.0)), emptyList(), false)
        }
        override suspend fun stats(rideId: String, key: HistorySeriesKey, range: HistoryRange) = HistoryStats(42.0, 42.0, null, true)
        override suspend fun recordedKeys(rideId: String, sourceId: String, limit: Int): HistoryRecordedKeys = error("No eager metric metadata")
        override suspend fun rides(before: HistoryCursor?) = error("Detail never preloads catalog")
        override suspend fun gps(rideId: String, range: HistoryRange, after: HistoryCursor?) = error("No route preload in detail reader")
    }
    private fun TestScope.component(repo: Repo, lifecycle: LifecycleRegistry = LifecycleRegistry(Lifecycle.State.RESUMED)) =
        HistoryDetailComponent(DefaultComponentContext(lifecycle), backgroundScope, repo, { HistoryTick(9000, 8010, 1) })

    @Test fun delayedNonCancellableRideCannotReplaceNewSelection() = runTest {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val repo = object : Repo() {
            override suspend fun ride(rideId: String): HistoryRide? {
                if (rideId == "a") withContext(NonCancellable) { entered.complete(Unit); release.await() }
                return super.ride(rideId)
            }
        }
        val c = component(repo)
        try {
            c.openRide("a"); runCurrent(); assertTrue(entered.isCompleted)
            c.openRide("b"); runCurrent(); assertEquals("b", c.state.value.ride?.id)
            release.complete(Unit); runCurrent()
            assertEquals("b", c.state.value.ride?.id); assertFalse(c.state.value.loading)
        } finally {
            release.complete(Unit); c.close(); runCurrent()
        }
    }

    @Test fun destroyedLifecycleAndParentCancellationCannotPublishLateReads() = runTest {
        for (destroy in listOf(true, false)) {
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val repo = object : Repo() {
                override suspend fun ride(rideId: String): HistoryRide? = withContext(NonCancellable) {
                    entered.complete(Unit); release.await(); super.ride(rideId)
                }
            }
            val lifecycle = LifecycleRegistry(Lifecycle.State.RESUMED); val parent = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
            val c = HistoryDetailComponent(DefaultComponentContext(lifecycle), parent, repo, { HistoryTick(9000, 8010, 1) })
            try {
                c.openRide("a"); runCurrent(); assertTrue(entered.isCompleted)
                if (destroy) {
                    assertEquals(Lifecycle.State.RESUMED, lifecycle.state)
                    lifecycle.destroy()
                    assertEquals(Lifecycle.State.DESTROYED, lifecycle.state)
                } else parent.cancel()
                val before = c.state.value
                release.complete(Unit); runCurrent()
                assertEquals(before, c.state.value)
                c.openRide("b"); runCurrent(); assertEquals(before, c.state.value)
            } finally {
                release.complete(Unit); parent.cancel(); runCurrent()
            }
        }
    }

    @Test fun addingRecordedMetricReadsStorageOnceAndPreservesCanonicalStats() = runTest {
        val repo = Repo(); val c = component(repo)
        c.openRide("a"); runCurrent(); assertEquals(range, c.state.value.range)
        c.addMetric(key); c.addMetric(key); runCurrent()
        assertEquals(listOf(Triple("a", key, range)), repo.reads)
        assertEquals(listOf(key), c.state.value.selectedKeys)
        assertEquals(42.0, c.state.value.series[key]?.samples?.single()?.value)
        assertEquals(42.0, c.state.value.stats[key]?.maximum)
        assertTrue(c.state.value.loadingKeys.isEmpty())
        c.addMetric(HistorySeriesKey("foreign", "voltage_v")); c.addMetric(HistorySeriesKey("pack", "voltage_v", 0)); runCurrent()
        assertEquals(1, repo.reads.size)
    }

    @Test fun removedMetricAndRetiredRangeCannotResurrectOnLateCompletion() = runTest {
        val release = CompletableDeferred<Unit>(); val entered = CompletableDeferred<Unit>()
        val repo = object : Repo() {
            override suspend fun series(rideId: String, key: HistorySeriesKey, range: HistoryRange, maxPoints: Int): HistorySeries {
                if (range == this@HistoryDetailComponentTest.range) withContext(NonCancellable) { entered.complete(Unit); release.await() }
                return super.series(rideId, key, range, maxPoints)
            }
        }
        val c = component(repo)
        try {
            c.openRide("a"); runCurrent()
            c.addMetric(key); runCurrent(); assertTrue(entered.isCompleted)
            val narrowed = HistoryRange(2000, 3000); c.selectRange(narrowed); runCurrent()
            assertEquals(narrowed, repo.reads.single().third)
            c.removeMetric(key); release.complete(Unit); runCurrent()
            assertTrue(c.state.value.series.isEmpty()); assertTrue(c.state.value.stats.isEmpty())
            assertTrue(c.state.value.selectedKeys.isEmpty()); assertEquals(narrowed, c.state.value.range)
        } finally {
            release.complete(Unit); c.close(); runCurrent()
        }
    }

    @Test fun reorderRemoveAndRangeKeepCursorQualifiedWithoutRereadingUnchangedRange() = runTest {
        val repo = Repo(); val c = component(repo); c.openRide("a"); runCurrent()
        c.addMetric(key); c.addMetric(other); runCurrent(); c.selectTime(2500)
        c.moveMetric(other, -5); c.removeMetric(key)
        assertEquals(listOf(other), c.state.value.selectedKeys); assertEquals(2500L, c.state.value.cursorEpochMillis)
        c.selectRange(range); runCurrent(); assertEquals(2, repo.reads.size)
        c.selectRange(HistoryRange(3000, 4000)); runCurrent()
        assertNull(c.state.value.cursorEpochMillis); assertEquals(3, repo.reads.size)
        c.selectTime(9000); assertNull(c.state.value.cursorEpochMillis)
    }

    @Test fun missingRideAndStorageFailureHaveTypedErrorsAndRetryClearsOldData() = runTest {
        val repo = object : Repo() {
            override suspend fun ride(rideId: String): HistoryRide? = when(rideId) {
                "missing" -> null
                "broken" -> error("Private database exception")
                else -> super.ride(rideId)
            }
        }
        val c = component(repo); c.openRide("missing"); runCurrent()
        assertEquals(HistoryReadError.NOT_FOUND, c.state.value.error); assertFalse(c.state.value.loading)
        c.openRide("broken"); runCurrent(); assertEquals(HistoryReadError.STORAGE, c.state.value.error)
        c.openRide("a"); runCurrent(); assertNull(c.state.value.error); assertNotNull(c.state.value.ride)
    }

    @Test fun metricFailureIsIsolatedAndCanBeRetriedByRemovingAndAdding() = runTest {
        var fail = true
        val repo = object : Repo() {
            override suspend fun stats(rideId: String, key: HistorySeriesKey, range: HistoryRange): HistoryStats {
                if (key == this@HistoryDetailComponentTest.key && fail) error("Storage failed")
                return super.stats(rideId, key, range)
            }
        }
        val c = component(repo); c.openRide("a"); runCurrent(); c.addMetric(key); c.addMetric(other); runCurrent()
        assertEquals(HistoryReadError.STORAGE, c.state.value.metricErrors[key])
        assertNotNull(c.state.value.series[other]); assertNull(c.state.value.series[key]); assertNull(c.state.value.error)
        fail = false; c.removeMetric(key); c.addMetric(key); runCurrent()
        assertNotNull(c.state.value.series[key]); assertTrue(c.state.value.metricErrors.isEmpty())
    }

    @Test fun removedThenReaddedSameKeyRejectsTheOldStatsCompletion() = runTest {
        var calls = 0
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val repo = object : Repo() {
            override suspend fun stats(rideId: String, key: HistorySeriesKey, range: HistoryRange): HistoryStats {
                calls++
                if (calls == 1) return withContext(NonCancellable) {
                    entered.complete(Unit); release.await(); HistoryStats(11.0, 11.0, null, true)
                }
                return HistoryStats(66.0, 66.0, null, true)
            }
        }
        val c = component(repo)
        try {
            c.openRide("a"); runCurrent(); c.addMetric(key); runCurrent()
            assertTrue(entered.isCompleted)
            c.removeMetric(key); c.addMetric(key); runCurrent()
            assertEquals(2, calls); assertEquals(66.0, c.state.value.stats[key]?.maximum)
            release.complete(Unit); runCurrent()
            assertEquals(66.0, c.state.value.stats[key]?.maximum)
            assertEquals(listOf(key), c.state.value.selectedKeys); assertTrue(c.state.value.loadingKeys.isEmpty())
        } finally {
            release.complete(Unit); c.close(); runCurrent()
        }
    }

    @Test fun completedRangeUsesMonotonicAxisAndLegacyKeepsItsWallOnlyAxis() = runTest {
        val repo = object : Repo() {
            override suspend fun ride(rideId: String): HistoryRide? = when (rideId) {
                "skew" -> super.ride(rideId)!!.copy(ended = HistoryTick(700, 4010, 1))
                "legacy" -> super.ride(rideId)!!.copy(started = HistoryTick(1000, 0, 0),
                    ended = HistoryTick(7000, 0, 0), status = HistoryRideStatus.LEGACY)
                else -> super.ride(rideId)!!.copy(ended = null, status = HistoryRideStatus.RECORDING)
            }
        }
        val c = component(repo)
        c.openRide("skew"); runCurrent(); assertEquals(range, c.state.value.range)
        c.openRide("legacy"); runCurrent(); assertEquals(HistoryRange(1000, 7000), c.state.value.range)
        c.openRide("running"); runCurrent(); assertEquals(HistoryRange(1000, 9000), c.state.value.range)
    }

    @Test fun unavailableStoredSeriesAndZeroWidthViewportAreUsableWithoutInventingData() = runTest {
        var reads = 0
        val repo = object : Repo() {
            override suspend fun series(rideId: String, key: HistorySeriesKey, range: HistoryRange, maxPoints: Int): HistorySeries {
                reads++; assertEquals(600, maxPoints); assertEquals(HistoryRange(3000, 3000), range)
                return HistorySeries(key, emptyList(), emptyList(), false)
            }
            override suspend fun stats(rideId: String, key: HistorySeriesKey, range: HistoryRange) = HistoryStats(null, null, null, false)
        }
        val c = component(repo); c.openRide("legacy"); runCurrent(); c.selectRange(HistoryRange(3000, 3000))
        c.addMetric(key); runCurrent(); assertEquals(1, reads)
        assertEquals(listOf(key), c.state.value.selectedKeys)
        assertTrue(c.state.value.series.getValue(key).samples.isEmpty())
        assertNull(c.state.value.stats.getValue(key).maximum); assertTrue(c.state.value.metricErrors.isEmpty())
    }


    @Test fun sourceExpansionLoadsOnlyRecordedSparseMetadataAndCachesIt() = runTest {
        var calls = 0
        val keys = HistoryRecordedKeys(listOf(HistorySeriesKey("pack", "cell_v", 0), HistorySeriesKey("pack", "cell_v", 2)), true)
        val repo = object : Repo() {
            override suspend fun recordedKeys(rideId: String, sourceId: String, limit: Int): HistoryRecordedKeys {
                calls++; assertEquals("a", rideId); assertEquals("pack", sourceId); assertEquals(20000, limit)
                return keys
            }
        }
        val c = component(repo); c.openRide("a"); runCurrent(); assertEquals(0, calls)
        c.loadSourceMetrics("foreign"); c.loadSourceMetrics("pack"); c.loadSourceMetrics("pack"); runCurrent()
        assertEquals(1, calls); assertEquals(keys, c.state.value.recordedKeys["pack"])
        assertTrue(repo.reads.isEmpty()); assertTrue(c.state.value.loadingSources.isEmpty())
        c.loadSourceMetrics("pack"); runCurrent(); assertEquals(1, calls)
    }

    @Test fun lateSourceMetadataCannotReplaceSameNamedGroupInAnotherRide() = runTest {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val repo = object : Repo() {
            override suspend fun recordedKeys(rideId: String, sourceId: String, limit: Int): HistoryRecordedKeys {
                if (rideId == "a") withContext(NonCancellable) { entered.complete(Unit); release.await() }
                return HistoryRecordedKeys(listOf(HistorySeriesKey("pack", "cell_v", if (rideId == "a") 0 else 4)), false)
            }
        }
        val c = component(repo)
        try {
            c.openRide("a"); runCurrent(); c.loadSourceMetrics("pack"); runCurrent()
            assertTrue(entered.isCompleted)
            c.openRide("b"); runCurrent(); c.loadSourceMetrics("pack"); runCurrent()
            release.complete(Unit); runCurrent()
            assertEquals("b", c.state.value.ride?.id)
            assertEquals(4, c.state.value.recordedKeys.getValue("pack").keys.single().cellIndex)
        } finally {
            release.complete(Unit); c.close(); runCurrent()
        }
    }

    @Test fun sourceMetadataRetrySurvivesViewportChangesWithoutNumericPreload() = runTest {
        var calls = 0
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val repo = object : Repo() {
            override suspend fun recordedKeys(rideId: String, sourceId: String, limit: Int): HistoryRecordedKeys {
                calls++
                if (calls == 1) error("Metadata unavailable")
                entered.complete(Unit); release.await()
                return HistoryRecordedKeys(listOf(key), false)
            }
        }
        val c = component(repo)
        try {
            c.openRide("a"); runCurrent(); c.loadSourceMetrics("pack"); runCurrent()
            assertEquals(HistoryReadError.STORAGE, c.state.value.sourceErrors["pack"])
            assertNull(c.state.value.error); assertTrue(c.state.value.loadingSources.isEmpty())
            c.loadSourceMetrics("pack"); runCurrent(); assertTrue(entered.isCompleted)
            c.selectRange(HistoryRange(2000, 3000)); release.complete(Unit); runCurrent()
            assertEquals(listOf(key), c.state.value.recordedKeys.getValue("pack").keys)
            assertTrue(c.state.value.sourceErrors.isEmpty()); assertTrue(c.state.value.loadingSources.isEmpty())
            assertEquals(2, calls); assertTrue(repo.reads.isEmpty())
            c.close(); val before = c.state.value; c.loadSourceMetrics("pack"); runCurrent(); assertEquals(before, c.state.value)
        } finally {
            release.complete(Unit); c.close(); runCurrent()
        }
    }


    @Test fun unfinishedRideViewportDoesNotLoseRecordedTailWhenWallClockMovesBackwards() = runTest {
        val repo = object : Repo() {
            override suspend fun ride(rideId: String) = super.ride(rideId)!!.copy(ended = null, status = HistoryRideStatus.RECORDING)
        }
        // Actual elapsed time is 8010; wall clock has moved back to 700.
        val c = HistoryDetailComponent(DefaultComponentContext(LifecycleRegistry(Lifecycle.State.RESUMED)),
            backgroundScope, repo, { HistoryTick(700, 8010, 1) })
        c.openRide("a"); runCurrent()
        assertEquals(HistoryRange(1000, 9000), c.state.value.range)
        assertNull(c.state.value.ride?.ended)
    }


    @Test fun synchronousSelectionObserverCannotRebindOldRideRequestToNewOwner() = runTest {
        val issued = mutableListOf<String>()
        val repo = object : Repo() {
            override suspend fun ride(rideId: String): HistoryRide? { issued += rideId; return super.ride(rideId) }
        }
        val c = component(repo)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            c.state.collect { if (it.rideId == "a" && it.loading) c.openRide("b") }
        }
        c.openRide("a"); runCurrent()
        assertEquals(listOf("b"), issued); assertEquals("b", c.state.value.ride?.id)
    }

    @Test fun synchronousMetricRemovalCannotStartOrPublishItsRetiredRead() = runTest {
        val repo = Repo(); val c = component(repo); c.openRide("a"); runCurrent()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            c.state.collect { if (key in it.selectedKeys) c.removeMetric(key) }
        }
        c.addMetric(key); runCurrent()
        assertTrue(repo.reads.isEmpty()); assertTrue(c.state.value.selectedKeys.isEmpty())
        assertTrue(c.state.value.series.isEmpty()); assertTrue(c.state.value.loadingKeys.isEmpty())
    }

    @Test fun synchronousRideSwitchDuringGroupExpansionCannotIssueOldMetadataRead() = runTest {
        val issued = mutableListOf<String>()
        val repo = object : Repo() {
            override suspend fun recordedKeys(rideId: String, sourceId: String, limit: Int): HistoryRecordedKeys {
                issued += rideId; return HistoryRecordedKeys(emptyList(), false)
            }
        }
        val c = component(repo); c.openRide("a"); runCurrent()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            c.state.collect { if (it.rideId == "a" && "pack" in it.loadingSources) c.openRide("b") }
        }
        c.loadSourceMetrics("pack"); runCurrent()
        assertTrue(issued.isEmpty()); assertEquals("b", c.state.value.ride?.id)
        assertTrue(c.state.value.recordedKeys.isEmpty())
    }


    @Test fun synchronousRangeAdjustmentDuringAddDoesNotIssueDuplicateNumericReads() = runTest {
        val repo = Repo(); val c = component(repo); c.openRide("a"); runCurrent()
        val adjusted = HistoryRange(2000, 3000)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            c.state.collect { if (key in it.selectedKeys && it.range == range) c.selectRange(adjusted) }
        }
        c.addMetric(key); runCurrent()
        assertEquals(listOf(Triple("a", key, adjusted)), repo.reads)
        assertEquals(adjusted, c.state.value.range); assertNotNull(c.state.value.series[key])
    }

}
