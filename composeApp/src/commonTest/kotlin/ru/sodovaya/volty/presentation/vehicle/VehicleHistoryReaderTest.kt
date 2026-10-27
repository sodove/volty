package ru.sodovaya.volty.presentation.vehicle

import kotlinx.coroutines.test.runTest
import ru.sodovaya.volty.domain.history.*
import kotlin.test.*

class VehicleHistoryReaderTest {
    private fun ride(id: String, vehicle: String, start: Long) =
        HistoryRide(id, vehicle, HistoryTick(start, start, 1), HistoryTick(start + 600_000, start + 600_000, 1), HistoryRideStatus.COMPLETED)

    private open class Repo(val catalog: List<List<HistoryRide>>) : HistoryReadRepository {
        var pages = 0
        var cellKeys: List<HistorySeriesKey> = emptyList()
        var missingCell: Int? = null
        override suspend fun rides(before: HistoryCursor?): HistoryPage<HistoryRide> {
            val index = before?.stableId?.toInt() ?: 0; pages++
            return HistoryPage(catalog[index], if (index + 1 < catalog.size) HistoryCursor(0, (index + 1).toString()) else null, false)
        }
        override suspend fun ride(rideId: String): HistoryRide? = null
        override suspend fun recordedKeys(rideId: String, sourceId: String, limit: Int) = HistoryRecordedKeys(cellKeys, false)
        override suspend fun sources(rideId: String) = listOf(HistorySource("pack", HistorySourceKind.BATTERY, "Pack"))
        override suspend fun gps(rideId: String, range: HistoryRange, after: HistoryCursor?) = error("unused")
        override suspend fun route(rideId: String, range: HistoryRange, after: HistoryCursor?) =
            HistoryRoutePage(null, HistoryPage(emptyList(), null, false), emptyList())
        override suspend fun stats(rideId: String, key: HistorySeriesKey, range: HistoryRange) = HistoryStats(null, null, null, true)
        override suspend fun series(rideId: String, key: HistorySeriesKey, range: HistoryRange, maxPoints: Int): HistorySeries {
            val cell = key.cellIndex
            if (cell != null && cell == missingCell) return HistorySeries(key, emptyList(), emptyList(), false)
            val value = if (cell != null) 4.0 + cell / 1000.0 else 50.0
            return HistorySeries(key, listOf(HistorySample(HistoryTick(range.endEpochMillis, range.endEpochMillis, 1), key, value)), emptyList(), false)
        }
    }
    private val tick = { HistoryTick(99_000_000, 99_000_000, 1) }

    @Test fun oneCatalogPassFindsEveryVehiclesNewestRides() = runTest {
        val repo = Repo(listOf(listOf(ride("w2", "wheel", 9), ride("b1", "bike", 8)), listOf(ride("w1", "wheel", 7)), listOf(ride("s1", "scooter", 6))))
        val found = VehicleHistoryReader(repo, tick, computeDispatcher = kotlinx.coroutines.Dispatchers.Unconfined).newestRides(setOf("wheel", "bike"), perVehicle = 1)
        assertEquals(listOf("w2"), found.getValue("wheel").map { it.id }); assertEquals(listOf("b1"), found.getValue("bike").map { it.id })
        assertEquals(1, repo.pages, "stops as soon as every vehicle has enough")
        val all = VehicleHistoryReader(repo, tick, computeDispatcher = kotlinx.coroutines.Dispatchers.Unconfined).newestRides(setOf("wheel", "nope"), perVehicle = 5)
        assertEquals(listOf("w2", "w1"), all.getValue("wheel").map { it.id }); assertTrue(all.getValue("nope").isEmpty())
    }

    @Test fun cellsAreReadInOrderAndAHoleShowsNoCells() = runTest {
        val repo = Repo(listOf(listOf(ride("r", "bike", 0))))
        repo.cellKeys = listOf(2, 0, 1).map { HistorySeriesKey("pack", "cell_v", it) }
        val reader = VehicleHistoryReader(repo, tick, computeDispatcher = kotlinx.coroutines.Dispatchers.Unconfined)
        val r = ride("r", "bike", 0)
        assertEquals(listOf(4.0, 4.001, 4.002), reader.battery("r", reader.recorded(r), withCells = true).single().cells)
        repo.missingCell = 1
        assertTrue(reader.battery("r", reader.recorded(r), withCells = true).single().cells.isEmpty())
        assertTrue(reader.battery("r", reader.recorded(r), withCells = false).single().cells.isEmpty())
    }

    @Test fun routeShapeKeepsBothEndsWithinTheBudget() {
        val fixes = (0 until 1000).map { HistoryGpsFix(HistoryTick(it * 1000L, it * 1000L, 1), 55.0 + it * 1e-4, 37.0, 5.0, null) }
        val shape = vehicleRouteShape(fixes, 50)
        assertEquals(50, shape.size); assertEquals(55.0, shape.first().first); assertEquals(55.0 + 999 * 1e-4, shape.last().first, 1e-9)
        assertEquals(3, vehicleRouteShape(fixes.take(3), 50).size)
    }

    @Test fun aSecondViewOfTheSameBatteryWithoutChargeIsHidden() {
        fun snap(label: String, soc: Double?) = VehicleBatterySnapshot(label, listOfNotNull(
            soc?.let { VehicleReading("soc_percent", it, HistoryUnit.PERCENT) }, VehicleReading("battery_temperature_c", 19.0, HistoryUnit.CELSIUS)), 0)
        assertEquals(listOf("wheel"), vehicleBatteryCards(listOf(snap("wheel", 94.0), snap("bms", null))).map { it.label })
        assertEquals(listOf("a", "b"), vehicleBatteryCards(listOf(snap("a", 50.0), snap("b", 60.0))).map { it.label }, "two charged packs are two batteries")
        assertEquals(listOf("x"), vehicleBatteryCards(listOf(snap("x", null), snap("y", null))).map { it.label }, "no charge anywhere: one card, not none")
    }
}
