package ru.sodovaya.volty.data.ble

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import ru.sodovaya.volty.data.bms.*
import ru.sodovaya.volty.data.history.*
import ru.sodovaya.volty.domain.history.*
import ru.sodovaya.volty.domain.location.RideLocationState
import ru.sodovaya.volty.domain.model.*
import kotlin.test.*
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class HistoryRecorderShutdownTest {
    private class Writer(val order: MutableList<String> = mutableListOf()) : HistoryWriter {
        override val diagnostics = MutableStateFlow(HistoryWriterDiagnostics())
        var sources = emptyList<HistorySource>()
        val values = mutableListOf<Double?>()
        var finishGate: CompletableDeferred<Unit>? = null
        override suspend fun begin(generation: Long, ride: HistoryRide, sources: List<HistorySource>) { this.sources = sources }
        override fun offer(generation: Long, batch: HistoryBatch): HistoryOffer {
            if (batch.samples.isNotEmpty()) { values += batch.samples.map { it.value }; order += "sample" }
            return HistoryOffer.ACCEPTED
        }
        override suspend fun finish(generation: Long, tick: HistoryTick, status: HistoryRideStatus, rideId: String?) {
            order += "finish"; finishGate?.await(); order += "finishAck"
        }
        override suspend fun flush() = Unit
        override suspend fun delete(rideId: String) = Unit
        override suspend fun close() { order += "writerClose" }
    }
    private fun ingress() = HistoryTelemetryIngress(RideRecordingController(Writer(), HistoryRecordingDemand {},
        { "ride" }, { 0 }), { HistoryTick(100, 10, 1) }, manageConnections = true)

    @Test fun coldShutdownIsTerminalAndIdempotentWithoutStartingRecording() {
        var starts = 0; var stops = 0; var bleStops = 0
        bleRepositoryTest(historyStartup = { starts++; ingress() }, historyShutdown = { stops++ }, serviceStop = { bleStops++ }) { repo ->
            repo.shutdownRecordingRuntime(); repo.shutdownRecordingRuntime()
            assertEquals(0, starts); assertEquals(1, stops); assertEquals(1, bleStops)
            assertFailsWith<CancellationException> { repo.connectDemo() }
            assertEquals(ConnectionState.Disconnected, repo.connectionState.value)
        }
    }

    @Test fun acceptedShutdownFencesConnectionsButKeepsBleUntilRecordingAckDespiteCallerCancellation() {
        val release = CompletableDeferred<Unit>(); var entered = false; var bleStops = 0
        bleRepositoryTest(historyStartup = { ingress() }, historyShutdown = { entered = true; release.await() },
            serviceStop = { bleStops++ }) { repo ->
            assertTrue(repo.connectDemo().isSuccess); runCurrent()
            val closing = backgroundScope.launch { repo.shutdownRecordingRuntime() }
            try {
                runCurrent(); assertTrue(entered); assertEquals(0, bleStops)
                assertFailsWith<CancellationException> { repo.connectDemo() }
                assertIs<ConnectionState.Connected>(repo.connectionState.value)
                closing.cancel(); runCurrent(); assertFalse(closing.isCompleted)
                release.complete(Unit); closing.join()
                assertEquals(1, bleStops); assertEquals(ConnectionState.Disconnected, repo.connectionState.value)
            } finally { release.complete(Unit); closing.cancelAndJoin(); repo.shutdownRecordingRuntime() }
        }
    }

    @Test fun failedRecordingCleanupRetainsBleForRetryAndNeverReopensConnections() {
        var attempts = 0; var bleStops = 0
        bleRepositoryTest(historyStartup = { ingress() }, historyShutdown = { attempts++; check(attempts > 1) { "cleanup pending" } },
            serviceStop = { bleStops++ }) { repo ->
            try {
                assertTrue(repo.connectDemo().isSuccess)
                assertFailsWith<IllegalStateException> { repo.shutdownRecordingRuntime() }
                assertEquals(0, bleStops); assertIs<ConnectionState.Connected>(repo.connectionState.value)
                assertFailsWith<CancellationException> { repo.connectDemo() }
                repo.shutdownRecordingRuntime(); repo.shutdownRecordingRuntime()
                assertEquals(2, attempts); assertEquals(1, bleStops)
                assertEquals(ConnectionState.Disconnected, repo.connectionState.value)
            } finally { repo.shutdownRecordingRuntime() }
        }
    }

    @Test fun concurrentShutdownsShareOneCleanupAndCannotInvalidateDisconnect() {
        val release = CompletableDeferred<Unit>(); var attempts = 0; var bleStops = 0
        bleRepositoryTest(historyStartup = { ingress() }, historyShutdown = { attempts++; release.await() },
            serviceStop = { bleStops++ }) { repo ->
            assertTrue(repo.connectDemo().isSuccess)
            val first = backgroundScope.async { repo.shutdownRecordingRuntime() }
            val second = backgroundScope.async { repo.shutdownRecordingRuntime() }
            try {
                runCurrent(); assertEquals(1, attempts); assertFalse(first.isCompleted); assertFalse(second.isCompleted)
                release.complete(Unit); first.await(); second.await()
                assertEquals(1, attempts); assertEquals(1, bleStops)
                assertEquals(ConnectionState.Disconnected, repo.connectionState.value)
            } finally { release.complete(Unit); first.cancelAndJoin(); second.cancelAndJoin(); repo.shutdownRecordingRuntime() }
        }
    }

    @Test fun shutdownWaitsForPreviouslyAcceptedOrdinaryDisconnectCleanup() {
        val release = CompletableDeferred<Unit>(); var disconnects = 0; var bleStops = 0; var recordingStops = 0
        bleRepositoryTest(historyStartup = { ingress() }, historyShutdown = { recordingStops++ },
            serviceStop = { bleStops++ }) { repo ->
            assertTrue(repo.connectDemo().isSuccess)
            repo.beforeHistoryDisconnectForTest = { disconnects++; if (disconnects == 1) release.await() }
            val original = backgroundScope.launch { repo.disconnect() }
            var closing: Deferred<Unit>? = null
            try {
                runCurrent(); assertEquals(1, disconnects); assertFalse(original.isCompleted)
                closing = backgroundScope.async { repo.shutdownRecordingRuntime() }; runCurrent()
                assertEquals(1, recordingStops)
                assertFalse(closing.isCompleted, "Detached sessions from the accepted disconnect still own cleanup")
                assertEquals(0, bleStops)
                release.complete(Unit); original.join(); closing.await()
                assertTrue(original.isCompleted)
                assertEquals(ConnectionState.Disconnected, repo.connectionState.value)
            } finally { release.complete(Unit); original.cancelAndJoin(); closing?.cancelAndJoin(); repo.shutdownRecordingRuntime() }
        }
    }

    @Test fun ordinaryDisconnectDuringShutdownJoinsOwnerInsteadOfReleasingBleEarly() {
        val release = CompletableDeferred<Unit>(); var entered = false; var disconnects = 0; var bleStops = 0
        bleRepositoryTest(historyStartup = { ingress() }, historyShutdown = { entered = true; release.await() },
            serviceStop = { bleStops++ }) { repo ->
            assertTrue(repo.connectDemo().isSuccess)
            repo.beforeHistoryDisconnectForTest = { disconnects++ }
            val closing = backgroundScope.async { repo.shutdownRecordingRuntime() }
            var disconnect: Job? = null
            try {
                runCurrent(); assertTrue(entered)
                disconnect = backgroundScope.launch { repo.disconnect() }; runCurrent()
                assertFalse(disconnect.isCompleted)
                assertEquals(0, disconnects); assertEquals(0, bleStops)
                release.complete(Unit); closing.await(); disconnect.join()
                assertEquals(1, disconnects); assertEquals(1, bleStops)
                assertEquals(ConnectionState.Disconnected, repo.connectionState.value)
            } finally { release.complete(Unit); closing.cancelAndJoin(); disconnect?.cancelAndJoin(); repo.shutdownRecordingRuntime() }
        }
    }

    @Test fun shutdownWhileBootstrapWaitsInvalidatesLateStartupPublication() {
        val release = CompletableDeferred<Unit>(); var entered = false; var stops = 0
        bleRepositoryTest(historyStartup = { entered = true; release.await(); ingress() }, historyShutdown = { stops++ }) { repo ->
            val connecting = backgroundScope.async { runCatching { repo.connectDemo() } }
            try {
                runCurrent(); assertTrue(entered)
                repo.shutdownRecordingRuntime(); assertEquals(1, stops)
                release.complete(Unit)
                assertIs<CancellationException>(connecting.await().exceptionOrNull())
                assertEquals(ConnectionState.Disconnected, repo.connectionState.value)
            } finally { release.complete(Unit); connecting.cancelAndJoin(); repo.shutdownRecordingRuntime() }
        }
    }

    @Test fun failedBleReleaseRetriesOnlyRemainingPhaseWithoutClosingRecordingTwice() {
        var recordingStops = 0; var bleAttempts = 0; var admittedOwners = 0
        bleRepositoryTest(historyStartup = { ingress() }, historyShutdown = { recordingStops++ },
            serviceStop = { bleAttempts++; check(bleAttempts > 1) { "service release pending" } }) { repo ->
            repo.beforeHistoryDisconnectForTest = { admittedOwners++ }
            try {
                assertTrue(repo.connectDemo().isSuccess)
                assertFailsWith<IllegalStateException> { repo.shutdownRecordingRuntime() }
                assertEquals(1, recordingStops); assertEquals(1, bleAttempts)
                assertEquals(1, admittedOwners)
                assertFailsWith<CancellationException> { repo.connectDemo() }
                repo.shutdownRecordingRuntime(); repo.shutdownRecordingRuntime()
                assertEquals(1, recordingStops); assertEquals(2, bleAttempts)
                assertEquals(1, admittedOwners, "Retry must retain the original detached cleanup owner")
            } finally { repo.shutdownRecordingRuntime() }
        }
    }

    @Test fun precancelledShutdownDoesNotFenceLiveRepositoryOrIssueCleanup() {
        var stops = 0; var entered = false
        bleRepositoryTest(historyStartup = { ingress() }, historyShutdown = { stops++ }) { repo ->
            try {
                val cancelled = backgroundScope.launch {
                    entered = true; currentCoroutineContext().cancel(); repo.shutdownRecordingRuntime()
                }
                runCurrent(); cancelled.join(); assertTrue(entered); assertEquals(0, stops)
                assertTrue(repo.connectDemo().isSuccess)
            } finally { repo.shutdownRecordingRuntime() }
        }
    }

    private class Protocol : BmsProtocol() {
        override val uuids = BmsUuids("s", "n", "w")
        override fun handshakeCommands() = emptyList<ByteArray>()
        override fun pollCommands() = emptyList<ByteArray>()
        override fun latestData(packIndex: Int): BmsData? = null
        override fun reset() = Unit
        override fun onNotification(data: ByteArray) = Unit
    }
    @Test fun actualRuntimeDrainsAcceptedReceiptThroughLiveRepositoryFunnelBeforeBleAndWriterDisposal() {
        var runtime: HistoryRecordingRuntime? = null; var binding: HistoryRecordingRuntime.Binding? = null
        val order = mutableListOf<String>(); val writer = Writer(order); val release = CompletableDeferred<Unit>()
        bleRepositoryTest(historyStartup = { assertNotNull(runtime).prepare().also { binding = it }.ingress },
            historyShutdown = { assertNotNull(runtime).close() }, serviceStop = { order += "bleStop" }) { repo ->
            runtime = HistoryRecordingRuntime(HistoryRecordingClock({ 1 }, { 100 }, { 10 }),
                StandardTestDispatcher(testScheduler), { writer }, MutableStateFlow(RideLocationState()),
                HistoryLocationForegroundAuthority({ true }) { "10000000-0000-0000-0000-000000000001" }, {},
                { _, _ -> error("optional native GPS unavailable") }, { "ride" })
            repo.sealHistoryTelemetryConfigurationForTest()
            val vehicle = Vehicle("bike", "Bike", "bicycle", listOf(Pack(0, "Pack", BmsType.ANT_BMS, "AA")), emptyList(),
                chemistry = Chemistry.LI_ION_NMC, createdAt = Instant.fromEpochSeconds(0))
            repo.installLinksForTest(vehicle, "AA", BmsType.ANT_BMS); repo.prepareInstalledHistoryForTest()
            assertTrue(assertNotNull(repo.historyTelemetryCaptureForTest(Protocol(), "AA", SampleSessionOwnership { true })).prepareForObservation())
            val controller = assertNotNull(binding).controller
            val owner = assertNotNull(controller.captureTelemetryRecording()); val source = writer.sources.single()
            val tick = HistoryTick(100, 10, 1)
            val queued = assertNotNull(controller.reserveQueuedTelemetry(owner, tick, source.id))
            val observed = ReceivedTelemetry(TelemetryReceipt("receipt", 1, TelemetryReceiptTime(100, 10)),
                TelemetryObservation(TelemetryOrigin.BATTERY, 0, listOf(TelemetryReading.measured(TelemetryMetric.CURRENT, 9.0))))
            val sample = HistoryTelemetrySample(CapturedHistoryTelemetry(owner, source, tick, observed), controller,
                SampleSessionOwnership { true }, queued)
            writer.finishGate = release
            val consume = CompletableDeferred<Unit>(); var received = false
            repo.beforeHistorySampleConsumeForTest = { received = true; consume.await() }
            assertTrue(assertNotNull(repo.sampleFunnelChannelForTest()).trySend(sample).isSuccess)
            assertTrue(received)
            assertTrue(writer.values.isEmpty())
            val closing = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) { repo.shutdownRecordingRuntime() }
            try {
                runCurrent(); assertFalse(closing.isCompleted)
                assertTrue(writer.values.isEmpty()); assertFalse("finish" in order)
                assertFalse("bleStop" in order); assertFalse("writerClose" in order)
                assertFailsWith<CancellationException> { repo.connectDemo() }
                consume.complete(Unit); runCurrent()
                assertEquals(listOf<Double?>(9.0), writer.values)
                assertTrue("finish" in order); assertFalse(closing.isCompleted)
                release.complete(Unit); closing.await()
                assertTrue(order.indexOf("sample") < order.indexOf("finishAck"))
                assertTrue(order.indexOf("finishAck") < order.indexOf("writerClose"))
                assertTrue(order.indexOf("writerClose") < order.indexOf("bleStop"))
                assertEquals(ConnectionState.Disconnected, repo.connectionState.value)
                assertNull(controller.state.value.intent.ride)
            } finally { consume.complete(Unit); queued.discard(); release.complete(Unit); closing.cancelAndJoin(); repo.shutdownRecordingRuntime(); runtime?.close() }
        }
    }
}
