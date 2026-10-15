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
class LegacyHistoryReadRepositoryTest {
    private val drivers = mutableListOf<JdbcSqliteDriver>()
    private val start = Instant.parse("2026-10-13T00:00:00Z").toEpochMilliseconds()
    private val key = HistorySeriesKey("legacy-battery", "power_w")
    private val range = HistoryRange(start, start + 30000)
    @AfterTest fun close() { drivers.forEach { it.close() } }
    private fun fixture(): Pair<VoltyDatabase, LegacyHistoryReader> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY); drivers += driver
        VoltyDatabase.Schema.create(driver)
        val provider = VoltyDatabaseProvider(driver)
        provider.database.rideRowQueries.insert("old", "bike", Instant.fromEpochMilliseconds(start).toString(), null)
        return provider.database to LegacyHistoryReader(provider)
    }

    @Test fun metadataUsesOnlyRecordedMetricKeysAndPreservesTheLegacyClockContract() = runTest {
        val (db, reader) = fixture()
        db.ridePointRowQueries.insert("old", "POWER", Instant.fromEpochMilliseconds(start).toString(), -1, 42.0, 1)
        db.ridePointRowQueries.insert("old", "SPEED", Instant.fromEpochMilliseconds(start).toString(), -1, 10.0, 1)
        val ride = assertNotNull(reader.ride("old"))
        assertEquals(HistoryRideStatus.LEGACY, ride.status)
        assertEquals(HistoryTick(start, 0, 0), ride.started)
        assertEquals("bike", ride.vehicleId)
        assertNull(ride.ended)
        assertEquals(listOf(HistorySourceKind.VEHICLE_BATTERY, HistorySourceKind.VEHICLE_MOTION), reader.sources("old").map { it.kind })
        assertEquals(listOf("legacy-battery", "legacy-motion"), reader.sources("old").map { it.id })
        assertNull(reader.ride("missing"))
        assertTrue(reader.sources("missing").isEmpty())
    }

    @Test fun rangesKeepCanonicalSignAndUnknownWithoutFabricatingOriginStatsOrRoute() = runTest {
        val (db, reader) = fixture()
        db.ridePointRowQueries.insert("old", "POWER", Instant.fromEpochMilliseconds(start).toString(), -1, 42.0, 1)
        db.ridePointRowQueries.insert("old", "POWER", Instant.fromEpochMilliseconds(start + 1000).toString(), -1, 0.0, 1)
        db.ridePointRowQueries.insert("old", "POWER", Instant.fromEpochMilliseconds(start + 2000).toString(), -1, 100.0, 0)
        db.ridePointRowQueries.insert("old", "CURRENT", Instant.fromEpochMilliseconds(start).toString(), -1, 999.0, 1)
        val result = reader.series("old", key, HistoryRange(start + 1000, start + 2000))
        assertEquals(listOf(-0.0, null), result.samples.map { it.value })
        assertEquals(HistoryUnknownReason.LEGACY, result.samples.last().unknown)
        assertTrue(result.samples.all { it.tick.elapsedMillis == 0L && it.tick.clockGeneration == 0L && it.key == key })
        assertNull(result.origin)
        assertTrue(result.markers.isEmpty())
        assertFalse(result.truncated)
        assertEquals(-42.0, reader.series("old", key, HistoryRange(start, start)).samples.single().value)
        assertEquals(HistoryStats(null, null, null, false), reader.stats("old", key, range))
        assertTrue(reader.gps("old", range).items.isEmpty())
        assertNull(reader.gps("old", range).next)
        assertEquals(42.0, db.ridePointRowQueries.selectByRide("old").executeAsList().first { it.metricKey == "POWER" }.value_)
    }

    @Test fun anotherMetricCannotConsumeTheSelectedSeriesReadBudget() = runTest {
        val (db, reader) = fixture()
        db.transaction {
            repeat(20001) { db.ridePointRowQueries.insert("old", "SPEED", Instant.fromEpochMilliseconds(start + it).toString(), -1, 20.0, 1) }
            db.ridePointRowQueries.insert("old", "POWER", Instant.fromEpochMilliseconds(start + 25000).toString(), -1, 4200.0, 1)
        }
        val oldGlobalRead = assertNotNull(reader.load("old"))
        assertTrue(oldGlobalRead.truncated)
        assertTrue(oldGlobalRead.samples.none { it.key == key }) // Driver proves the former starvation shape.
        val result = reader.series("old", key, range)
        assertEquals(listOf(-4200.0), result.samples.map { it.value })
        assertFalse(result.truncated)
    }

    @Test fun legacySeriesBudgetIsExplicitAndThinningCannotCreateExactPeaks() = runTest {
        val (db, reader) = fixture()
        db.transaction {
            repeat(20001) { db.ridePointRowQueries.insert("old", "POWER", Instant.fromEpochMilliseconds(start + it).toString(), -1, it.toDouble(), 1) }
        }
        val result = reader.series("old", key, range, 7)
        assertTrue(result.samples.size <= 7)
        assertTrue(result.truncated)
        assertEquals(start, result.samples.first().tick.epochMillis)
        assertEquals(start + 19999, result.samples.last().tick.epochMillis)
        assertEquals(-19999.0, result.samples.mapNotNull { it.value }.minOrNull())
        assertEquals(HistoryStats(null, null, null, false), reader.stats("old", key, range))
        assertEquals(20001, db.ridePointRowQueries.selectByRide("old").executeAsList().size)
    }

    @Test fun legacyRidePagesUseStableIdsForEqualTimesAndKeepEveryStoredRide() = runTest {
        val (db, reader) = fixture()
        db.rideRowQueries.delete("old")
        repeat(45) { db.rideRowQueries.insert("r${it.toString().padStart(2, '0')}", "bike", Instant.fromEpochMilliseconds(start).toString(), null) }
        val ids = mutableListOf<String>()
        var after: HistoryCursor? = null
        val pageSizes = mutableListOf<Int>()
        repeat(3) {
            val page = reader.rides(after)
            pageSizes += page.items.size; ids += page.items.map { it.id }; after = page.next
            assertEquals(it < 2, page.truncated)
        }
        assertEquals(listOf(20, 20, 5), pageSizes)
        assertEquals((44 downTo 0).map { "r${it.toString().padStart(2, '0')}" }, ids)
        assertNull(after)
        assertEquals(45, db.rideRowQueries.selectAll().executeAsList().size)
    }

    @Test fun rangeUsesExactTruncatedMillisecondsForFractionalAndOffsetInstants() = runTest {
        val (db, reader) = fixture()
        for (timestamp in listOf("2026-10-13T00:00:00.999999999Z", "2026-10-13T03:00:00.999999999+03:00",
            "2026-10-12T21:00:00.999999999-03:00")) {
            db.ridePointRowQueries.insert("old", "POWER", timestamp, -1, 7.0, 1)
        }
        val result = reader.series("old", key, HistoryRange(start + 999, start + 999))
        assertEquals(3, result.samples.size)
        assertTrue(result.samples.all { it.tick == HistoryTick(start + 999, 0, 0) && it.value == -7.0 })
        assertTrue(reader.series("old", key, HistoryRange(start + 1000, start + 1000)).samples.isEmpty())
        db.rideRowQueries.insert("later", "bike", "2026-10-13T00:00:00.999999999Z", null)
        db.rideRowQueries.insert("offset", "bike", "2026-10-13T03:00:00.999999999+03:00", null)
        val rides = reader.rides(HistoryCursor(start + 999, "zzzz"))
        assertEquals(listOf("offset", "later", "old"), rides.items.map { it.id })
    }

    @Test fun unavailableMetricIndexAndRideHaveNoNumericFallbackAndBudgetsAreValidated() = runTest {
        val (_, reader) = fixture()
        for (invalid in listOf(key.copy(sourceId = "other"), key.copy(metricId = "future"), key.copy(cellIndex = 0))) {
            assertTrue(reader.series("old", invalid, range).samples.isEmpty())
        }
        assertTrue(reader.series("missing", key, range).samples.isEmpty())
        assertEquals(HistoryStats(null, null, null, false), reader.stats("missing", key, range))
        for (budget in listOf(2, 601)) assertFailsWith<IllegalArgumentException> { reader.series("old", key, range, budget) }
    }

    @Test fun orphanedPointsCannotInventARecordedRideOrItsSource() = runTest {
        val (db, reader) = fixture()
        db.ridePointRowQueries.insert("old", "POWER", Instant.fromEpochMilliseconds(start).toString(), -1, 4200.0, 1)
        db.rideRowQueries.delete("old") // Deliberately incoherent old database, foreign keys disabled.
        assertEquals(1, db.ridePointRowQueries.selectByRide("old").executeAsList().size)
        assertNull(reader.ride("old"))
        val sources = reader.sources("old")
        val series = reader.series("old", key, range)
        assertTrue(sources.isEmpty())
        assertTrue(series.samples.isEmpty())
    }
}
