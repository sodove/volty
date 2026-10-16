package ru.sodovaya.volty.data.history

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import ru.sodovaya.volty.data.db.VoltyDatabase
import ru.sodovaya.volty.data.db.VoltyDatabaseProvider
import ru.sodovaya.volty.domain.history.*
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryLongRecordingAcceptanceTest {
    @Test fun twoHourProjectedRecordingKeepsSubBucketPeakAndIndependentQualityBeyondDetailPage() = runTest(timeout = 120.seconds) {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(null, "PRAGMA foreign_keys=ON", 0)
        VoltyDatabase.Schema.create(driver)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = SqlDelightHistoryStore(VoltyDatabaseProvider(driver), dispatcher)
        val writer = BufferedHistoryWriter(store, dispatcher, emptyFlow(), projectRecording = true)
        val origin = HistoryTick(10000, 500, 1)
        val power = HistorySeriesKey("pack", "power_w")
        val cell = HistorySeriesKey("pack", "cell_v", 0)
        fun tick(receipt: Int) = HistoryTick(origin.epochMillis + receipt * 100L,
            origin.elapsedMillis + receipt * 100L, origin.clockGeneration)
        try {
            writer.begin(7, HistoryRide("ride", "bike", origin, null, HistoryRideStatus.RECORDING),
                listOf(HistorySource("pack", HistorySourceKind.BATTERY, "Battery")))
            for (receipt in 0..72000) {
                val samples = listOf(
                    HistorySample(tick(receipt), power, if (receipt == 43217) -4200.0 else -1000.0),
                    if (receipt in 60000..60199) HistorySample(tick(receipt), cell, null, HistoryUnknownReason.PARTIAL_SOURCE)
                    else HistorySample(tick(receipt), cell, 0.0),
                )
                assertEquals(HistoryOffer.ACCEPTED, writer.offer(7, HistoryBatch("ride", samples, emptyList(), emptyList())))
                // Finite deterministic backpressure; no wall-clock delays or
                // unbounded virtual-time advance through the writer's loops.
                if ((receipt + 1) % 64 == 0) writer.flush()
            }
            writer.finish(7, tick(72000), HistoryRideStatus.COMPLETED, "ride")
            runCurrent()
            assertEquals(144002L, writer.diagnostics.value.committedEvents)
            assertEquals(0L, writer.diagnostics.value.droppedEvents)
            assertEquals(0L, writer.diagnostics.value.pendingEvents)
            assertNull(writer.diagnostics.value.failure)
            val range = HistoryRange(origin.epochMillis, tick(72000).epochMillis)
            val firstDetailPage = store.samples("ride", power, range, null)
            assertEquals(20000, firstDetailPage.items.size)
            assertNotNull(firstDetailPage.next, "Fixture must exceed one detail page")
            val reader = DefaultHistoryReadRepository(store)
            val series = reader.series("ride", power, range, 61)
            assertFalse(series.truncated); assertTrue(series.samples.size <= 61)
            assertTrue(series.samples.any { it.value == -4200.0 && it.tick == tick(43217) })
            assertTrue(series.samples.all { it.unknown == null })
            val stats = reader.stats("ride", power, range)
            assertEquals(-4200.0, stats.minimum); assertEquals(-1000.0, stats.maximum)
            assertEquals(2000.0 + 3200.0 * 0.1 / 3600, assertNotNull(stats.energyWh), 1e-6)
            val summary = store.buckets("ride", power, range, null).items
            assertTrue(summary.isNotEmpty() && summary.all { it.complete }, "Reach genuinely complete full-stream evidence")
            val cellStats = reader.stats("ride", cell, range)
            assertEquals(0.0, cellStats.minimum); assertEquals(0.0, cellStats.maximum)
            assertFalse(cellStats.complete, "A partial cell must not inherit the healthy power series quality")
            assertTrue(stats.complete, "A complete earned summary must not become incomplete at the detail page limit")
            assertEquals(HistoryRideStatus.COMPLETED, store.ride("ride")?.status)
        } finally { writer.close(); driver.close() }
    }
}
