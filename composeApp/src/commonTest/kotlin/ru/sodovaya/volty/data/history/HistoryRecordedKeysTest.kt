package ru.sodovaya.volty.data.history

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.test.runTest
import ru.sodovaya.volty.data.db.VoltyDatabaseProvider
import ru.sodovaya.volty.data.db.VoltyDatabase
import ru.sodovaya.volty.domain.history.*
import kotlin.test.*

class HistoryRecordedKeysTest {
    private val drivers = mutableListOf<JdbcSqliteDriver>()
    private val tick = HistoryTick(10000, 100, 1)
    private data class Fixture(val driver: JdbcSqliteDriver, val database: VoltyDatabase,
        val store: SqlDelightHistoryStore, val reader: SqlDelightHistoryReadRepository)
    private fun fixture(): Fixture {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY); drivers += driver
        VoltyDatabase.Schema.create(driver)
        val provider = VoltyDatabaseProvider(driver)
        return Fixture(driver, provider.database, SqlDelightHistoryStore(provider), SqlDelightHistoryReadRepository(provider))
    }
    @AfterTest fun close() { drivers.forEach { it.close() } }
    private suspend fun Fixture.begin(vararg sources: HistorySource) {
        store.begin(HistoryRide("ride", "retired-bike", tick, null, HistoryRideStatus.RECORDING), sources.toList())
    }
    private fun key(source: String, metric: String, cell: Int? = null) = HistorySeriesKey(source, metric, cell)
    private fun point(key: HistorySeriesKey) = HistorySample(tick, key, null, HistoryUnknownReason.UNAVAILABLE)

    @Test fun realSparseCellsRemainSourceQualifiedWithoutReadingNumericTraces() = runTest {
        val f = fixture()
        f.begin(HistorySource("a", HistorySourceKind.BATTERY, "Same label"), HistorySource("b", HistorySourceKind.BATTERY, "Same label"))
        val a = listOf(key("a", "cell_v", 0), key("a", "cell_v", 2), key("a", "current_a"))
        val b = key("b", "cell_v", 0)
        f.store.append(HistoryBatch("ride", (a + b).map(::point), emptyList(), emptyList()))
        // Deliberately incoherent stored index: do not let a Long→Int conversion
        // invent a negative/different physical cell in the metadata picker.
        f.database.rideHistorySampleQueries.insertSample("ride", 99, "a", "cell_v", 2147483648L,
            tick.epochMillis, tick.elapsedMillis, tick.clockGeneration, null, "UNAVAILABLE")
        val observed = object : HistoryStore by f.store {
            override suspend fun samples(rideId: String, key: HistorySeriesKey, range: HistoryRange, after: HistoryCursor?, limit: Int): HistoryPage<HistorySample> = error("No numeric trace read allowed")
            override suspend fun buckets(rideId: String, key: HistorySeriesKey, range: HistoryRange, after: HistoryCursor?, limit: Int): HistoryPage<HistoryBucket> = error("No numeric bucket read allowed")
        }
        val reader = DefaultHistoryReadRepository(observed)
        assertEquals(HistoryRecordedKeys(a, false), reader.recordedKeys("ride", "a"))
        assertEquals(HistoryRecordedKeys(listOf(b), false), f.reader.recordedKeys("ride", "b"))
    }

    @Test fun summaryOnlyKeysArePresentAndDetailSummaryDuplicatesDoNotConsumeBudget() = runTest {
        val f = fixture(); f.begin(HistorySource("a", HistorySourceKind.BATTERY, "A"))
        val voltage = point(key("a", "voltage_v")).copy(value = 50.0, unknown = null)
        val current = point(key("a", "current_a"))
        val bucket = HistoryBucket(voltage.key, 0, tick, tick, voltage, voltage, null, 1, true)
        f.store.append(HistoryBatch("ride", listOf(current), emptyList(), emptyList(), receiptId = "summary", buckets = listOf(bucket)))
        assertEquals(HistoryRecordedKeys(listOf(current.key, voltage.key), false), f.reader.recordedKeys("ride", "a", 2))
        f.store.append(HistoryBatch("ride", listOf(voltage), emptyList(), emptyList()))
        assertEquals(HistoryRecordedKeys(listOf(current.key, voltage.key), false), f.reader.recordedKeys("ride", "a", 2))
        assertEquals(HistoryRecordedKeys(listOf(current.key), true), f.reader.recordedKeys("ride", "a", 1))
    }

    @Test fun forbiddenFutureAndMalformedKeysAreFilteredBeforeTheMetadataBudget() = runTest {
        val f = fixture(); f.begin(HistorySource("section", HistorySourceKind.BATTERY_ASSEMBLY, "Section"))
        val bad = (0..100).flatMap { index -> listOf(
            point(key("section", "cell_v", index)), point(key("section", "future-$index")),
            point(key("section", "voltage_v", index))) }
        val good = point(key("section", "voltage_v"))
        f.store.append(HistoryBatch("ride", bad + good, emptyList(), emptyList()))
        assertEquals(HistoryRecordedKeys(listOf(good.key), false), f.reader.recordedKeys("ride", "section", 1))
        assertEquals(HistoryRecordedKeys(emptyList(), false), f.reader.recordedKeys("ride", "absent", 1))
    }

    @Test fun thousandRecordedCellsRemainSparseAndHaveAnExplicitReadCap() = runTest {
        val f = fixture(); f.begin(HistorySource("pack", HistorySourceKind.BATTERY, "A"))
        val keys = (0..1000).map { key("pack", "cell_v", it * 2) }
        f.store.append(HistoryBatch("ride", keys.map(::point), emptyList(), emptyList()))
        assertEquals(HistoryRecordedKeys(keys, false), f.reader.recordedKeys("ride", "pack"))
        assertEquals(HistoryRecordedKeys(keys.take(1000), true), f.reader.recordedKeys("ride", "pack", 1000))
    }

    @Test fun legacyKeysRetainOnlyRecognizedScalarFactsAndNeverBorrowShadowedOldRows() = runTest {
        val f = fixture()
        f.database.rideRowQueries.insert("old", "retired", "2026-10-15T00:00:00Z", null)
        for ((metric, cell) in listOf("POWER" to -1L, "CURRENT" to -1L, "FUTURE" to -1L, "VOLTAGE" to 7L))
            f.database.ridePointRowQueries.insert("old", metric, "2026-10-15T00:00:00Z", cell, 4200.0, 0)
        val expected = listOf(key("legacy-battery", "current_a"), key("legacy-battery", "power_w"))
        assertEquals(HistoryRecordedKeys(expected, false), f.reader.recordedKeys("old", "legacy-battery"))
        assertEquals(HistoryRecordedKeys(emptyList(), false), f.reader.recordedKeys("old", "legacy-motion"))
        f.store.begin(HistoryRide("old", "new", tick, null, HistoryRideStatus.RECORDING),
            listOf(HistorySource("virtual", HistorySourceKind.DERIVED_BATTERY, "Virtual")))
        assertEquals(HistoryRecordedKeys(emptyList(), false), f.reader.recordedKeys("old", "legacy-battery"))
    }

    @Test fun missingParentsAndInvalidBudgetsCannotInventSeriesOrPerformStorageReads() = runTest {
        val f = fixture(); f.begin(HistorySource("a", HistorySourceKind.BATTERY, "A"))
        f.store.append(HistoryBatch("ride", listOf(point(key("a", "voltage_v"))), emptyList(), emptyList()))
        f.driver.execute(null, "DELETE FROM HistoryRideV2 WHERE id = 'ride'", 0)
        assertEquals(1, f.database.rideHistorySampleQueries.samplesForReceipt("ride", 0, Long.MAX_VALUE).executeAsList().size)
        assertEquals(1, f.database.rideHistorySourceQueries.sourcesForRide("ride").executeAsList().size)
        assertEquals(HistoryRecordedKeys(emptyList(), false), f.reader.recordedKeys("ride", "a"))
        assertEquals(HistoryRecordedKeys(emptyList(), false), f.store.recordedKeys("ride", "a"))
        // A premature format lookup must fail differently from budget validation.
        f.driver.execute(null, "DROP TABLE HistoryRideV2", 0)
        val denied = object : HistoryStore by f.store {
            override suspend fun ride(rideId: String): HistoryRide? = error("Invalid budget must fail before lookup")
        }
        val reader = DefaultHistoryReadRepository(denied)
        for (limit in listOf(0, 20001)) {
            assertFailsWith<IllegalArgumentException> { reader.recordedKeys("ride", "a", limit) }
            assertFailsWith<IllegalArgumentException> { f.reader.recordedKeys("ride", "a", limit) }
        }
    }
}
