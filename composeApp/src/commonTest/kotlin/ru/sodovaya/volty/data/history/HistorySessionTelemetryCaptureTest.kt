package ru.sodovaya.volty.data.history

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import ru.sodovaya.volty.data.bms.*
import ru.sodovaya.volty.domain.history.*
import ru.sodovaya.volty.domain.model.*
import kotlin.test.*

class HistorySessionTelemetryCaptureTest {
    private val source = HistorySource("pack-a", HistorySourceKind.BATTERY, "A")
    private val key = HistoryTelemetrySource(TelemetryOrigin.BATTERY, 0)
    private fun tick(delta: Long = 0) = HistoryTick(10000 + delta, 100 + delta, 1)
    private class Emitter : BmsProtocol() {
        override val uuids = BmsUuids("s", "n", "w")
        var decodes = 0
        override fun handshakeCommands() = emptyList<ByteArray>()
        override fun pollCommands() = emptyList<ByteArray>()
        override fun latestData(packIndex: Int): BmsData? = null
        override fun reset() {}
        override fun onNotification(data: ByteArray) {
            decodes++
            data.forEach { value -> observeTelemetry(TelemetryOrigin.BATTERY, 0) {
                listOf(TelemetryReading.measured(TelemetryMetric.CURRENT, value.toDouble()))
            } }
        }
    }
    private class Writer : HistoryWriter {
        override val diagnostics = MutableStateFlow(HistoryWriterDiagnostics())
        val samples = mutableListOf<HistoryBatch>()
        val statuses = mutableListOf<HistoryRideStatus>()
        override suspend fun begin(generation: Long, ride: HistoryRide, sources: List<HistorySource>) {}
        override fun offer(generation: Long, batch: HistoryBatch): HistoryOffer {
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

    @Test fun lostReceiptMarksOnlyActualMappedObservationsAndKeepsLiveDecoderUsable() = runTest {
        val writer = Writer(); val c = controller(writer); val protocol = Emitter()
        c.execute(RecordingInput.Connected("bike", 7, tick()), listOf(source))
        var failing = true
        val events = mutableListOf<CapturedHistoryTelemetry>()
        val capture = historySessionTelemetryCapture(protocol, "attempt", c,
            { if (failing) error("clock failed"); tick(3) }, { true }, mapOf(key to source), { events += it })
        capture.processNotification(byteArrayOf())
        assertEquals(1, protocol.decodes)
        assertEquals(1L, capture.receiptFailures)
        assertNull(c.telemetryFailure.value, "no decoded observation means no earned telemetry loss verdict")
        capture.processNotification(byteArrayOf(5))
        assertEquals(2, protocol.decodes)
        assertTrue(events.isEmpty())
        assertEquals("ride-1", assertNotNull(c.telemetryFailure.value).rideId)
        failing = false
        capture.processNotification(byteArrayOf(6))
        assertEquals(HistoryOffer.ACCEPTED, events.single().offer(c) { true })
        c.execute(RecordingInput.Stop(tick(4), 7, "ride-1"))
        assertEquals(listOf(HistoryRideStatus.INTERRUPTED), writer.statuses)
    }

    @Test fun missingReceiptForUnmappedSourceDoesNotPoisonRecording() = runTest {
        val writer = Writer(); val c = controller(writer); val protocol = Emitter()
        c.execute(RecordingInput.Connected("bike", 7, tick()), listOf(source))
        val capture = historySessionTelemetryCapture(protocol, "attempt", c, { error("clock failed") }, { true }, emptyMap(), {})
        capture.processNotification(byteArrayOf(5))
        assertEquals(1, protocol.decodes); assertEquals(1L, capture.receiptFailures)
        assertNull(c.telemetryFailure.value)
    }

    @Test fun missingReceiptKeepsOriginalRecordingOwnerAcrossPrefixStopStart() = runTest {
        val writer = Writer(); val c = controller(writer); val protocol = Emitter()
        c.execute(RecordingInput.Connected("bike", 7, tick()), listOf(source))
        val capture = historySessionTelemetryCapture(protocol, "attempt", c, { error("clock failed") }, { true }, mapOf(key to source), {})
        capture.processNotification(byteArrayOf(5)) {
            runBlocking {
                c.execute(RecordingInput.Stop(tick(2), 7, "ride-1"))
                c.execute(RecordingInput.Start("bike", 7, tick(3)), listOf(source))
            }
        }
        assertEquals(1, protocol.decodes); assertEquals(1L, capture.receiptFailures)
        assertNull(c.telemetryFailure.value)
        c.execute(RecordingInput.Stop(tick(4), 7, "ride-2"))
        assertEquals(listOf(HistoryRideStatus.COMPLETED, HistoryRideStatus.COMPLETED), writer.statuses)
    }

    @Test fun oneReceiptKeepsCoalescedPeaksAndFrozenSourceAttribution() = runTest {
        val writer = Writer(); val c = controller(writer); val protocol = Emitter()
        c.execute(RecordingInput.Connected("bike", 7, tick()), listOf(source))
        val bindings = mutableMapOf(key to source)
        val events = mutableListOf<CapturedHistoryTelemetry>(); var reads = 0
        val capture = historySessionTelemetryCapture(protocol, "attempt", c,
            { reads++; tick(1) }, { true }, bindings, { events += it })
        bindings[key] = HistorySource("different-pack", HistorySourceKind.BATTERY, "B")
        capture.processNotification(byteArrayOf(1, 99, 0))
        assertEquals(1, reads); assertEquals(3, events.size)
        assertEquals(listOf(1.0, 99.0, 0.0), events.map { it.received.observation.readings.single().value })
        assertTrue(events.all { it.source == source && it.tick == tick(1) && it.recording.rideId == "ride-1" })
        assertSame(events.first().received.receipt, events.last().received.receipt)
        events.forEach { assertEquals(HistoryOffer.ACCEPTED, it.offer(c) { true }) }
        assertEquals(listOf(1.0, 99.0, 0.0), writer.samples.flatMap { it.samples }.map { it.value })
        assertTrue(writer.samples.flatMap { it.samples }.all { it.key.sourceId == source.id })
    }

    @Test fun recordingIsCapturedBeforePrefixAndOldPacketCannotEnterNewRide() = runTest {
        val writer = Writer(); val c = controller(writer); val protocol = Emitter()
        c.execute(RecordingInput.Connected("bike", 7, tick()), listOf(source))
        val events = mutableListOf<CapturedHistoryTelemetry>()
        var receiptTick = tick(1)
        val capture = historySessionTelemetryCapture(protocol, "attempt", c,
            { receiptTick }, { true }, mapOf(key to source), { events += it })
        capture.processNotification(byteArrayOf(5)) {
            runBlocking {
                c.execute(RecordingInput.Stop(tick(2), 7, "ride-1"))
                c.execute(RecordingInput.Start("bike", 7, tick(3)), listOf(source))
            }
        }
        val old = events.single()
        assertEquals("ride-1", old.recording.rideId)
        assertEquals(HistoryOffer.RETIRED, old.offer(c) { true })
        assertTrue(writer.samples.isEmpty())
        receiptTick = tick(4)
        capture.processNotification(byteArrayOf(6))
        val fresh = events.last()
        assertEquals("ride-2", fresh.recording.rideId)
        assertEquals(HistoryOffer.ACCEPTED, fresh.offer(c) { true })
        assertEquals("ride-2", writer.samples.single().rideId)
    }

    @Test fun noRecordingAndUnownedSourceKeepLiveDecodeWithoutHistory() = runTest {
        val writer = Writer(); val c = controller(writer); val protocol = Emitter()
        val events = mutableListOf<CapturedHistoryTelemetry>()
        val capture = historySessionTelemetryCapture(protocol, "attempt", c,
            { tick(1) }, { true }, emptyMap(), { events += it })
        capture.processNotification(byteArrayOf(1))
        c.execute(RecordingInput.Connected("bike", 7, tick()), listOf(source))
        capture.processNotification(byteArrayOf(2))
        assertEquals(2, protocol.decodes); assertTrue(events.isEmpty())
        assertTrue(writer.samples.isEmpty())
    }

    @Test fun queuedEnvelopeRequiresItsBleAttemptEvenWithCurrentRecording() = runTest {
        val writer = Writer(); val c = controller(writer); val protocol = Emitter()
        c.execute(RecordingInput.Connected("bike", 7, tick()), listOf(source))
        val events = mutableListOf<CapturedHistoryTelemetry>()
        val capture = historySessionTelemetryCapture(protocol, "attempt", c,
            { tick(1) }, { true }, mapOf(key to source), { events += it })
        capture.processNotification(byteArrayOf(5))
        assertEquals(HistoryOffer.RETIRED, events.single().offer(c) { false })
        assertTrue(writer.samples.isEmpty())
        assertEquals(HistoryOffer.ACCEPTED, events.single().offer(c) { true })
    }

    @Test fun contributorMappingRejectsAggregateOrWrongKindAtConstruction() {
        val writer = Writer(); val c = controller(writer)
        for (kind in listOf(HistorySourceKind.CONTROLLER, HistorySourceKind.VEHICLE_BATTERY)) {
            assertFailsWith<IllegalArgumentException> {
                historySessionTelemetryCapture(Emitter(), "attempt", c, { tick() }, { true },
                    mapOf(key to source.copy(kind = kind)), {})
            }
        }
    }
}
