package ru.sodovaya.volty.data.ble

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import ru.sodovaya.volty.data.bms.*
import ru.sodovaya.volty.data.history.*
import ru.sodovaya.volty.domain.history.*
import ru.sodovaya.volty.domain.model.*
import kotlin.test.*
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class KableBmsRepositoryHistoryFunnelTest {
    private val source = HistorySource("pack-a", HistorySourceKind.BATTERY, "A")
    private fun tick(delta: Long = 0) = HistoryTick(10000 + delta, 100 + delta, 1)
    private fun vehicle() = Vehicle(id = "bike", name = "Bike", iconKey = "bicycle",
        packs = listOf(Pack(index = 0, label = "A", bmsType = BmsType.JK_BMS, bmsAddress = "AA:BB:CC:DD:EE:01")),
        topology = PackTopology.PARALLEL, chemistry = Chemistry.LI_ION_NMC, createdAt = Instant.fromEpochSeconds(0))
    private class Emitter : BmsProtocol() {
        override val uuids = BmsUuids("s", "n", "w")
        override fun handshakeCommands() = emptyList<ByteArray>()
        override fun pollCommands() = emptyList<ByteArray>()
        override fun latestData(packIndex: Int): BmsData? = null
        override fun reset() {}
        override fun onNotification(data: ByteArray) {
            data.forEach { value -> observeTelemetry(TelemetryOrigin.BATTERY, 0) {
                listOf(TelemetryReading.measured(TelemetryMetric.CURRENT, value.toDouble()))
            } }
        }
    }
    private class Writer : HistoryWriter {
        override val diagnostics = MutableStateFlow(HistoryWriterDiagnostics())
        val samples = mutableListOf<HistoryBatch>()
        val statuses = mutableListOf<HistoryRideStatus>()
        var failSamples = false
        override suspend fun begin(generation: Long, ride: HistoryRide, sources: List<HistorySource>) {}
        override fun offer(generation: Long, batch: HistoryBatch): HistoryOffer {
            if (batch.samples.isNotEmpty() && failSamples) error("injected writer failure")
            if (batch.samples.isNotEmpty()) samples += batch
            return HistoryOffer.ACCEPTED
        }
        override suspend fun finish(generation: Long, tick: HistoryTick, status: HistoryRideStatus, rideId: String?) { statuses += status }
        override suspend fun flush() {}
        override suspend fun delete(rideId: String) {}
        override suspend fun close() {}
    }
    private fun controller(writer: Writer): RideRecordingController {
        var n = 0
        return RideRecordingController(writer, HistoryRecordingDemand {}, { "ride-${++n}" }, { 0 })
    }

    @Test fun recordingFailureKeepsLiveConsumerAndRemainsInterruptedAcrossReconnect() = bleRepositoryTest { repo ->
        val v = vehicle(); repo.installLinksForTest(v, v.packs.single().bmsAddress, BmsType.JK_BMS)
        val channel = assertNotNull(repo.sampleFunnelChannelForTest())
        val writer = Writer(); val c = controller(writer)
        c.execute(RecordingInput.Connected("bike", 7, tick()), listOf(source))
        val owner = SampleSessionOwnership { true }; var queued = 0; var receiptTick = tick(1)
        val capture = historySessionTelemetryCapture(Emitter(), "attempt", c, { receiptTick }, owner::isCurrent,
            mapOf(HistoryTelemetrySource(TelemetryOrigin.BATTERY, 0) to source), {
                if (channel.trySend(HistoryTelemetrySample(it, c, owner)).isSuccess) queued++
            })
        writer.failSamples = true
        capture.processNotification(byteArrayOf(7))
        assertTrue(channel.trySend(PackSample(0, BmsData(current = 3f, isConnected = true), emptyList())).isSuccess)
        advanceUntilIdle()
        assertEquals(1, queued)
        assertEquals(3f, repo.activeData.value.current, "history failure must not terminate the shared live consumer")
        assertEquals("ride-1", assertNotNull(c.telemetryFailure.value).rideId)
        c.execute(RecordingInput.LinkLost(7, tick(2)))
        c.execute(RecordingInput.Connected("bike", 8, tick(3)), listOf(source))
        c.execute(RecordingInput.Stop(tick(4), 8, "ride-1"))
        assertEquals(listOf(HistoryRideStatus.INTERRUPTED), writer.statuses)
        writer.failSamples = false
        c.execute(RecordingInput.Start("bike", 8, tick(5)), listOf(source))
        assertNull(c.telemetryFailure.value, "new ride owns fresh recording health")
        receiptTick = tick(6)
        capture.processNotification(byteArrayOf(9))
        advanceUntilIdle()
        assertEquals("ride-2", writer.samples.single().rideId)
        c.execute(RecordingInput.Stop(tick(7), 8, "ride-2"))
        assertEquals(listOf(HistoryRideStatus.INTERRUPTED, HistoryRideStatus.COMPLETED), writer.statuses)
    }

    @Test fun rawHistoryQueueKeepsEveryPeakWithoutPublishingDashboardSnapshots() = bleRepositoryTest { repo ->
        val v = vehicle(); repo.installLinksForTest(v, v.packs.single().bmsAddress, BmsType.JK_BMS)
        val channel = assertNotNull(repo.sampleFunnelChannelForTest())
        val writer = Writer(); val c = controller(writer)
        c.execute(RecordingInput.Connected("bike", 7, tick()), listOf(source))
        val owner = SampleSessionOwnership { true }; val protocol = Emitter(); var queued = 0
        val dashboard = repo.activeData.value
        val capture = historySessionTelemetryCapture(protocol, "attempt", c, { tick(1) }, owner::isCurrent,
            mapOf(HistoryTelemetrySource(TelemetryOrigin.BATTERY, 0) to source), {
                if (channel.trySend(HistoryTelemetrySample(it, c, owner)).isSuccess) queued++
            })
        capture.processNotification(byteArrayOf(1, 99, 0))
        advanceUntilIdle()
        assertEquals(3, queued)
        assertEquals(0L, protocol.telemetryObserverFailures)
        assertEquals(listOf(1.0, 99.0, 0.0), writer.samples.flatMap { it.samples }.map { it.value })
        assertSame(dashboard, repo.activeData.value)
    }

    @Test fun rawHistoryQueueRejectsRetiredAttemptWhileHealthyAttemptKeepsRecording() = bleRepositoryTest { repo ->
        val v = vehicle(); repo.installLinksForTest(v, v.packs.single().bmsAddress, BmsType.JK_BMS)
        val channel = assertNotNull(repo.sampleFunnelChannelForTest())
        val writer = Writer(); val c = controller(writer)
        c.execute(RecordingInput.Connected("bike", 7, tick()), listOf(source))
        val oldOwner = SampleSessionOwnership { true }; val newOwner = SampleSessionOwnership { true }
        var queued = 0
        fun capture(owner: SampleSessionOwnership, id: String) = historySessionTelemetryCapture(Emitter(), id, c,
            { tick(1) }, owner::isCurrent, mapOf(HistoryTelemetrySource(TelemetryOrigin.BATTERY, 0) to source), {
                if (channel.trySend(HistoryTelemetrySample(it, c, owner)).isSuccess) queued++
            })
        val old = capture(oldOwner, "old"); val fresh = capture(newOwner, "fresh")
        repo.beforeVehicleDataPublishForTest = {
            repo.beforeVehicleDataPublishForTest = null
            old.processNotification(byteArrayOf(7))
            fresh.processNotification(byteArrayOf(8))
            assertEquals(2, queued)
            oldOwner.retire()
        }
        assertTrue(channel.trySend(PackSample(0, BmsData(current = 3f, isConnected = true), emptyList())).isSuccess)
        advanceUntilIdle()
        assertEquals(2, queued)
        assertEquals(listOf(8.0), writer.samples.flatMap { it.samples }.map { it.value })
        assertEquals(3f, repo.activeData.value.current)
    }

    @Test fun rawHistoryQueueNeverReassignsPendingEnvelopeWhenNewRideStarts() = bleRepositoryTest { repo ->
        val v = vehicle(); repo.installLinksForTest(v, v.packs.single().bmsAddress, BmsType.JK_BMS)
        val channel = assertNotNull(repo.sampleFunnelChannelForTest())
        val writer = Writer(); val c = controller(writer)
        c.execute(RecordingInput.Connected("bike", 7, tick()), listOf(source))
        val owner = SampleSessionOwnership { true }; var queued = 0; var receiptTick = tick(1)
        val capture = historySessionTelemetryCapture(Emitter(), "attempt", c, { receiptTick }, owner::isCurrent,
            mapOf(HistoryTelemetrySource(TelemetryOrigin.BATTERY, 0) to source), {
                if (channel.trySend(HistoryTelemetrySample(it, c, owner)).isSuccess) queued++
            })
        repo.beforeVehicleDataPublishForTest = {
            repo.beforeVehicleDataPublishForTest = null
            capture.processNotification(byteArrayOf(7))
            runBlocking {
                c.execute(RecordingInput.Stop(tick(2), 7, "ride-1"))
                c.execute(RecordingInput.Start("bike", 7, tick(3)), listOf(source))
            }
            receiptTick = tick(4)
            capture.processNotification(byteArrayOf(9))
        }
        assertTrue(channel.trySend(PackSample(0, BmsData(current = 3f, isConnected = true), emptyList())).isSuccess)
        advanceUntilIdle()
        assertEquals(2, queued)
        assertEquals(listOf(9.0), writer.samples.flatMap { it.samples }.map { it.value })
        assertEquals("ride-2", writer.samples.single().rideId)
    }
}
