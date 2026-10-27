package ru.sodovaya.volty.presentation.history

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import ru.sodovaya.volty.domain.history.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryTotalsComponentTest {
    private val esc = HistorySource("esc", HistorySourceKind.CONTROLLER, "ESC")
    private val pack = HistorySource("pack", HistorySourceKind.BATTERY, "Pack")
    private val whole = HistoryRange(0, 3_600_000)

    private open inner class Repo : HistoryReadRepository {
        var trip = 10.0
        val statsReads = mutableListOf<HistorySeriesKey>()
        var routeReads = 0
        override suspend fun recordedKeys(rideId: String, sourceId: String, limit: Int) = HistoryRecordedKeys(emptyList(), false)
        override suspend fun rides(before: HistoryCursor?) = HistoryPage(emptyList<HistoryRide>(), null, false)
        override suspend fun ride(rideId: String): HistoryRide? = null
        override suspend fun sources(rideId: String) = emptyList<HistorySource>()
        override suspend fun series(rideId: String, key: HistorySeriesKey, range: HistoryRange, maxPoints: Int) = error("unused")
        override suspend fun gps(rideId: String, range: HistoryRange, after: HistoryCursor?) = error("unused")
        override suspend fun route(rideId: String, range: HistoryRange, after: HistoryCursor?): HistoryRoutePage {
            routeReads++
            fun fix(sec: Long, lat: Double) = HistoryGpsFix(HistoryTick(sec * 1000, sec * 1000, 1), lat, 37.0, 5.0, null)
            return HistoryRoutePage(null, HistoryPage(listOf(fix(0, 55.0), fix(5, 55.001)), null, false), emptyList())
        }
        override suspend fun stats(rideId: String, key: HistorySeriesKey, range: HistoryRange): HistoryStats {
            statsReads += key
            return when (key.metricId) {
                "trip_distance_km" -> HistoryStats(0.0, trip, null, true)
                "speed_kmh" -> error("storage failure")
                else -> HistoryStats(null, null, null, true)
            }
        }
    }

    private fun TestScope.component(repo: Repo) =
        HistoryTotalsComponent(DefaultComponentContext(LifecycleRegistry(Lifecycle.State.RESUMED)), backgroundScope, repo, refreshMillis = 15_000)

    @Test fun loadsWholeRideTotalsAndAFailedStatOnlyDropsItsTile() = runTest {
        val repo = Repo(); val c = component(repo)
        c.open("r", listOf(esc), whole); runCurrent()
        val kinds = c.state.value.totals.associateBy { it.kind }
        assertEquals(10.0, kinds.getValue(HistoryTotalKind.DISTANCE_KM).value)
        assertNull(kinds[HistoryTotalKind.MAX_KMH], "speed failed to read: no tile, not zero")
        assertEquals(1, repo.routeReads, "the GPS track is always read now: it is the primary witness of the distance")
        assertFalse(c.state.value.loading)
    }

    @Test fun withoutACounterTheGpsTrackGivesTheDistance() = runTest {
        val repo = Repo(); val c = component(repo)
        c.open("r", listOf(pack), whole); runCurrent()
        assertEquals(1, repo.routeReads)
        assertEquals(0.111, c.state.value.totals.single { it.kind == HistoryTotalKind.DISTANCE_KM }.value, 0.002)
    }

    @Test fun aGrowingRecordingKeepsItsTilesAndRefreshesNoFasterThanTheInterval() = runTest {
        val repo = Repo(); val c = component(repo)
        c.open("r", listOf(esc), whole); runCurrent()
        val reads = repo.statsReads.size
        repo.trip = 12.0
        c.open("r", listOf(esc), HistoryRange(0, 3_605_000)); runCurrent()
        assertEquals(10.0, c.state.value.totals.single { it.kind == HistoryTotalKind.DISTANCE_KM }.value, "old tiles stay meanwhile")
        assertEquals(reads, repo.statsReads.size)
        c.open("r", listOf(esc), HistoryRange(0, 3_610_000)); advanceTimeBy(14_999); runCurrent()
        assertEquals(reads, repo.statsReads.size, "updates coalesce")
        advanceTimeBy(2); runCurrent()
        assertEquals(12.0, c.state.value.totals.single { it.kind == HistoryTotalKind.DISTANCE_KM }.value)
    }

    @Test fun anotherRideStartsEmptyAndLoadsImmediately() = runTest {
        val repo = Repo(); val c = component(repo)
        c.open("r", listOf(esc), whole); runCurrent()
        c.open("s", listOf(esc), whole)
        assertEquals("s", c.state.value.rideId); assertTrue(c.state.value.totals.isEmpty()); assertTrue(c.state.value.loading)
        runCurrent(); assertFalse(c.state.value.totals.isEmpty())
    }
}
