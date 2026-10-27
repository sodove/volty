package ru.sodovaya.volty.presentation.history

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import ru.sodovaya.volty.domain.history.*
import kotlin.test.*

/** The ride-card peak PWM: what was read, what was shown. Stats that disagree with detail are built on purpose. */
@OptIn(ExperimentalCoroutinesApi::class)
class HistoryCardStatsComponentTest {
    private val origin = HistoryTick(1000, 10, 1)
    private val duty = HistorySeriesKey("esc", "duty_percent")
    private val estimated = HistorySeriesKey("esc", "duty_estimated_percent")
    private fun ride(id: String, status: HistoryRideStatus = HistoryRideStatus.COMPLETED) =
        HistoryRide(id, "bike", origin, if (status == HistoryRideStatus.RECORDING) null else HistoryTick(700_000, 690_010, 1), status)
    private fun sample(key: HistorySeriesKey, i: Int, v: Double) = HistorySample(HistoryTick(1000L + i * 1000L, i * 1000L + 10, 1), key, v)
    private fun series(key: HistorySeriesKey, peak: Double, needle: Double? = null) = (0..600).map { i ->
        sample(key, i, when { i in 300..305 -> peak; needle != null && i == 450 -> needle; else -> 50.0 + (i % 5) })
    }

    private class Repo : HistoryReadRepository {
        var stats = mapOf<HistorySeriesKey, HistoryStats>()
        var detail = mapOf<HistorySeriesKey, List<HistorySample>>()
        var detailSupported = true; var pageSize = 20_000
        var failStats = false
        val statsCalls = mutableListOf<Pair<String, HistorySeriesKey>>()
        val sampleCalls = mutableListOf<HistorySeriesKey>()
        override suspend fun recordedKeys(rideId: String, sourceId: String, limit: Int) = error("unused")
        override suspend fun rides(before: HistoryCursor?): HistoryPage<HistoryRide> = error("unused")
        override suspend fun ride(rideId: String): HistoryRide? = error("unused")
        override suspend fun sources(rideId: String) = listOf(HistorySource("esc", HistorySourceKind.CONTROLLER, "ESC"))
        override suspend fun series(rideId: String, key: HistorySeriesKey, range: HistoryRange, maxPoints: Int) = error("unused")
        override suspend fun gps(rideId: String, range: HistoryRange, after: HistoryCursor?) = error("unused")
        override suspend fun route(rideId: String, range: HistoryRange, after: HistoryCursor?) = error("unused")
        override suspend fun stats(rideId: String, key: HistorySeriesKey, range: HistoryRange): HistoryStats {
            statsCalls += rideId to key
            if (failStats) error("disk error")
            return stats[key] ?: HistoryStats(null, null, null, true)
        }
        override suspend fun samples(rideId: String, key: HistorySeriesKey, range: HistoryRange, after: HistoryCursor?, limit: Int):
            HistoryPage<HistorySample>? {
            if (!detailSupported) return null
            sampleCalls += key
            val all = detail[key].orEmpty()
            val from = after?.stableId?.toInt() ?: 0
            val to = minOf(all.size, from + pageSize)
            val next = if (to < all.size) HistoryCursor(all[to - 1].tick.epochMillis, to.toString()) else null
            return HistoryPage(all.subList(from, to), next, next != null)
        }
    }

    /** Runs [body] with a component on a test dispatcher and destroys its lifecycle afterwards, even when [body] fails. */
    private suspend fun TestScope.withComponent(repo: Repo, body: suspend (HistoryCardStatsComponent) -> Unit) {
        val lifecycle = LifecycleRegistry(Lifecycle.State.RESUMED)
        val c = HistoryCardStatsComponent(DefaultComponentContext(lifecycle), backgroundScope, repo, StandardTestDispatcher(testScheduler))
        try { body(c) } finally { lifecycle.destroy(); runCurrent() }
    }

    @Test fun visibleRidesLoadOnceAndAreServedFromTheCacheAfterwards() = runTest {
        val repo = Repo().apply { stats = mapOf(duty to HistoryStats(0.0, 92.0, null, true)); detail = mapOf(duty to series(duty, 92.0)) }
        withComponent(repo) { c ->
            c.setVisible(listOf(ride("a"), ride("b"))); runCurrent()
            assertEquals(92.0, c.state.value.stats.getValue("a").maxDuty!!.value)
            val reads = repo.statsCalls.size
            assertTrue(reads > 0)
            c.setVisible(listOf(ride("a"), ride("b"))); runCurrent()
            c.setVisible(emptyList()); c.setVisible(listOf(ride("a"))); runCurrent()
            assertEquals(reads, repo.statsCalls.size, "the same rides at the same revision are never re-read")
            c.setVisible(listOf(ride("a")), revision = 1); runCurrent()
            assertTrue(repo.statsCalls.size > reads, "a new catalog revision re-reads")
        }
    }

    @Test fun theEstimateIsMarkedAndAMeasurementIsNot() = runTest {
        val repo = Repo().apply { stats = mapOf(estimated to HistoryStats(0.0, 87.0, null, true)); detail = mapOf(estimated to series(estimated, 87.0)) }
        withComponent(repo) { c ->
            c.setVisible(listOf(ride("a"))); runCurrent()
            val peak = c.state.value.stats.getValue("a").maxDuty!!
            assertEquals(87.0, peak.value); assertTrue(peak.estimated); assertFalse(peak.approximate)
            assertEquals("~ ", historyTotalPrefix(peak))
        }
    }

    @Test fun aNeedleInTheStatsMaximumIsExcludedWhenTheDetailFitsOnePage() = runTest {
        // Incoherent on purpose: complete = true stats whose maximum is the needle.
        val repo = Repo().apply {
            stats = mapOf(duty to HistoryStats(0.0, 400.0, null, true)); detail = mapOf(duty to series(duty, 88.0, needle = 400.0))
        }
        withComponent(repo) { c ->
            c.setVisible(listOf(ride("a"))); runCurrent()
            val peak = c.state.value.stats.getValue("a").maxDuty!!
            assertEquals(88.0, peak.value); assertTrue(peak.approximate)
        }
    }

    @Test fun detailBeyondOnePageKeepsTheStatsMaximumMarkedApproximate() = runTest {
        val repo = Repo().apply {
            stats = mapOf(duty to HistoryStats(0.0, 400.0, null, true)); detail = mapOf(duty to series(duty, 88.0, needle = 400.0)); pageSize = 100
        }
        withComponent(repo) { c ->
            c.setVisible(listOf(ride("a"))); runCurrent()
            val peak = c.state.value.stats.getValue("a").maxDuty!!
            assertEquals(400.0, peak.value); assertTrue(peak.approximate)
            assertEquals(1, repo.sampleCalls.size, "one page is the whole budget")
        }
    }

    @Test fun aRecordingRideIsReadFromStatsOnly() = runTest {
        val repo = Repo().apply { stats = mapOf(duty to HistoryStats(0.0, 77.0, null, false)); detail = mapOf(duty to series(duty, 77.0)) }
        withComponent(repo) { c ->
            c.setVisible(listOf(ride("a", HistoryRideStatus.RECORDING))); runCurrent()
            val peak = c.state.value.stats.getValue("a").maxDuty!!
            assertEquals(77.0, peak.value); assertTrue(peak.approximate, "an incomplete stat says approximate")
            assertTrue(repo.sampleCalls.isEmpty())
        }
    }

    @Test fun aReaderThatCannotPageDetailKeepsTheStatsMaximum() = runTest {
        val repo = Repo().apply { stats = mapOf(duty to HistoryStats(0.0, 63.0, null, true)); detailSupported = false }
        withComponent(repo) { c ->
            c.setVisible(listOf(ride("a"))); runCurrent()
            val peak = c.state.value.stats.getValue("a").maxDuty!!
            assertEquals(63.0, peak.value); assertFalse(peak.approximate); assertFalse(peak.estimated)
        }
    }

    @Test fun noDutyAtAllShowsNoLineAndLegacyIsNotRead() = runTest {
        val repo = Repo()
        withComponent(repo) { c ->
            c.setVisible(listOf(ride("a"), ride("old", HistoryRideStatus.LEGACY))); runCurrent()
            assertNull(c.state.value.stats.getValue("a").maxDuty)
            assertNull(c.state.value.stats.getValue("old").maxDuty)
            assertTrue(repo.statsCalls.none { it.first == "old" })
            assertTrue(c.state.value.errors.isEmpty())
        }
    }

    @Test fun aFailureIsKeptAndOnlyAnExplicitRetryRestartsIt() = runTest {
        val repo = Repo().apply { failStats = true; stats = mapOf(duty to HistoryStats(0.0, 70.0, null, true)); detail = mapOf(duty to series(duty, 70.0)) }
        withComponent(repo) { c ->
            c.setVisible(listOf(ride("a"))); runCurrent()
            assertEquals(setOf("a"), c.state.value.errors); val failed = repo.statsCalls.size
            c.setVisible(listOf(ride("a"))); runCurrent()
            assertEquals(failed, repo.statsCalls.size, "no hot loop")
            repo.failStats = false; c.retry("a"); runCurrent()
            assertTrue(c.state.value.errors.isEmpty()); assertEquals(70.0, c.state.value.stats.getValue("a").maxDuty!!.value)
        }
    }

    @Test fun withBothSeriesPresentTheLargerWinsTheTieGoesToTheMeasurementAndAnApproximateLoserDoesNotLeak() = runTest {
        suspend fun peak(measured: Double, estimate: Double, measuredStatsComplete: Boolean = true): HistoryTotal {
            val repo = Repo().apply {
                stats = mapOf(duty to HistoryStats(0.0, measured, null, measuredStatsComplete), estimated to HistoryStats(0.0, estimate, null, true))
                detail = mapOf(duty to series(duty, measured), estimated to series(estimated, estimate))
            }
            var out: HistoryTotal? = null
            withComponent(repo) { c -> c.setVisible(listOf(ride("a"))); runCurrent(); out = c.state.value.stats.getValue("a").maxDuty }
            return out!!
        }
        assertTrue(peak(measured = 40.0, estimate = 87.0).estimated)
        assertFalse(peak(measured = 92.0, estimate = 87.0).estimated)
        assertFalse(peak(measured = 80.0, estimate = 80.0).estimated, "a tie goes to the measurement")
        // Incoherent on purpose: incomplete stats on a loser far below the winner.
        val exact = peak(measured = 40.0, estimate = 87.0, measuredStatsComplete = false)
        assertEquals(87.0, exact.value); assertTrue(exact.estimated); assertFalse(exact.approximate)
    }
}
