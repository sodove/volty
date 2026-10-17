package ru.sodovaya.volty.data.history

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.test.runTest
import ru.sodovaya.volty.data.db.VoltyDatabase
import ru.sodovaya.volty.data.db.VoltyDatabaseProvider
import ru.sodovaya.volty.domain.history.*
import kotlin.test.*
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class SqlDelightHistoryReadRepositoryTest {
    private val drivers = mutableListOf<JdbcSqliteDriver>()
    private val start = Instant.parse("2026-10-13T00:00:00Z").toEpochMilliseconds()
    private val source = HistorySource("pack", HistorySourceKind.BATTERY, "Recorded battery")
    private val key = HistorySeriesKey("pack", "power_w")
    private val oldKey = HistorySeriesKey("legacy-battery", "power_w")
    private val range = HistoryRange(start, start + 10000)
    @AfterTest fun close() { drivers.forEach { it.close() } }
    private data class Fixture(val db: VoltyDatabase, val store: SqlDelightHistoryStore,
        val reader: SqlDelightHistoryReadRepository)
    private fun fixture(maxReadPages: Int = 8): Fixture {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY); drivers += driver
        driver.execute(null, "PRAGMA foreign_keys=ON", 0)
        VoltyDatabase.Schema.create(driver)
        val provider = VoltyDatabaseProvider(driver)
        return Fixture(provider.database, SqlDelightHistoryStore(provider), SqlDelightHistoryReadRepository(provider, maxReadPages = maxReadPages))
    }
    private fun Fixture.old(id: String, epoch: Long = start) = db.rideRowQueries.insert(
        id, "old-bike", Instant.fromEpochMilliseconds(epoch).toString(), null)
    private suspend fun Fixture.current(id: String, epoch: Long = start, elapsed: Long = 500,
        recordedSource: HistorySource = source): HistoryTick {
        val tick = HistoryTick(epoch, elapsed, 7)
        store.begin(HistoryRide(id, "new-bike", tick, null, HistoryRideStatus.RECORDING), listOf(recordedSource))
        return tick
    }
    private suspend fun allPages(reader: HistoryReadRepository): Pair<List<HistoryRide>, List<Int>> {
        val rides = mutableListOf<HistoryRide>(); val sizes = mutableListOf<Int>()
        var after: HistoryCursor? = null
        repeat(4) { // Fixture has <=45 rides; unexpected continuation must fail, never loop.
            val page = reader.rides(after)
            rides += page.items; sizes += page.items.size
            assertEquals(page.next != null, page.truncated)
            if (page.next == null) return rides to sizes
            after = page.next
        }
        error("Catalog did not terminate within the fixture bound")
    }

    @Test fun mixedCatalogPagesEqualTimeRidesTogetherWithoutErasingTheirClockMetadata() = runTest {
        val f = fixture()
        repeat(45) { i ->
            val id = "r${i.toString().padStart(2, '0')}"
            if (i % 2 == 0) f.old(id) else f.current(id, elapsed = 500 + i.toLong())
            if (i == 1) f.store.finish(id, HistoryTick(start + 1234, 900, 7), HistoryRideStatus.COMPLETED)
        }
        val (rides, sizes) = allPages(f.reader)
        assertEquals(listOf(20, 20, 5), sizes)
        assertEquals((44 downTo 0).map { "r${it.toString().padStart(2, '0')}" }, rides.map { it.id })
        rides.forEach { ride ->
            val index = ride.id.drop(1).toInt()
            assertEquals(if (index % 2 == 0) HistoryTick(start, 0, 0) else HistoryTick(start, 500 + index.toLong(), 7), ride.started)
            assertEquals(when { index == 1 -> HistoryRideStatus.COMPLETED; index % 2 == 0 -> HistoryRideStatus.LEGACY
                else -> HistoryRideStatus.RECORDING }, ride.status)
            assertEquals(if (index == 1) HistoryTick(start + 1234, 900, 7) else null, ride.ended)
        }
    }

    @Test fun duplicateOldIdsAreExcludedBeforeThePageLimitAndTheirRowsArePreserved() = runTest {
        val f = fixture()
        repeat(45) { f.old("r${it.toString().padStart(2, '0')}") }
        for (i in 20..44) f.current("r${i.toString().padStart(2, '0')}", epoch = start + 1000)
        val (rides, sizes) = allPages(f.reader)
        assertEquals(listOf(20, 20, 5), sizes)
        assertEquals(45, rides.map { it.id }.distinct().size)
        assertEquals(25, rides.count { it.status == HistoryRideStatus.RECORDING })
        assertEquals(20, rides.count { it.status == HistoryRideStatus.LEGACY })
        assertEquals(45, f.db.rideRowQueries.selectAll().executeAsList().size)
        assertEquals(25, f.db.rideHistoryV2Queries.openRides().executeAsList().size)
    }

    @Test fun seriesMetadataAndGpsUseOnlyTheSelectedStorageFormat() = runTest {
        val f = fixture(); f.old("old")
        f.db.ridePointRowQueries.insert("old", "POWER", Instant.fromEpochMilliseconds(start).toString(), -1, 42.0, 1)
        val origin = f.current("new")
        val point = HistorySample(origin, key, -100.0)
        val fix = HistoryGpsFix(origin, 55.0, 60.0, 2.0, null)
        f.store.append(HistoryBatch("new", listOf(point), listOf(fix), emptyList()))
        assertEquals(listOf(-42.0), f.reader.series("old", oldKey, range).samples.map { it.value })
        assertNull(f.reader.series("old", oldKey, range).origin)
        assertEquals(listOf(point), f.reader.series("new", key, range).samples)
        assertEquals(origin, f.reader.series("new", key, range).origin)
        assertTrue(f.reader.series("old", key, range).samples.isEmpty())
        assertTrue(f.reader.series("new", oldKey, range).samples.isEmpty())
        assertEquals(listOf(source), f.reader.sources("new"))
        assertEquals(listOf("legacy-battery"), f.reader.sources("old").map { it.id })
        assertEquals(listOf(fix), f.reader.gps("new", range).items)
        assertTrue(f.reader.gps("old", range).items.isEmpty())
        assertEquals(HistoryStats(null, null, null, false), f.reader.stats("old", oldKey, range))
        assertNull(f.reader.ride("missing"))
        assertTrue(f.reader.sources("missing").isEmpty())
        assertTrue(f.reader.series("missing", key, range).samples.isEmpty())
    }

    @Test fun aV2RideWithAnUnavailableMetricCannotFallBackToOldRowsWithTheSameId() = runTest {
        val f = fixture(); f.old("same")
        f.db.ridePointRowQueries.insert("same", "POWER", Instant.fromEpochMilliseconds(start).toString(), -1, 4200.0, 1)
        val controller = HistorySource("controller", HistorySourceKind.CONTROLLER, "Recorded controller")
        val origin = f.current("same", recordedSource = controller)
        val motionKey = HistorySeriesKey(controller.id, "speed_kmh")
        f.store.append(HistoryBatch("same", listOf(HistorySample(origin, motionKey, 18.0)), emptyList(), emptyList()))
        assertEquals(HistoryRideStatus.RECORDING, f.reader.ride("same")?.status)
        assertEquals(listOf(controller), f.reader.sources("same"))
        val unavailable = f.reader.series("same", oldKey, range)
        assertTrue(unavailable.samples.isEmpty())
        assertEquals(origin, unavailable.origin)
        assertEquals(listOf(18.0), f.reader.series("same", motionKey, range).samples.map { it.value })
        assertEquals(1, f.db.ridePointRowQueries.selectByRide("same").executeAsList().size)
    }

    @Test fun routeSnapshotCarriesStoredOriginFixesAndGapFactsOnlyFromItsStorageFormat() = runTest {
        val f = fixture(); f.old("old"); f.old("same")
        val origin = f.current("same")
        val fix = HistoryGpsFix(origin.copy(epochMillis = start + 100, elapsedMillis = origin.elapsedMillis + 100), 55.0, 60.0, 2.0, null)
        val markers = listOf(HistoryMarker(fix.tick, HistoryMarkerKind.GPS_GAP),
            HistoryMarker(fix.tick, HistoryMarkerKind.BLE_GAP, source.id))
        f.store.append(HistoryBatch("same", emptyList(), listOf(fix), markers))
        val snapshot = f.reader.route("same", range)
        assertEquals(origin, snapshot.origin); assertEquals(listOf(fix), snapshot.fixes.items)
        assertEquals(markers, snapshot.markers); assertNull(snapshot.fixes.next)
        val narrowed = f.reader.route("same", HistoryRange(start + 101, start + 1000))
        assertTrue(narrowed.fixes.items.isEmpty()); assertTrue(narrowed.markers.isEmpty())
        for (id in listOf("old", "missing")) {
            val empty = f.reader.route(id, range)
            assertNull(empty.origin); assertTrue(empty.fixes.items.isEmpty()); assertTrue(empty.markers.isEmpty())
        }
    }

    @Test fun catalogFractionalTimesMatchTheCursorAndLegacyEndMetadata() = runTest {
        val f = fixture()
        f.db.rideRowQueries.insert("old", "bike", "2026-10-13T03:00:00.999999999+03:00", "2026-10-13T00:00:01.999999999Z")
        f.db.rideRowQueries.insert("negative", "bike", "2026-10-12T21:00:00.999999999-03:00", null)
        f.old("before", start + 998)
        val origin = f.current("v2", start + 999)
        val page = f.reader.rides(HistoryCursor(start + 999, "zzzz"))
        assertEquals(listOf("v2", "old", "negative", "before"), page.items.map { it.id })
        assertEquals(origin, page.items.first().started)
        assertEquals(HistoryTick(start + 999, 0, 0), page.items[1].started)
        assertEquals(HistoryTick(start + 1999, 0, 0), page.items[1].ended)
        assertEquals(HistoryTick(start + 999, 0, 0), page.items[2].started)
        assertEquals(listOf("old", "negative", "before"), f.reader.rides(HistoryCursor(start + 999, "v2")).items.map { it.id })
    }

    @Test fun unifiedReaderRetainsTheConfiguredV2ReadBudgetAndRejectsBadPointBudgets() = runTest {
        val f = fixture(maxReadPages = 1)
        val origin = f.current("new")
        f.store.append(HistoryBatch("new", (0..20000).map { HistorySample(origin, key, it.toDouble()) }, emptyList(), emptyList()))
        val result = f.reader.series("new", key, range, 7)
        assertTrue(result.truncated)
        assertTrue(result.samples.size <= 7)
        assertEquals(19999.0, result.samples.mapNotNull { it.value }.maxOrNull())
        assertEquals(origin, result.origin)
        assertFailsWith<IllegalArgumentException> { f.reader.series("missing", key, range, 2) }
    }
}
