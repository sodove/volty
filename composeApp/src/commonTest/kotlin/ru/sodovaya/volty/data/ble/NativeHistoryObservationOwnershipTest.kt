package ru.sodovaya.volty.data.ble

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import ru.sodovaya.volty.data.bms.*
import ru.sodovaya.volty.data.history.*
import ru.sodovaya.volty.domain.history.*
import ru.sodovaya.volty.domain.model.*
import kotlin.test.*
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class, ExperimentalCoroutinesApi::class)
class NativeHistoryObservationOwnershipTest {
    private class Emitter : BmsProtocol() {
        override val uuids = BmsUuids("service", "notify", "write")
        var decodes = 0
        override fun handshakeCommands() = emptyList<ByteArray>()
        override fun pollCommands() = emptyList<ByteArray>()
        override fun latestData(packIndex: Int): BmsData? = null
        override fun reset() = Unit
        override fun onNotification(data: ByteArray) {
            decodes++
            observeTelemetry(TelemetryOrigin.BATTERY, 0) {
                listOf(TelemetryReading.measured(TelemetryMetric.CURRENT, data.single().toDouble()))
            }
        }
    }
    private class Writer : HistoryWriter {
        override val diagnostics = MutableStateFlow(HistoryWriterDiagnostics())
        val values = mutableListOf<Double?>()
        var begins = 0
        var duringOffer: ((HistoryBatch) -> Unit)? = null
        override suspend fun begin(generation: Long, ride: HistoryRide, sources: List<HistorySource>) { begins++ }
        override fun offer(generation: Long, batch: HistoryBatch): HistoryOffer {
            duringOffer?.invoke(batch)
            values += batch.samples.map { it.value }; return HistoryOffer.ACCEPTED
        }
        override suspend fun finish(generation: Long, tick: HistoryTick, status: HistoryRideStatus, rideId: String?) = Unit
        override suspend fun flush() = Unit
        override suspend fun delete(rideId: String) = Unit
        override suspend fun close() = Unit
    }
    private class Fixture {
        val spec = LinkSpec("AA", ProtocolKind.ANT, listOf(OwnedSource(0)))
        val vehicle = Vehicle("bike", "Bike", "bicycle", listOf(Pack(0, "A", BmsType.ANT_BMS, "AA")), emptyList(),
            chemistry = Chemistry.LI_ION_NMC, createdAt = Instant.fromEpochSeconds(0))
        val writer = Writer()
        val controller = RideRecordingController(writer, HistoryRecordingDemand {}, { "ride" }, { 0 })
        private var time = 100L
        var clockFails = false
        val ingress = HistoryTelemetryIngress(controller, {
            check(!clockFails) { "Clock unavailable" }; HistoryTick(1000 + ++time, time, 1)
        }, { _, _ -> mapOf(HistoryTelemetrySource(TelemetryOrigin.BATTERY, 0) to
            HistorySource("physical-pack", HistorySourceKind.BATTERY, "Pack")) }, manageConnections = true)
        val channel = sampleFunnelChannel(8)
        suspend fun install() = ingress.installNativePlan("bike", listOf(spec), vehicle) { true }
        fun capture(protocol: Emitter, id: String) = assertNotNull(ingress.capture(protocol, id, spec, vehicle,
            SampleSessionOwnership { true }, channel)) // Live ownership deliberately survives history handover.
        // Retraction: native facts are already writer-owned, not dashboard-queued.
        fun consume() = assertNoQueuedHistory()
        fun assertNoQueuedHistory() {
            val unexpected = channel.tryReceive().getOrNull()
            (unexpected as? HistoryTelemetrySample)?.discard()
            assertNull(unexpected, "Retired recording path issued history")
        }
    }

    @Test fun replacementRevokesOldHistoryWhileBothLiveDecodersRemainUsable() = runTest {
        val f = Fixture()
        try {
            f.install(); val oldProtocol = Emitter(); val old = f.capture(oldProtocol, "old")
            assertTrue(old.prepareForObservation())
            assertTrue(old.processNotification(byteArrayOf(1))); f.consume()
            val nextProtocol = Emitter(); val next = f.capture(nextProtocol, "next")
            assertTrue(next.prepareForObservation())
            assertTrue(old.processNotification(byteArrayOf(2))); f.assertNoQueuedHistory()
            assertTrue(next.processNotification(byteArrayOf(3))); f.consume()
            assertEquals(listOf<Double?>(1.0, 3.0), f.writer.values)
            assertEquals(2, oldProtocol.decodes); assertEquals(1, nextProtocol.decodes)
            assertEquals(1, f.writer.begins)
        } finally { f.channel.cancel() }
    }

    @Test fun acceptedOldPrefixDrainsBeforeReplacementWithoutReauthorizingOldCapture() = runTest {
        val f = Fixture(); var replacement: Deferred<Boolean>? = null
        try {
            f.install(); val oldProtocol = Emitter(); val old = f.capture(oldProtocol, "old")
            assertTrue(old.prepareForObservation())
            val next = f.capture(Emitter(), "next")
            var waitedInsideOffer = false
            f.writer.duringOffer = { batch ->
                if (batch.samples.any { it.value == 4.0 }) {
                    replacement = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                        next.prepareForObservation()
                    }
                    waitedInsideOffer = !assertNotNull(replacement).isCompleted
                    // Replacement has revoked further old observations but must
                    // await this already admitted synchronous writer call.
                    assertEquals(emptyList(), f.writer.values)
                }
            }
            assertTrue(old.processNotification(byteArrayOf(4)))
            assertTrue(waitedInsideOffer)
            f.writer.duringOffer = null
            assertEquals(listOf<Double?>(4.0), f.writer.values)
            f.assertNoQueuedHistory()
            assertTrue(old.processNotification(byteArrayOf(5))); f.assertNoQueuedHistory()
            assertTrue(assertNotNull(replacement).await())
            assertTrue(old.processNotification(byteArrayOf(6))); f.assertNoQueuedHistory()
            assertTrue(next.processNotification(byteArrayOf(7))); f.consume()
            assertEquals(listOf<Double?>(4.0, 7.0), f.writer.values)
            assertEquals(3, oldProtocol.decodes)
        } finally { f.channel.cancel(); replacement?.cancelAndJoin() }
    }

    @Test fun oldClockFailureCannotMarkSuccessorsRecordingAsLost() = runTest {
        val f = Fixture()
        try {
            f.install(); val protocol = Emitter(); val old = f.capture(protocol, "old")
            assertTrue(old.prepareForObservation())
            val next = f.capture(Emitter(), "next"); assertTrue(next.prepareForObservation())
            assertNull(f.controller.telemetryFailure.value)
            f.clockFails = true
            assertTrue(old.processNotification(byteArrayOf(8)))
            assertEquals(1, old.receiptFailures); assertEquals(1, protocol.decodes)
            assertNull(f.controller.telemetryFailure.value, "Retired source reported a loss against successor")
            f.clockFails = false
            assertTrue(next.processNotification(byteArrayOf(9))); f.consume()
            assertEquals(listOf<Double?>(9.0), f.writer.values)
            // The active owner must still report its real receipt loss. Merely
            // suppressing every unreceipted callback would pass the old-owner check.
            f.clockFails = true
            assertTrue(next.processNotification(byteArrayOf(12)))
            assertEquals(1, next.receiptFailures)
            assertEquals("ReceiptLoss", f.controller.telemetryFailure.value?.failureType)
            f.assertNoQueuedHistory()
        } finally { f.channel.cancel() }
    }

    @Test fun samePlanAndSameProtocolObjectCannotRestoreOldCaptureCapability() = runTest {
        val f = Fixture()
        try {
            f.install(); val protocol = Emitter(); val old = f.capture(protocol, "old")
            assertTrue(old.prepareForObservation())
            f.install() // Same public ride/source IDs, different native selection capability.
            val next = f.capture(protocol, "next"); assertTrue(next.prepareForObservation())
            assertTrue(old.processNotification(byteArrayOf(10))); f.assertNoQueuedHistory()
            assertTrue(next.processNotification(byteArrayOf(11))); f.consume()
            assertEquals(listOf<Double?>(11.0), f.writer.values)
            assertEquals(2, protocol.decodes); assertEquals(1, f.writer.begins)
        } finally { f.channel.cancel() }
    }
}
