package ru.sodovaya.volty.data.history

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import ru.sodovaya.volty.data.db.VoltyDatabase
import ru.sodovaya.volty.data.db.VoltyDatabaseProvider
import ru.sodovaya.volty.domain.history.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryRecordingWriterIntegrationTest {
    @Test fun fullStreamSummaryAndThinnedDetailSurviveRealCommitWithLostAcknowledgement() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(null, "PRAGMA foreign_keys=ON", 0)
        VoltyDatabase.Schema.create(driver)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = SqlDelightHistoryStore(VoltyDatabaseProvider(driver), dispatcher)
        var loseAck = true
        val attempts = mutableListOf<HistoryBatch>()
        val transport = object : HistoryStore by store {
            override suspend fun append(batch: HistoryBatch) {
                attempts += batch
                store.append(batch)
                if (loseAck) { loseAck = false; error("Committed, ACK lost") }
            }
        }
        val writer = BufferedHistoryWriter(transport, dispatcher, emptyFlow(), projectRecording = true)
        try {
            val origin = HistoryTick(10000, 100, 1)
            val key = HistorySeriesKey("pack", "power_w")
            val raw = (0..499).map {
                HistorySample(HistoryTick(10000L + it, 100L + it, 1), key, if (it == 221) -9999.0 else -100.0)
            }
            writer.begin(7, HistoryRide("ride", "bike", origin, null, HistoryRideStatus.RECORDING),
                listOf(HistorySource("pack", HistorySourceKind.BATTERY, "A")))
            assertEquals(HistoryOffer.ACCEPTED, writer.offer(7, HistoryBatch("ride", raw, emptyList(), emptyList())))
            // 500 events trigger the automatic drain before an explicit flush on this real IO path.
            runCurrent()
            assertEquals(1, attempts.size)
            assertNotNull(writer.diagnostics.value.failure)
            assertFalse(writer.diagnostics.value.accepting)
            writer.flush()
            assertEquals(2, attempts.size)
            assertEquals(attempts.first(), attempts.last())
            writer.finish(7, raw.last().tick, HistoryRideStatus.COMPLETED, "ride")
            val range = HistoryRange(10000, 10500)
            val stats = store.stats("ride", key, range)
            val expected = historyStats(raw, emptyList(), -1.0)
            assertEquals(expected.minimum, stats.minimum)
            assertEquals(expected.maximum, stats.maximum)
            assertEquals(assertNotNull(expected.energyWh), assertNotNull(stats.energyWh), 1e-12)
            assertTrue(stats.complete)
            assertEquals(500, store.buckets("ride", key, range, null).items.single().sampleCount)
            val detail = store.samples("ride", key, range, null).items
            assertTrue(detail.size <= 4)
            assertEquals(raw.first(), detail.first())
            assertEquals(raw.last(), detail.last())
            assertEquals(HistoryRideStatus.COMPLETED, store.ride("ride")?.status)
        } finally { writer.close(); driver.close() }
    }
}
