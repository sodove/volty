package ru.sodovaya.volty.data.ble

import kotlinx.coroutines.channels.Channel
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

    @Test fun startupIngressCannotChangeAfterConnectionPreparationStarts() = bleRepositoryTest { repo ->
        val ingress = HistoryTelemetryIngress(controller(Writer()), { tick() }, { _, _ -> emptyMap() })
        repo.configureHistoryTelemetryIngress(ingress)
        repo.sealHistoryTelemetryConfigurationForTest()
        assertFailsWith<IllegalStateException> { repo.configureHistoryTelemetryIngress(null) }
    }

    @Test fun ingressLossFromRetiredRecordingCannotPoisonNewRide() = kotlinx.coroutines.test.runTest {
        val writer = Writer(); val c = controller(writer)
        c.execute(RecordingInput.Connected("bike", 7, tick()), listOf(source))
        val ingress = HistoryTelemetryIngress(c, { tick(1) }, { _, _ ->
            mapOf(HistoryTelemetrySource(TelemetryOrigin.BATTERY, 0) to source)
        })
        val channel = Channel<Sample>(0)
        val capture = assertNotNull(ingress.capture(Emitter(), "attempt", LinkSpec("address", ProtocolKind.JK), null,
            SampleSessionOwnership { true }, channel))
        capture.processNotification(byteArrayOf(5)) {
            runBlocking {
                c.execute(RecordingInput.Stop(tick(2), 7, "ride-1"))
                c.execute(RecordingInput.Start("bike", 7, tick(3)), listOf(source))
            }
        }
        assertEquals(1L, ingress.diagnostics.value.droppedObservations, "driver must reach failed send")
        assertNull(c.telemetryFailure.value)
        c.execute(RecordingInput.Stop(tick(4), 7, "ride-2"))
        assertEquals(listOf(HistoryRideStatus.COMPLETED, HistoryRideStatus.COMPLETED), writer.statuses)
        channel.close()
    }

    @Test fun nativeIngressFreezesAttemptSourcePlanAndEnqueuesOwnedEnvelopes() = kotlinx.coroutines.test.runTest {
        val writer = Writer(); val c = controller(writer)
        c.execute(RecordingInput.Connected("bike", 7, tick()), listOf(source))
        val v = vehicle(); val spec = LinkSpec(v.packs.single().bmsAddress, ProtocolKind.JK, listOf(OwnedSource(0)))
        val map = mutableMapOf(HistoryTelemetrySource(TelemetryOrigin.BATTERY, 0) to source)
        var planned = 0
        val ingress = HistoryTelemetryIngress(c, { tick(1) }, { receivedSpec, receivedVehicle ->
            assertSame(spec, receivedSpec); assertSame(v, receivedVehicle); planned++; map
        })
        val owner = SampleSessionOwnership { true }; val channel = Channel<Sample>(4)
        val capture = assertNotNull(ingress.capture(Emitter(), "attempt", spec, v, owner, channel))
        map.clear()
        capture.processNotification(byteArrayOf(1, 99, 0))
        assertEquals(1, planned)
        val packets = (0..2).map { assertIs<HistoryTelemetrySample>(channel.tryReceive().getOrNull()) }
        assertTrue(packets.all { it.sessionOwnership === owner && it.controller === c && it.telemetry.source == source })
        packets.forEach { assertEquals(HistoryOffer.ACCEPTED, it.telemetry.offer(c, owner::isCurrent)) }
        assertEquals(listOf(1.0, 99.0, 0.0), writer.samples.flatMap { it.samples }.map { it.value })
        assertEquals(0L, ingress.diagnostics.value.droppedObservations)
        channel.close()
    }

    @Test fun nativeIngressReportsFullAndClosedQueueWithoutBlockingDecoder() = kotlinx.coroutines.test.runTest {
        val writer = Writer(); val c = controller(writer)
        c.execute(RecordingInput.Connected("bike", 7, tick()), listOf(source))
        val ingress = HistoryTelemetryIngress(c, { tick(1) }, { _, _ ->
            mapOf(HistoryTelemetrySource(TelemetryOrigin.BATTERY, 0) to source)
        })
        val protocol = Emitter(); val channel = Channel<Sample>(1)
        val capture = assertNotNull(ingress.capture(protocol, "attempt", LinkSpec("address", ProtocolKind.JK),
            null, SampleSessionOwnership { true }, channel))
        capture.processNotification(byteArrayOf(1, 2))
        assertEquals(1L, ingress.diagnostics.value.droppedObservations)
        assertEquals(HistoryTelemetryIngressFailure.FULL, ingress.diagnostics.value.lastFailure)
        channel.close()
        capture.processNotification(byteArrayOf(3))
        assertEquals(2L, ingress.diagnostics.value.droppedObservations)
        assertEquals(HistoryTelemetryIngressFailure.CLOSED, ingress.diagnostics.value.lastFailure)
        assertEquals(2, protocol.decodes)
        assertEquals(1.0, assertIs<HistoryTelemetrySample>(channel.tryReceive().getOrNull()).telemetry.received.observation.readings.single().value)
        assertTrue(writer.samples.isEmpty(), "producer must enqueue, never bypass the consumer")
        assertEquals("ride-1", assertNotNull(c.telemetryFailure.value).rideId)
        c.execute(RecordingInput.Stop(tick(2), 7, "ride-1"))
        assertEquals(listOf(HistoryRideStatus.INTERRUPTED), writer.statuses)
    }

    @Test fun nativeIngressSetupFailureIsVisibleAndLeavesDefaultLiveDecodeUsable() {
        val ingress = HistoryTelemetryIngress(controller(Writer()), { tick() }, { _, _ -> error("injected plan failure") })
        val protocol = Emitter(); val channel = Channel<Sample>(1)
        val capture = ingress.capture(protocol, "attempt", LinkSpec("address", ProtocolKind.JK), null,
            SampleSessionOwnership { true }, channel)
        assertNull(capture)
        assertEquals(1L, ingress.diagnostics.value.setupFailures)
        assertEquals(HistoryTelemetryIngressFailure.SETUP, ingress.diagnostics.value.lastFailure)
        processObservedSessionNotification(protocol, byteArrayOf(1), NoSampleEverWatchdogActivity(protocol), capture)
        assertEquals(1, protocol.decodes)
        channel.close()
    }

    @Test fun currentVehiclePlanFailureInterruptsItsRecordingWithoutInventingSamples() = kotlinx.coroutines.test.runTest {
        assertOwnedSetupFailure(invalidBinding = false)
    }

    @Test fun invalidCurrentVehicleRoleBindingAlsoInterruptsItsRecording() = kotlinx.coroutines.test.runTest {
        assertOwnedSetupFailure(invalidBinding = true)
    }

    private suspend fun assertOwnedSetupFailure(invalidBinding: Boolean) {
        val writer = Writer(); val c = controller(writer)
        c.execute(RecordingInput.Connected("bike", 7, tick()), listOf(source))
        var plans = 0; var clocks = 0
        val ingress = HistoryTelemetryIngress(c, { clocks++; tick(1) }, { _, _ ->
            plans++
            if (!invalidBinding) error("injected plan failure")
            mapOf(HistoryTelemetrySource(TelemetryOrigin.BATTERY, 0) to source.copy(kind = HistorySourceKind.CONTROLLER))
        })
        val v = vehicle(); val protocol = Emitter(); val channel = Channel<Sample>(1)
        try {
            val capture = ingress.capture(protocol, "attempt", LinkSpec(v.packs.single().bmsAddress, ProtocolKind.JK,
                listOf(OwnedSource(0))), v, SampleSessionOwnership { true }, channel)
            assertNull(capture)
            assertEquals(1, plans)
            assertEquals(1L, ingress.diagnostics.value.setupFailures)
            assertEquals(HistoryTelemetryIngressFailure.SETUP, ingress.diagnostics.value.lastFailure)
            assertEquals(HistoryRecordingFailure("ride-1", "SetupFailure"), c.telemetryFailure.value)
            assertEquals(0, clocks, "setup loss must not fabricate an acquisition timestamp")
            processObservedSessionNotification(protocol, byteArrayOf(1), NoSampleEverWatchdogActivity(protocol), capture)
            assertEquals(1, protocol.decodes)
            assertTrue(writer.samples.isEmpty())
            c.execute(RecordingInput.LinkLost(7, tick(2)))
            c.execute(RecordingInput.Connected("bike", 8, tick(3)))
            c.execute(RecordingInput.Stop(tick(4), 8, "ride-1"))
            assertEquals(listOf(HistoryRideStatus.INTERRUPTED), writer.statuses)
        } finally { channel.close() }
    }

    @Test fun unqualifiedSetupFailureCannotPoisonAnUnrelatedOrGpsOnlyRecording() = kotlinx.coroutines.test.runTest {
        for (case in listOf("other-vehicle", "missing-vehicle", "retired-attempt", "no-telemetry")) {
            val writer = Writer(); val c = controller(writer)
            c.execute(RecordingInput.Connected("bike", 7, tick()), if (case == "no-telemetry") emptyList() else listOf(source))
            val owner = SampleSessionOwnership { true }; var plans = 0
            val ingress = HistoryTelemetryIngress(c, { error("setup must not read clock") }, { _, _ ->
                plans++
                if (case == "retired-attempt") owner.retire()
                error("injected plan failure")
            })
            val v = when (case) { "other-vehicle" -> vehicle().copy(id = "other"); "missing-vehicle" -> null; else -> vehicle() }
            val channel = Channel<Sample>(1)
            try {
                assertNull(ingress.capture(Emitter(), "attempt", LinkSpec("address", ProtocolKind.JK), v, owner, channel))
                assertEquals(1, plans)
                assertEquals(1L, ingress.diagnostics.value.setupFailures)
                assertNull(c.telemetryFailure.value, case)
                c.execute(RecordingInput.Stop(tick(1), 7, "ride-1"))
                assertEquals(listOf(HistoryRideStatus.COMPLETED), writer.statuses, case)
            } finally { channel.close() }
        }
    }

    @Test fun failedOldSetupCannotAttachItsVerdictToANewRideStartedInsideThePlanner() = kotlinx.coroutines.test.runTest {
        val writer = Writer(); val c = controller(writer)
        c.execute(RecordingInput.Connected("bike", 7, tick()), listOf(source))
        var plans = 0
        val ingress = HistoryTelemetryIngress(c, { error("setup must not read clock") }, { _, _ ->
            plans++
            runBlocking {
                c.execute(RecordingInput.Stop(tick(1), 7, "ride-1"))
                c.execute(RecordingInput.Start("bike", 7, tick(2)), listOf(source))
            }
            error("injected old plan failure")
        })
        val channel = Channel<Sample>(1)
        try {
            assertNull(ingress.capture(Emitter(), "attempt", LinkSpec("address", ProtocolKind.JK), vehicle(),
                SampleSessionOwnership { true }, channel))
            assertEquals(1, plans)
            assertEquals(1L, ingress.diagnostics.value.setupFailures)
            assertEquals("ride-2", c.state.value.intent.ride?.id)
            assertNull(c.telemetryFailure.value)
            c.execute(RecordingInput.Stop(tick(3), 7, "ride-2"))
            assertEquals(listOf(HistoryRideStatus.COMPLETED, HistoryRideStatus.COMPLETED), writer.statuses)
        } finally { channel.close() }
    }

    @Test fun setupOwnershipPredicateFailureStillLeavesLiveDecodeUsable() = kotlinx.coroutines.test.runTest {
        val writer = Writer(); val c = controller(writer)
        c.execute(RecordingInput.Connected("bike", 7, tick()), listOf(source))
        val ingress = HistoryTelemetryIngress(c, { tick() }, { _, _ -> error("injected plan failure") })
        val protocol = Emitter(); val channel = Channel<Sample>(1)
        try {
            val capture = ingress.capture(protocol, "attempt", LinkSpec("address", ProtocolKind.JK), vehicle(),
                SampleSessionOwnership { error("injected ownership failure") }, channel)
            assertNull(capture)
            assertEquals(1L, ingress.diagnostics.value.setupFailures)
            assertNull(c.telemetryFailure.value)
            processObservedSessionNotification(protocol, byteArrayOf(1), NoSampleEverWatchdogActivity(protocol), capture)
            assertEquals(1, protocol.decodes)
        } finally { channel.close() }
    }

    @Test fun nativeIngressRetiredAttemptDoesNotReadReceiptOrEnqueue() = kotlinx.coroutines.test.runTest {
        val writer = Writer(); val c = controller(writer)
        c.execute(RecordingInput.Connected("bike", 7, tick()), listOf(source))
        var reads = 0
        val ingress = HistoryTelemetryIngress(c, { reads++; tick(1) }, { _, _ ->
            mapOf(HistoryTelemetrySource(TelemetryOrigin.BATTERY, 0) to source)
        })
        val owner = SampleSessionOwnership { true }; val channel = Channel<Sample>(1); val protocol = Emitter()
        val capture = assertNotNull(ingress.capture(protocol, "attempt", LinkSpec("address", ProtocolKind.JK), null, owner, channel))
        owner.retire()
        assertFalse(capture.processNotification(byteArrayOf(1)))
        assertEquals(0, reads); assertEquals(0, protocol.decodes)
        assertTrue(channel.tryReceive().isFailure)
        assertEquals(0L, ingress.diagnostics.value.droppedObservations)
        channel.close()
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
        val writer = Writer(); val c = controller(writer)
        c.execute(RecordingInput.Connected("bike", 7, tick()), listOf(source))
        var planned = 0
        val ingress = HistoryTelemetryIngress(c, { tick(1) }, { _, _ ->
            planned++
            mapOf(HistoryTelemetrySource(TelemetryOrigin.BATTERY, 0) to source)
        })
        repo.configureHistoryTelemetryIngress(ingress)
        val v = vehicle(); repo.installLinksForTest(v, v.packs.single().bmsAddress, BmsType.JK_BMS)
        val owner = SampleSessionOwnership { true }; val protocol = Emitter()
        val dashboard = repo.activeData.value
        val capture = assertNotNull(repo.historyTelemetryCaptureForTest(protocol, v.packs.single().bmsAddress, owner))
        capture.processNotification(byteArrayOf(1, 99, 0))
        advanceUntilIdle()
        assertEquals(1, planned)
        assertEquals(0L, protocol.telemetryObserverFailures)
        assertEquals(0L, ingress.diagnostics.value.droppedObservations)
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
