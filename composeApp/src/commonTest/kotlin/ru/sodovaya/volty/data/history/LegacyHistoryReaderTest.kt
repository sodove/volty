package ru.sodovaya.volty.data.history

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.test.runTest
import ru.sodovaya.volty.data.db.VoltyDatabase
import ru.sodovaya.volty.data.db.VoltyDatabaseProvider
import ru.sodovaya.volty.domain.history.*
import kotlin.test.*

class LegacyHistoryReaderTest {
    private val drivers = mutableListOf<JdbcSqliteDriver>()
    @AfterTest fun close() { drivers.forEach { it.close() } }
    private fun fixture(): Pair<VoltyDatabase, LegacyHistoryReader> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY); drivers += driver
        VoltyDatabase.Schema.create(driver)
        val provider = VoltyDatabaseProvider(driver)
        provider.database.rideRowQueries.insert("old", "bike", "2026-10-13T00:00:00Z", null)
        return provider.database to LegacyHistoryReader(provider)
    }
    @Test fun signIsInvertedExactlyOnceAndUnknownNeverBecomesMeasuredZero() = runTest {
        val (db, reader) = fixture()
        db.ridePointRowQueries.insert("old", "POWER", "2026-10-13T00:00:00Z", -1, 420.0, 1)
        db.ridePointRowQueries.insert("old", "CURRENT", "2026-10-13T00:00:01Z", -1, 99.0, 0)
        db.ridePointRowQueries.insert("old", "SPEED", "2026-10-13T00:00:02Z", -1, 0.0, 1)
        val snapshot = assertNotNull(reader.load("old"))
        assertEquals(HistoryRideStatus.LEGACY, snapshot.ride.status)
        assertEquals(listOf(-420.0, null, 0.0), snapshot.samples.map { it.value })
        assertEquals(HistoryUnknownReason.LEGACY, snapshot.samples[1].unknown)
        assertEquals(listOf("power_w", "current_a", "speed_kmh"), snapshot.samples.map { it.key.metricId })
        assertNull(snapshot.origin)
        assertEquals(HistoryStats(null, null, null, false), snapshot.stats)
        assertEquals(snapshot, reader.load("old"))
        assertEquals(420.0, db.ridePointRowQueries.selectByRide("old").executeAsList().first().value_)
    }
    @Test fun boundedReadOrdersFractionalWallTimestampsAndReportsUnsupportedMetrics() = runTest {
        val (db, reader) = fixture()
        db.ridePointRowQueries.insert("old", "SPEED", "2026-10-13T00:00:00.001Z", -1, 2.0, 1)
        db.ridePointRowQueries.insert("old", "SPEED", "2026-10-13T00:00:00Z", -1, 1.0, 1)
        db.ridePointRowQueries.insert("old", "FUTURE_FIELD", "2026-10-13T00:00:01Z", -1, 9.0, 1)
        val snapshot = assertNotNull(reader.load("old", 1))
        assertEquals(listOf(1.0), snapshot.samples.map { it.value })
        assertTrue(snapshot.truncated)
        assertEquals(setOf("FUTURE_FIELD"), snapshot.unmappedMetricKeys)
        assertEquals(3, db.ridePointRowQueries.selectByRide("old").executeAsList().size)
    }
    @Test fun absentRideAndInvalidLimitAreDistinct() = runTest {
        val (_, reader) = fixture()
        assertNull(reader.load("missing"))
        assertFailsWith<IllegalArgumentException> { reader.load("old", 0) }
    }
}
