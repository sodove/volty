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
import ru.sodovaya.volty.data.bms.JbdBmsProtocol
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryRecordingWriterIntegrationTest {
    @Test fun coalescedJbdFramesKeepEarnedPeakWithoutInventingMeasurementIntervals() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        VoltyDatabase.Schema.create(driver)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = SqlDelightHistoryStore(VoltyDatabaseProvider(driver), dispatcher)
        val writer = BufferedHistoryWriter(store, dispatcher, emptyFlow(), projectRecording = true)
        val controller = RideRecordingController(writer, HistoryRecordingDemand {}, { "ride" }, { store.recoverInterrupted() })
        val source = HistorySource("pack", HistorySourceKind.BATTERY, "A")
        val origin = HistoryTick(10000, 100, 1)
        val receipt = HistoryTick(10001, 101, 1)
        fun frame(current: Int): ByteArray {
            val data = ByteArray(23)
            data[0] = (5000 shr 8).toByte(); data[1] = 5000.toByte()
            data[2] = (current shr 8).toByte(); data[3] = current.toByte(); data[19] = 80
            val body = byteArrayOf(0, data.size.toByte()) + data
            val checksum = (0x10000 - body.sumOf { it.toInt() and 255 }) and 65535
            return byteArrayOf(0xdd.toByte(), 3) + body + byteArrayOf((checksum shr 8).toByte(), checksum.toByte(), 0x77)
        }
        try {
            controller.execute(RecordingInput.Connected("bike", 7, origin), listOf(source))
            val protocol = JbdBmsProtocol()
            var accepted = 0
            protocol.telemetryObserver = { observation ->
                val points = historyTelemetrySamples(source, receipt, observation)
                if (controller.offerTelemetry(7, HistoryBatch("ride", points, emptyList(), emptyList())) == HistoryOffer.ACCEPTED) accepted++
            }
            protocol.onNotification(frame(-400) + frame(-200) + frame(-200))
            assertEquals(3, accepted)
            controller.execute(RecordingInput.Stop(HistoryTick(10010, 110, 1), 7, "ride"))
            val key = HistorySeriesKey("pack", "power_w")
            val range = HistoryRange(10000, 10020)
            val summary = store.stats("ride", key, range)
            assertEquals(-200.0, summary.minimum)
            assertEquals(-100.0, summary.maximum)
            assertNull(summary.energyWh, "One receipt time cannot prove intervals between coalesced frames")
            assertEquals(3, store.buckets("ride", key, range, null).items.single().sampleCount)
            assertEquals(listOf(-200.0, -100.0), store.samples("ride", key, range, null).items.map { it.value })
            assertEquals(0, protocol.telemetryObserverFailures)
        } finally { writer.close(); driver.close() }
    }

    @Test fun controllerRetainsOneRideAcrossReconnectAndGpsGapThenDurablyStopsAtLastFact() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        VoltyDatabase.Schema.create(driver)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = SqlDelightHistoryStore(VoltyDatabaseProvider(driver), dispatcher)
        val writer = BufferedHistoryWriter(store, dispatcher, emptyFlow(), projectRecording = true)
        val demands = mutableListOf<Boolean>()
        val controller = RideRecordingController(writer, HistoryRecordingDemand { demands += it }, { "ride" }, { store.recoverInterrupted() })
        fun tick(delta: Long) = HistoryTick(10000 + delta, 100 + delta, 1)
        val key = HistorySeriesKey("pack", "power_w")
        fun batch(delta: Long, watts: Double) = HistoryBatch("ride", listOf(HistorySample(tick(delta), key, watts)), emptyList(), emptyList())
        val sources = listOf(HistorySource("pack", HistorySourceKind.BATTERY, "A"))
        try {
            controller.execute(RecordingInput.Connected("bike", 7, tick(0)), sources)
            assertEquals(HistoryOffer.ACCEPTED, controller.offerTelemetry(7, batch(10, -100.0)))
            controller.execute(RecordingInput.LinkLost(7, tick(30)))
            assertEquals(HistoryOffer.RETIRED, controller.offerTelemetry(7, batch(31, -9999.0)))
            val gps = HistoryGpsFix(tick(35), 56.0, 60.0, 5.0, null)
            assertEquals(HistoryOffer.ACCEPTED, controller.offerGps("ride", gps))
            controller.execute(RecordingInput.Connected("bike", 8, tick(40)), sources)
            assertEquals(HistoryOffer.RETIRED, controller.offerTelemetry(7, batch(45, -9999.0)))
            assertEquals(HistoryOffer.ACCEPTED, controller.offerTelemetry(8, batch(50, -200.0)))
            controller.execute(RecordingInput.Stop(tick(49), 8, "ride"))
            val range = HistoryRange(10000, 10100)
            assertEquals(listOf("ride"), store.rides(null).items.map { it.id })
            assertEquals(HistoryRideStatus.COMPLETED, store.ride("ride")?.status)
            assertEquals(tick(50), store.ride("ride")?.ended)
            assertEquals(listOf(gps), store.gps("ride", range, null).items)
            assertEquals(listOf(-100.0, -200.0), store.samples("ride", key, range, null).items.map { it.value })
            assertEquals(2, store.buckets("ride", key, range, null).items.single().sampleCount)
            assertFalse(store.stats("ride", key, range).complete)
            assertNull(store.stats("ride", key, range).energyWh)
            assertEquals(listOf(true, false), demands)
        } finally { writer.close(); driver.close() }
    }

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
