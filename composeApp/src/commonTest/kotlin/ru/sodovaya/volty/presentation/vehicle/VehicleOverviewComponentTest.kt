package ru.sodovaya.volty.presentation.vehicle

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import ru.sodovaya.volty.domain.history.*
import ru.sodovaya.volty.domain.model.*
import ru.sodovaya.volty.domain.repository.GaugePeaks
import ru.sodovaya.volty.domain.repository.VehicleRepository
import kotlin.test.*
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class VehicleOverviewComponentTest {
    private val bike = Vehicle("bike", "Bike", "bicycle", listOf(Pack(0, "Pack", BmsType.ANT_BMS, "AA")), emptyList(),
        chemistry = Chemistry.LI_ION_NMC, createdAt = Instant.fromEpochSeconds(0))
    private fun ride(id: String, vehicle: String, start: Long) =
        HistoryRide(id, vehicle, HistoryTick(start, start, 1), HistoryTick(start + 3_600_000, start + 3_600_000, 1), HistoryRideStatus.COMPLETED)

    private class Vehicles(list: List<Vehicle>) : VehicleRepository {
        override val vehicles: Flow<List<Vehicle>> = MutableStateFlow(list)
        override suspend fun get(id: String): Vehicle? = null
        override suspend fun upsert(vehicle: Vehicle) {}
        override suspend fun delete(id: String) {}
        override suspend fun touch(id: String) {}
        override val gaugePeaks: Flow<Map<String, GaugePeaks>> = flowOf(emptyMap())
        override suspend fun updateGaugePeaks(id: String, currentA: Float, powerW: Float) {}
    }

    private open inner class History : HistoryReadRepository {
        val seriesRanges = mutableListOf<HistoryRange>()
        var catalogPages = 0
        open val pages = listOf(
            HistoryPage(listOf(ride("r3", "wheel", 30_000_000), ride("r2", "bike", 20_000_000)), HistoryCursor(20_000_000, "r2"), false),
            HistoryPage(listOf(ride("r1", "bike", 10_000_000)), null, false),
        )
        override suspend fun rides(before: HistoryCursor?) = pages[if (before == null) 0 else 1].also { catalogPages++ }
        override suspend fun ride(rideId: String): HistoryRide? = null
        override suspend fun recordedKeys(rideId: String, sourceId: String, limit: Int) = HistoryRecordedKeys(emptyList(), false)
        override suspend fun sources(rideId: String) = listOf(HistorySource("pack", HistorySourceKind.BATTERY, "Pack"))
        override suspend fun gps(rideId: String, range: HistoryRange, after: HistoryCursor?) = error("unused")
        override suspend fun route(rideId: String, range: HistoryRange, after: HistoryCursor?) =
            HistoryRoutePage(null, HistoryPage(emptyList(), null, false), emptyList())
        override suspend fun stats(rideId: String, key: HistorySeriesKey, range: HistoryRange) = HistoryStats(52.0, 80.0, null, true)
        override suspend fun series(rideId: String, key: HistorySeriesKey, range: HistoryRange, maxPoints: Int): HistorySeries {
            seriesRanges += range
            fun at(ms: Long, v: Double?) = HistorySample(HistoryTick(ms, ms, 1), key, v, if (v == null) HistoryUnknownReason.UNAVAILABLE else null)
            return when (key.metricId) {
                // The newest stored row is an unknown: the reading is the last measured value before it.
                "soc_percent" -> HistorySeries(key, listOf(at(23_500_000, 54.0), at(23_590_000, 52.0), at(23_599_000, null)), emptyList(), false)
                "voltage_v" -> HistorySeries(key, listOf(at(23_598_000, 98.4)), emptyList(), false)
                "cell_min_v" -> error("storage failure")
                else -> HistorySeries(key, emptyList(), emptyList(), false)
            }
        }
    }

    private fun TestScope.component(
        history: History, vehicles: List<Vehicle> = listOf(bike), maxRides: Int = 10, seed: VehicleOverviewSeed? = null,
        reader: VehicleHistoryReader = historyReader(history),
    ) = DefaultVehicleOverviewComponent(DefaultComponentContext(LifecycleRegistry(Lifecycle.State.RESUMED)), backgroundScope,
        "bike", Vehicles(vehicles), reader,
        {}, {}, {}, {}, {}, maxRides = maxRides, seed = seed)

    private fun historyReader(history: History) =
        VehicleHistoryReader(history, { HistoryTick(40_000_000, 40_000_000, 1) }, computeDispatcher = Dispatchers.Unconfined)

    @Test fun showsOnlyThisVehiclesRidesAndTheLastKnownBatteryOfTheNewest() = runTest {
        val history = History(); val c = component(history); runCurrent()
        val s = c.state.value
        assertFalse(s.loading); assertFalse(s.error); assertEquals("Bike", s.vehicle?.name)
        assertEquals(listOf("r2", "r1"), s.rides.map { it.id }); assertEquals("r2", s.lastRide?.id)
        val battery = s.battery.single()
        assertEquals("Pack", battery.label)
        assertEquals(listOf("soc_percent" to 52.0, "voltage_v" to 98.4), battery.readings.map { it.metricId to it.value },
            "a failed or empty metric is left out, never shown as zero")
        assertEquals(23_598_000, battery.atEpochMillis)
        // Only the tail of the ride is read for the last state, not the whole ride.
        assertTrue(history.seriesRanges.all { it == HistoryRange(23_000_000, 23_600_000) })
        assertTrue(s.lastTotals.isNotEmpty()); assertEquals(setOf("r2", "r1"), s.rideTotals.keys, "every listed ride gets its numbers")
    }

    @Test fun stopsPagingOnceEnoughRidesAreFound() = runTest {
        val history = History(); val c = component(history, maxRides = 1); runCurrent()
        assertEquals(listOf("r2"), c.state.value.rides.map { it.id }); assertEquals(1, history.catalogPages)
    }

    @Test fun routeCanAppearWhileBatteryIsStillBeingRead() = runTest {
        val batteryRequested = CompletableDeferred<Unit>()
        val batteryReady = CompletableDeferred<Unit>()
        val history = object : History() {
            override suspend fun recordedKeys(rideId: String, sourceId: String, limit: Int): HistoryRecordedKeys {
                batteryRequested.complete(Unit)
                batteryReady.await()
                return super.recordedKeys(rideId, sourceId, limit)
            }

            override suspend fun route(rideId: String, range: HistoryRange, after: HistoryCursor?) = previewRoute()
        }
        val overview = component(history, maxRides = 1)
        runCurrent()
        assertTrue(batteryRequested.isCompleted)
        assertTrue(overview.state.value.loading)
        assertEquals("r2", overview.state.value.lastRide?.id)
        assertEquals(previewPoints, overview.state.value.lastRoute)
        batteryReady.complete(Unit)
    }

    @Test fun reopeningUsesTheLastRouteAfterConfirmingTheLatestRide() = runTest {
        val sourcesRequested = CompletableDeferred<Unit>()
        val sourcesReady = CompletableDeferred<Unit>()
        var holdSources = false
        var newest = ride("r2", "bike", 20_000_000)
        val history = object : History() {
            override val pages get() = listOf(HistoryPage(listOf(newest), null, false))

            override suspend fun sources(rideId: String): List<HistorySource> {
                if (holdSources) {
                    sourcesRequested.complete(Unit)
                    sourcesReady.await()
                }
                return super.sources(rideId)
            }

            override suspend fun route(rideId: String, range: HistoryRange, after: HistoryCursor?) = previewRoute()
        }
        val reader = historyReader(history)
        val first = component(history, maxRides = 1, reader = reader)
        runCurrent()
        assertEquals(previewPoints, first.state.value.lastRoute)

        holdSources = true
        val reopened = component(history, maxRides = 1, reader = reader)
        runCurrent()
        assertTrue(sourcesRequested.isCompleted)
        assertTrue(reopened.state.value.loading)
        assertEquals(previewPoints, reopened.state.value.lastRoute)

        newest = ride("new", "bike", 30_000_000)
        val changed = component(history, maxRides = 1, reader = reader)
        runCurrent()
        assertEquals("new", changed.state.value.lastRide?.id)
        assertTrue(changed.state.value.lastRoute.isEmpty())
        sourcesReady.complete(Unit)
    }

    private val previewPoints = listOf(55.0 to 37.0, 55.001 to 37.001)

    private fun previewRoute(): HistoryRoutePage {
        val fixes = previewPoints.mapIndexed { index, (lat, lon) ->
            val time = 20_000_000L + index * 1_000L
            HistoryGpsFix(HistoryTick(time, time, 1), lat, lon, 5.0, null)
        }
        return HistoryRoutePage(fixes.first().tick, HistoryPage(fixes, null, false), emptyList())
    }

    @Test fun publishesTheOverviewBeforeRevealingOlderTotalsTogether() = runTest {
        val routeRequested = CompletableDeferred<Unit>()
        val routeReady = CompletableDeferred<Unit>()
        val firstOlderTotalsReady = CompletableDeferred<Unit>()
        val lastOlderTotalsRequested = CompletableDeferred<Unit>()
        val lastOlderTotalsReady = CompletableDeferred<Unit>()
        val fixes = listOf(
            HistoryGpsFix(HistoryTick(20_000_000, 20_000_000, 1), 55.0, 37.0, 5.0, null),
            HistoryGpsFix(HistoryTick(20_001_000, 20_001_000, 1), 55.001, 37.001, 5.0, null),
        )
        val history = object : History() {
            override val pages = listOf(
                HistoryPage(listOf(ride("r2", "bike", 20_000_000)), HistoryCursor(20_000_000, "r2"), false),
                HistoryPage(listOf(ride("r1", "bike", 10_000_000), ride("r0", "bike", 1_000_000)), null, false),
            )

            override suspend fun route(rideId: String, range: HistoryRange, after: HistoryCursor?): HistoryRoutePage {
                if (rideId != "r2") return super.route(rideId, range, after)
                routeRequested.complete(Unit)
                routeReady.await()
                return HistoryRoutePage(fixes.first().tick, HistoryPage(fixes, null, false), emptyList())
            }

            override suspend fun sources(rideId: String): List<HistorySource> {
                when (rideId) {
                    "r1" -> firstOlderTotalsReady.await()
                    "r0" -> {
                        lastOlderTotalsRequested.complete(Unit)
                        lastOlderTotalsReady.await()
                    }
                }
                return super.sources(rideId)
            }
        }
        val component = component(history)
        runCurrent()
        assertTrue(routeRequested.isCompleted, "the last ride's route read is in progress")
        val loading = component.state.value
        assertTrue(loading.loading)
        assertTrue(loading.rides.isEmpty())
        assertEquals("r2", loading.lastRide?.id)
        assertEquals(52.0, loading.battery.single().value("soc_percent"),
            "the earned battery must be visible while the route is still loading")
        assertTrue(loading.lastTotals.isEmpty())

        routeReady.complete(Unit)
        runCurrent()
        val ready = component.state.value
        assertFalse(ready.loading)
        assertEquals(listOf("r2", "r1", "r0"), ready.rides.map { it.id })
        assertEquals("r2", ready.lastRide?.id)
        assertEquals(fixes.map { it.lat to it.lon }, ready.lastRoute)
        assertEquals(52.0, ready.battery.single().value("soc_percent"))
        assertTrue(ready.lastTotals.isNotEmpty())
        assertEquals(setOf("r2"), ready.rideTotals.keys)

        firstOlderTotalsReady.complete(Unit)
        runCurrent()
        assertTrue(lastOlderTotalsRequested.isCompleted, "the first older ride has finished reading")
        assertEquals(setOf("r2"), component.state.value.rideTotals.keys, "older totals are published as one batch")

        lastOlderTotalsReady.complete(Unit)
        runCurrent()
        assertEquals(setOf("r2", "r1", "r0"), component.state.value.rideTotals.keys)
    }

    @Test fun garageBatteryIsVisibleBeforeTheHistoryReadAndReplacedByTheLatestRide() = runTest {
        val catalogRequested = CompletableDeferred<Unit>()
        val catalogReady = CompletableDeferred<Unit>()
        val history = object : History() {
            override suspend fun rides(before: HistoryCursor?): HistoryPage<HistoryRide> {
                catalogRequested.complete(Unit)
                catalogReady.await()
                return super.rides(before)
            }
        }
        val earlierRide = ride("r1", "bike", 10_000_000)
        val snapshot = VehicleBatterySnapshot(
            "Pack", listOf(VehicleReading("soc_percent", 81.0, HistoryUnit.PERCENT)),
            earlierRide.ended!!.epochMillis, sourceId = "pack",
        )
        val c = component(history, seed = VehicleOverviewSeed(bike, VehicleGlance(earlierRide, snapshot, emptyList())))
        runCurrent()

        assertTrue(catalogRequested.isCompleted)
        assertTrue(c.state.value.loading)
        assertEquals(bike, c.state.value.vehicle)
        assertEquals(earlierRide, c.state.value.lastRide)
        assertEquals(listOf(snapshot), c.state.value.battery)

        catalogReady.complete(Unit)
        runCurrent()
        assertFalse(c.state.value.loading)
        assertEquals("r2", c.state.value.lastRide?.id)
        assertEquals(52.0, c.state.value.battery.single().value("soc_percent"))
    }

    @Test fun aVehicleWithoutRidesShowsNothingRecordedAndNoError() = runTest {
        val history = object : History() {
            override val pages = listOf(HistoryPage(listOf(ride("r3", "wheel", 30_000_000)), null, false),
                HistoryPage(emptyList<HistoryRide>(), null, false))
        }
        val c = component(history); runCurrent()
        val s = c.state.value
        assertFalse(s.loading); assertFalse(s.error); assertTrue(s.rides.isEmpty()); assertNull(s.lastRide); assertTrue(s.battery.isEmpty())
    }
}
