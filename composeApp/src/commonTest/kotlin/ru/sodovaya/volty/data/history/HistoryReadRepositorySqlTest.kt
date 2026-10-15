package ru.sodovaya.volty.data.history

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.test.runTest
import ru.sodovaya.volty.data.db.VoltyDatabase
import ru.sodovaya.volty.data.db.VoltyDatabaseProvider
import ru.sodovaya.volty.domain.history.*
import kotlin.test.*

class HistoryReadRepositorySqlTest {
    private val origin = HistoryTick(10000, 500, 1)
    private val drivers = mutableListOf<JdbcSqliteDriver>()
    @AfterTest fun close() { drivers.forEach { it.close() } }
    private fun fixture(): SqlDelightHistoryStore {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY); drivers += driver
        driver.execute(null, "PRAGMA foreign_keys=ON", 0)
        VoltyDatabase.Schema.create(driver)
        return SqlDelightHistoryStore(VoltyDatabaseProvider(driver))
    }

    @Test fun aRealEqualTimePageBoundaryKeepsBothSidesAndDoesNotInventSummaryStats() = runTest {
        val store = fixture()
        val source = HistorySource("pack", HistorySourceKind.BATTERY, "Recorded battery")
        val key = HistorySeriesKey(source.id, "power_w")
        store.begin(HistoryRide("ride", "bike", origin, null, HistoryRideStatus.RECORDING), listOf(source))
        store.append(HistoryBatch("ride", (0..20002).map {
            HistorySample(origin, key, when (it) { 0 -> 0.0; 19999 -> -4200.0; 20001 -> 9000.0; else -> 1.0 })
        }, emptyList(), emptyList()))
        store.finish("ride", origin, HistoryRideStatus.COMPLETED)
        val calls = mutableListOf<Pair<HistoryCursor?, Int>>()
        val observed = object : HistoryStore by store {
            override suspend fun samples(rideId: String, key: HistorySeriesKey, range: HistoryRange,
                after: HistoryCursor?, limit: Int): HistoryPage<HistorySample> =
                store.samples(rideId, key, range, after, limit).also { calls += after to it.items.size }
        }
        val repository = DefaultHistoryReadRepository(observed)
        val range = HistoryRange(origin.epochMillis, origin.epochMillis)
        val result = repository.series("ride", key, range, 11)
        assertEquals(listOf<Pair<HistoryCursor?, Int>>(null to 20000, HistoryCursor(10000, "19999") to 3), calls)
        assertTrue(result.samples.size <= 11)
        assertEquals(0.0, result.samples.first().value)
        assertEquals(1.0, result.samples.last().value)
        assertTrue(result.samples.any { it.value == -4200.0 })
        assertTrue(result.samples.any { it.value == 9000.0 })
        assertTrue(result.samples.all { it.tick == origin && it.unknown == null })
        assertFalse(result.truncated)
        // Raw detail alone cannot supply a certified full-stream summary.
        assertEquals(HistoryStats(null, null, null, false), repository.stats("ride", key, range))
    }

    @Test fun twoHourMultiSourceReadKeepsCellIdentityGapsAndRawWallJumpWithoutLiveData() = runTest {
        val store = fixture()
        val sources = listOf(
            HistorySource("controller-a", HistorySourceKind.CONTROLLER, "Front"),
            HistorySource("controller-b", HistorySourceKind.CONTROLLER, "Rear"),
            HistorySource("pack-a", HistorySourceKind.BATTERY, "Left"),
            HistorySource("pack-b", HistorySourceKind.BATTERY, "Right"),
        )
        val keys = listOf(HistorySeriesKey("controller-a", "speed_kmh"), HistorySeriesKey("controller-b", "speed_kmh"),
            HistorySeriesKey("pack-a", "power_w"), HistorySeriesKey("pack-b", "power_w"),
            HistorySeriesKey("pack-a", "cell_v", 0), HistorySeriesKey("pack-a", "cell_v", 1),
            HistorySeriesKey("pack-b", "cell_v", 0), HistorySeriesKey("pack-b", "cell_v", 1))
        val values = listOf(10.0, 20.0, -30.0, 40.0, 3.8, 3.9, 4.0, 0.0)
        fun tick(second: Int) = HistoryTick(10000 + second * 1000L + if (second >= 5400) 3600000 else 0,
            500 + second * 1000L, 1)
        store.begin(HistoryRide("ride", "bike", origin, null, HistoryRideStatus.RECORDING), sources)
        // Deliberately distinct sources/physical cells, not eight copies of one aggregate.
        for (start in 0..7200 step 128) {
            val samples = (start..minOf(start + 127, 7200)).flatMap { second ->
                keys.mapIndexed { index, key -> HistorySample(tick(second), key, values[index]) }
            }
            store.append(HistoryBatch("ride", samples, emptyList(), emptyList()))
        }
        val gap = HistoryMarker(tick(3600), HistoryMarkerKind.BLE_GAP)
        store.append(HistoryBatch("ride", emptyList(), emptyList(), listOf(gap)))
        store.finish("ride", tick(7200), HistoryRideStatus.COMPLETED)
        val rawRowsRead = mutableMapOf<HistorySeriesKey, Int>()
        val observed = object : HistoryStore by store {
            override suspend fun samples(rideId: String, key: HistorySeriesKey, range: HistoryRange,
                after: HistoryCursor?, limit: Int): HistoryPage<HistorySample> =
                store.samples(rideId, key, range, after, limit).also {
                    assertEquals(20000, limit)
                    rawRowsRead[key] = (rawRowsRead[key] ?: 0) + it.items.size
                }
        }
        val repository = DefaultHistoryReadRepository(observed)
        assertEquals(sources, repository.sources("ride"))
        keys.forEachIndexed { index, key ->
            val result = repository.series("ride", key, HistoryRange(10000, 7210000), 61)
            assertEquals(key, result.key)
            assertEquals(origin, result.origin)
            assertTrue(result.samples.size <= 61)
            assertFalse(result.truncated)
            assertEquals(setOf(values[index]), result.samples.mapNotNull { it.value }.toSet())
            assertTrue(result.samples.all { it.key == key })
            assertEquals(tick(7200), result.samples.last().tick)
            assertTrue(result.samples.any { it.unknown == HistoryUnknownReason.GAP && it.tick == gap.tick })
            assertTrue(result.samples.any { it.unknown == HistoryUnknownReason.GAP && it.tick == tick(5400) })
        }
        assertEquals(keys.associateWith { 7201 }, rawRowsRead)
        val atJump = repository.series("ride", keys.first(), HistoryRange(5410000, 5410000))
        assertEquals(listOf(HistorySample(tick(5400), keys.first(), 10.0)), atJump.samples)
        assertTrue(repository.series("ride", keys.first(), HistoryRange(0, 9999)).samples.isEmpty())
        assertTrue(repository.series("ride", keys.first().copy(sourceId = "unrecorded"), HistoryRange(10000, 7210000)).samples.isEmpty())
    }
}
