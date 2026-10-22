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

    private fun TestScope.component(history: History, vehicles: List<Vehicle> = listOf(bike), maxRides: Int = 10) =
        DefaultVehicleOverviewComponent(DefaultComponentContext(LifecycleRegistry(Lifecycle.State.RESUMED)), backgroundScope,
            "bike", Vehicles(vehicles), history, { HistoryTick(40_000_000, 40_000_000, 1) }, {}, {}, {}, {}, {}, maxRides = maxRides)

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
        assertEquals("r2", s.totals.rideId); assertTrue(s.totals.totals.isNotEmpty())
    }

    @Test fun stopsPagingOnceEnoughRidesAreFound() = runTest {
        val history = History(); val c = component(history, maxRides = 1); runCurrent()
        assertEquals(listOf("r2"), c.state.value.rides.map { it.id }); assertEquals(1, history.catalogPages)
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
