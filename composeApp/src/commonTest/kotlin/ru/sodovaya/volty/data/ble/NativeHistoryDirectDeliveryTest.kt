package ru.sodovaya.volty.data.ble

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import ru.sodovaya.volty.data.bms.BmsProtocol
import ru.sodovaya.volty.data.bms.BmsUuids
import ru.sodovaya.volty.data.history.HistoryOffer
import ru.sodovaya.volty.data.history.HistoryTelemetrySource
import ru.sodovaya.volty.data.history.HistoryRecordingDemand
import ru.sodovaya.volty.data.history.HistoryWriterDiagnostics
import ru.sodovaya.volty.data.history.HistoryWriter
import ru.sodovaya.volty.data.history.RideRecordingController
import ru.sodovaya.volty.domain.history.*
import ru.sodovaya.volty.domain.model.*
import kotlin.test.*
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class NativeHistoryDirectDeliveryTest {
    private val source = HistorySource("pack-a", HistorySourceKind.BATTERY, "A")
    private val spec = LinkSpec("AA:BB:CC:DD:EE:01", ProtocolKind.JK, listOf(OwnedSource(0)))

    private fun tick(delta: Long = 0) = HistoryTick(10_000 + delta, 100 + delta, 1)

    private fun vehicle() = Vehicle(
        id = "bike",
        name = "Bike",
        iconKey = "bicycle",
        packs = listOf(Pack(index = 0, label = "A", bmsType = BmsType.JK_BMS, bmsAddress = spec.address)),
        topology = PackTopology.PARALLEL,
        chemistry = Chemistry.LI_ION_NMC,
        createdAt = Instant.fromEpochSeconds(0),
    )

    private class Emitter : BmsProtocol() {
        override val uuids = BmsUuids("service", "notify", "write")
        var decodes = 0

        override fun handshakeCommands() = emptyList<ByteArray>()
        override fun pollCommands() = emptyList<ByteArray>()
        override fun latestData(packIndex: Int): BmsData? = null
        override fun reset() = Unit

        override fun onNotification(data: ByteArray) {
            decodes++
            data.forEach { value ->
                observeTelemetry(TelemetryOrigin.BATTERY, 0) {
                    listOf(TelemetryReading.measured(TelemetryMetric.CURRENT, value.toDouble()))
                }
            }
        }
    }

    private class Writer(private val sampleCapacity: Int = Int.MAX_VALUE) : HistoryWriter {
        override val diagnostics = MutableStateFlow(HistoryWriterDiagnostics())
        val issued = mutableListOf<Pair<Long, HistoryBatch>>()
        val outcomes = mutableListOf<HistoryOffer>()
        val finishes = mutableListOf<Triple<HistoryTick, HistoryRideStatus, String?>>()
        var acceptedSamples = 0
        var failOffers = false
        var duringOffer: ((Long, HistoryBatch) -> Unit)? = null

        override suspend fun begin(generation: Long, ride: HistoryRide, sources: List<HistorySource>) = Unit

        override fun offer(generation: Long, batch: HistoryBatch): HistoryOffer {
            duringOffer?.invoke(generation, batch)
            issued += generation to batch
            if (batch.samples.isEmpty()) return HistoryOffer.ACCEPTED
            if (failOffers) error("injected writer failure")
            if (acceptedSamples + batch.samples.size > sampleCapacity) {
                val dropped = batch.samples.size - (sampleCapacity - acceptedSamples).coerceAtLeast(0)
                diagnostics.value = diagnostics.value.copy(
                    droppedEvents = diagnostics.value.droppedEvents + dropped,
                )
                outcomes += HistoryOffer.OVERFLOW
                return HistoryOffer.OVERFLOW
            }
            acceptedSamples += batch.samples.size
            outcomes += HistoryOffer.ACCEPTED
            return HistoryOffer.ACCEPTED
        }

        override suspend fun flush() = Unit
        override suspend fun finish(generation: Long, tick: HistoryTick, status: HistoryRideStatus, rideId: String?) {
            finishes += Triple(tick, status, rideId)
        }
        override suspend fun delete(rideId: String) = Unit
        override suspend fun close() = Unit
    }

    private class Clock(var current: HistoryTick) {
        fun read() = current
    }

    private data class Fixture(
        val writer: Writer,
        val controller: RideRecordingController,
        val ingress: HistoryTelemetryIngress,
        val clock: Clock,
        val channel: Channel<Sample>,
        val ownership: SampleSessionOwnership,
        val protocol: Emitter,
        val capture: SessionTelemetryCapture,
    )

    private suspend fun fixture(sampleCapacity: Int = Int.MAX_VALUE, channelCapacity: Int = 1): Fixture {
        val writer = Writer(sampleCapacity)
        var rideIds = 0
        val controller = RideRecordingController(writer, HistoryRecordingDemand {}, { "ride-${++rideIds}" }, { 0 })
        val clock = Clock(tick())
        val ingress = HistoryTelemetryIngress(
            controller,
            clock::read,
            { _, _ -> mapOf(HistoryTelemetrySource(TelemetryOrigin.BATTERY, 0) to source) },
            manageConnections = true,
        )
        val channel = sampleFunnelChannel(channelCapacity)
        val ownership = SampleSessionOwnership { true }
        val protocol = Emitter()
        ingress.installNativePlan("bike", listOf(spec), vehicle()) { true }
        val capture = assertNotNull(ingress.capture(protocol, "attempt", spec, vehicle(), ownership, channel))
        assertTrue(capture.prepareForObservation())
        return Fixture(writer, controller, ingress, clock, channel, ownership, protocol, capture)
    }

    @Test
    fun managedTelemetryBypassesFullAndClosedDashboardFunnels() = runTest {
        for (closeChannel in listOf(false, true)) {
            val f = fixture()
            val dashboardSample = PackSample(0, BmsData(current = 3f, isConnected = true), emptyList())
            assertTrue(f.channel.trySend(dashboardSample).isSuccess)
            if (closeChannel) f.channel.close()

            f.clock.current = tick(20)
            assertTrue(f.capture.processNotification(byteArrayOf(1, 99, 0)))

            assertEquals(listOf(1.0, 99.0, 0.0), f.writer.issued.flatMap { it.second.samples }.map { it.value })
            assertTrue(f.writer.issued.all { it.second.rideId == "ride-1" })
            assertSame(dashboardSample, f.channel.tryReceive().getOrNull())
            assertTrue(f.channel.tryReceive().isFailure, "managed history must not enqueue a dashboard sample")
            assertEquals(0L, f.ingress.diagnostics.value.droppedObservations)
            assertEquals(0L, f.ingress.diagnostics.value.setupFailures)

            f.ownership.retire()
            assertFalse(f.capture.processNotification(byteArrayOf(7)))
            assertEquals(1, f.protocol.decodes)
            assertEquals(3, f.writer.issued.count { it.second.samples.isNotEmpty() })
            f.ingress.closeNative()
            f.channel.cancel()
        }
    }

    @Test
    fun manualStopAndRuntimeCloseWaitForDirectWriterOfferToReturn() = runTest {
        for (closeRuntime in listOf(false, true)) {
            val f = fixture()
            val rideId = assertNotNull(f.controller.state.value.intent.ride).id
            val controls = assertNotNull(f.ingress.manualControls())
            var controlJob: Deferred<*>? = null
            var triggered = false
            f.writer.duringOffer = { _, batch ->
                if (!triggered && batch.samples.isNotEmpty()) {
                    triggered = true
                    controlJob = if (closeRuntime) {
                        backgroundScope.async(start = CoroutineStart.UNDISPATCHED) { f.ingress.closeNative() }
                    } else {
                        backgroundScope.async(start = CoroutineStart.UNDISPATCHED) { controls.stop(rideId) }
                    }
                    assertFalse(assertNotNull(controlJob).isCompleted,
                        "the lifecycle fence must wait for the in-flight writer offer")
                }
            }

            f.clock.current = tick(20)
            assertTrue(f.capture.processNotification(byteArrayOf(7)))
            runCurrent()
            assertNotNull(controlJob).await()

            assertTrue(triggered)
            assertEquals(listOf(7.0), f.writer.issued.flatMap { it.second.samples }.map { it.value })
            assertEquals(listOf(HistoryRideStatus.COMPLETED), f.writer.finishes.map { it.second })
            assertEquals(tick(20), f.writer.finishes.single().first)
            f.channel.cancel()
        }
    }

    @Test
    fun directOfferKeepsReceiptRideAndTickAcrossAStopAndRestart() = runTest {
        val f = fixture()
        val first = assertNotNull(f.controller.captureTelemetryRecording())
        val rideId = assertNotNull(f.controller.state.value.intent.ride).id
        f.clock.current = tick(30)

        assertTrue(f.capture.processNotification(byteArrayOf(5)) {
            kotlinx.coroutines.runBlocking {
                f.controller.execute(RecordingInput.Stop(tick(10), first.connectionGeneration, rideId))
                f.controller.execute(RecordingInput.Start("bike", first.connectionGeneration, tick(20)), listOf(source))
            }
        })

        assertEquals("ride-2", f.controller.state.value.intent.ride?.id)
        assertTrue(f.writer.issued.none { it.second.samples.isNotEmpty() },
            "the receipt captured for the retired ride must not move to its successor")
        f.clock.current = tick(31)
        assertTrue(f.capture.processNotification(byteArrayOf(6)))

        val current = assertNotNull(f.controller.captureTelemetryRecording())
        val telemetryOffers = f.writer.issued.filter { it.second.samples.isNotEmpty() }
        assertEquals(listOf("ride-2"), telemetryOffers.map { it.second.rideId })
        assertEquals(listOf(tick(31)), telemetryOffers.flatMap { it.second.samples }.map { it.tick })
        assertEquals(listOf(current.generation), telemetryOffers.map { it.first })
        f.controller.execute(RecordingInput.Stop(tick(25), current.connectionGeneration, "ride-2"))
        assertEquals(tick(31), f.writer.finishes.last().first,
            "a delayed manual stop must retain the latest synchronously issued telemetry bound")
        assertEquals(listOf(HistoryRideStatus.COMPLETED, HistoryRideStatus.COMPLETED), f.writer.finishes.map { it.second })
        f.ingress.closeNative()
        f.channel.cancel()
    }

    @Test
    fun writerFailureAndBoundedOverflowInterruptInsteadOfCompletingTheRide() = runTest {
        val failed = fixture()
        failed.writer.failOffers = true
        failed.clock.current = tick(20)
        assertTrue(failed.capture.processNotification(byteArrayOf(9)))
        assertEquals(1, failed.writer.issued.count { it.second.samples.isNotEmpty() })
        assertNotNull(failed.controller.telemetryFailure.value)
        val failedRide = assertNotNull(failed.controller.state.value.intent.ride).id
        assertTrue(assertNotNull(failed.ingress.manualControls()).stop(failedRide))
        assertEquals(listOf(HistoryRideStatus.INTERRUPTED), failed.writer.finishes.map { it.second })
        failed.channel.cancel()

        val overflow = fixture(sampleCapacity = 1)
        overflow.clock.current = tick(20)
        assertTrue(overflow.capture.processNotification(byteArrayOf(1)))
        overflow.clock.current = tick(21)
        assertTrue(overflow.capture.processNotification(byteArrayOf(2)))
        assertEquals(listOf(HistoryOffer.ACCEPTED, HistoryOffer.OVERFLOW), overflow.writer.outcomes)
        assertEquals(1L, overflow.writer.diagnostics.value.droppedEvents)
        assertEquals(listOf(1.0, 2.0), overflow.writer.issued.flatMap { it.second.samples }.map { it.value })
        assertNotNull(overflow.controller.telemetryFailure.value,
            "a bounded writer overflow must latch a visible recording failure")
        val overflowRide = assertNotNull(overflow.controller.state.value.intent.ride).id
        assertTrue(assertNotNull(overflow.ingress.manualControls()).stop(overflowRide))
        assertEquals(listOf(HistoryRideStatus.INTERRUPTED), overflow.writer.finishes.map { it.second })
        overflow.channel.cancel()
    }
}
