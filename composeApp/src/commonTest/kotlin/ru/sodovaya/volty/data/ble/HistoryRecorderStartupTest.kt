package ru.sodovaya.volty.data.ble

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import ru.sodovaya.volty.data.history.*
import ru.sodovaya.volty.domain.history.*
import ru.sodovaya.volty.domain.location.RideLocationState
import ru.sodovaya.volty.domain.model.ConnectionState
import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.repository.*
import kotlin.test.*
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class HistoryRecorderStartupTest {
    private class Legacy : RideHistoryRepository by NoOpRideHistoryRepository {
        val begins = mutableListOf<String>()
        override suspend fun startRide(summary: RideSummary) { begins += summary.id }
    }
    private class Writer : HistoryWriter {
        override val diagnostics = MutableStateFlow(HistoryWriterDiagnostics())
        var begins = 0
        var closes = 0
        override suspend fun begin(generation: Long, ride: HistoryRide, sources: List<HistorySource>) { begins++ }
        override fun offer(generation: Long, batch: HistoryBatch) = HistoryOffer.ACCEPTED
        override suspend fun finish(generation: Long, tick: HistoryTick, status: HistoryRideStatus, rideId: String?) = Unit
        override suspend fun flush() = Unit
        override suspend fun delete(rideId: String) = Unit
        override suspend fun close() { closes++ }
    }
    private fun ingress() = HistoryTelemetryIngress(RideRecordingController(Writer(), HistoryRecordingDemand {},
        { "ride" }, { 0 }), { HistoryTick(100, 10, 1) }, manageConnections = true)

    @Test fun connectionWaitsForStartupThenSealsNativeChoiceAcrossReconnect() {
        val release = CompletableDeferred<Unit>(); val legacy = Legacy(); var calls = 0
        val ingress = ingress()
        bleRepositoryTest(rideHistoryRepository = legacy, historyStartup = { calls++; release.await(); ingress }) { repo ->
            val connect = backgroundScope.async { repo.connectDemo() }
            try {
                runCurrent(); assertEquals(1, calls); assertFalse(connect.isCompleted)
                assertFalse(repo.connectionState.value is ConnectionState.Connected); assertTrue(legacy.begins.isEmpty())
                release.complete(Unit); assertTrue(connect.await().isSuccess); runCurrent()
                assertIs<ConnectionState.Connected>(repo.connectionState.value); assertTrue(legacy.begins.isEmpty())
                repo.disconnect(); assertTrue(repo.connectDemo().isSuccess); runCurrent()
                assertEquals(1, calls); assertTrue(legacy.begins.isEmpty())
                assertFailsWith<IllegalStateException> { repo.configureHistoryTelemetryIngress(null) }
            } finally { release.complete(Unit); connect.cancelAndJoin(); repo.disconnect(); runCurrent() }
        }
    }

    @Test fun physicalGuestEntryAlsoAwaitsStartupBeforeBluetoothAvailabilityFailure() {
        val release = CompletableDeferred<Unit>(); var calls = 0; val legacy = Legacy()
        bleRepositoryTest(rideHistoryRepository = legacy, bleAdapterStateProvider = BleAdapterStateProvider { false },
            historyStartup = { calls++; release.await(); ingress() }) { repo ->
            val connect = backgroundScope.async { repo.connectGuest("AA", BmsType.ANT_BMS) }
            try {
                runCurrent(); assertEquals(1, calls); assertFalse(connect.isCompleted)
                assertFalse(repo.connectionState.value is ConnectionState.Failed)
                release.complete(Unit); assertTrue(connect.await().isFailure); runCurrent()
                assertIs<ConnectionState.Failed>(repo.connectionState.value); assertTrue(legacy.begins.isEmpty())
                assertTrue(repo.connectDemo().isSuccess); runCurrent()
                assertEquals(1, calls); assertTrue(legacy.begins.isEmpty())
            } finally { release.complete(Unit); connect.cancelAndJoin(); repo.disconnect(); runCurrent() }
        }
    }

    @Test fun startupFailureDoesNotConnectOrStartLegacyAndCanRetry() {
        val legacy = Legacy(); var calls = 0; val ingress = ingress()
        bleRepositoryTest(rideHistoryRepository = legacy, historyStartup = {
            calls++; check(calls > 1) { "recovery failed" }; ingress
        }) { repo ->
            try {
                val first = repo.connectDemo(); assertTrue(first.isFailure)
                assertFalse(repo.connectionState.value is ConnectionState.Connected)
                runCurrent(); assertTrue(legacy.begins.isEmpty())
                assertTrue(repo.connectDemo().isSuccess); runCurrent()
                assertEquals(2, calls); assertTrue(legacy.begins.isEmpty())
            } finally { repo.disconnect(); runCurrent() }
        }
    }

    @Test fun disconnectDuringStartupAcquiresSessionLockAndInvalidatesLateConnection() {
        val release = CompletableDeferred<Unit>(); var entered = false; val legacy = Legacy()
        bleRepositoryTest(rideHistoryRepository = legacy, historyStartup = { entered = true; release.await(); ingress() }) { repo ->
            val connect = backgroundScope.async { repo.connectDemo() }
            var disconnect: Job? = null
            try {
                runCurrent(); assertTrue(entered); assertFalse(connect.isCompleted)
                disconnect = backgroundScope.launch { repo.disconnect() }; runCurrent()
                assertTrue(disconnect.isCompleted, "History preparation must not hold the BLE session mutex")
                assertIs<ConnectionState.Disconnected>(repo.connectionState.value)
                release.complete(Unit); assertTrue(connect.await().isFailure); runCurrent()
                assertIs<ConnectionState.Disconnected>(repo.connectionState.value); assertTrue(legacy.begins.isEmpty())
                assertTrue(repo.connectDemo().isSuccess); runCurrent()
                assertIs<ConnectionState.Connected>(repo.connectionState.value); assertTrue(legacy.begins.isEmpty())
            } finally { release.complete(Unit); connect.cancelAndJoin(); disconnect?.cancelAndJoin(); repo.disconnect(); runCurrent() }
        }
    }

    @Test fun latestConcurrentRequestWinsAndOnlyOneStartupIsIssued() {
        val release = CompletableDeferred<Unit>(); var calls = 0; val legacy = Legacy(); val ingress = ingress()
        bleRepositoryTest(rideHistoryRepository = legacy, historyStartup = { calls++; release.await(); ingress }) { repo ->
            val first = backgroundScope.async { repo.connectDemo() }
            var second: Deferred<Result<Unit>>? = null
            try {
                runCurrent(); assertEquals(1, calls)
                second = backgroundScope.async { repo.connectDemo() }; runCurrent()
                assertEquals(1, calls); assertFalse(first.isCompleted); assertFalse(second.isCompleted)
                release.complete(Unit)
                assertTrue(first.await().isFailure); assertTrue(second.await().isSuccess); runCurrent()
                assertEquals(1, calls); assertIs<ConnectionState.Connected>(repo.connectionState.value)
                assertTrue(legacy.begins.isEmpty())
            } finally { release.complete(Unit); first.cancelAndJoin(); second?.cancelAndJoin(); repo.disconnect(); runCurrent() }
        }
    }

    @Test fun callerCancellationBeforeStartupAckDoesNotSealLegacyOrPreventRetry() {
        val release = CompletableDeferred<Unit>(); var calls = 0; val legacy = Legacy()
        bleRepositoryTest(rideHistoryRepository = legacy, historyStartup = { calls++; release.await(); ingress() }) { repo ->
            val first = backgroundScope.launch { repo.connectDemo() }
            try {
                runCurrent(); assertEquals(1, calls); first.cancelAndJoin()
                assertFalse(repo.connectionState.value is ConnectionState.Connected); assertTrue(legacy.begins.isEmpty())
                release.complete(Unit); assertTrue(repo.connectDemo().isSuccess); runCurrent()
                assertEquals(2, calls); assertTrue(legacy.begins.isEmpty())
            } finally { release.complete(Unit); first.cancelAndJoin(); repo.disconnect(); runCurrent() }
        }
    }

    @Test fun repositoryCloseDuringStartupCannotPublishLateConnectedState() {
        val release = CompletableDeferred<Unit>(); var entered = false; val legacy = Legacy()
        bleRepositoryTest(rideHistoryRepository = legacy, historyStartup = { entered = true; release.await(); ingress() }) { repo ->
            val connect = backgroundScope.async { runCatching { repo.connectDemo() } }
            try {
                runCurrent(); assertTrue(entered); repo.close(); release.complete(Unit)
                assertIs<CancellationException>(connect.await().exceptionOrNull())
                assertFalse(repo.connectionState.value is ConnectionState.Connected); assertTrue(legacy.begins.isEmpty())
            } finally { release.complete(Unit); connect.cancelAndJoin() }
        }
    }

    @Test fun closeAfterBootstrapWhileHistorySelectionWaitsCannotPublishConnected() {
        val release = CompletableDeferred<Unit>(); var entered = false; var serviceStarts = 0
        val controller = RideRecordingController(Writer(), HistoryRecordingDemand {}, { "ride" },
            { entered = true; release.await(); 0 })
        val ingress = HistoryTelemetryIngress(controller, { HistoryTick(100, 10, 1) }, manageConnections = true)
        bleRepositoryTest(serviceStart = { serviceStarts++ }, historyStartup = { ingress }) { repo ->
            // A retained never-ready selection makes demo replacement issue Selected;
            // its real controller recovery ACK gates the post-bootstrap continuation.
            ingress.installNativePlan("previous", emptyList(), null) { true }
            val connect = backgroundScope.async { runCatching { repo.connectDemo() } }
            try {
                runCurrent(); assertTrue(entered); assertFalse(connect.isCompleted)
                repo.close(); release.complete(Unit)
                assertIs<CancellationException>(connect.await().exceptionOrNull())
                assertFalse(repo.connectionState.value is ConnectionState.Connected)
                assertEquals(0, serviceStarts)
            } finally { release.complete(Unit); connect.cancelAndJoin() }
        }
    }

    @Test fun disconnectAfterBootstrapDuringHistorySelectionPreventsEvenTransientServiceStart() {
        val release = CompletableDeferred<Unit>(); var entered = false; var serviceStarts = 0
        val controller = RideRecordingController(Writer(), HistoryRecordingDemand {}, { "ride" },
            { entered = true; release.await(); 0 })
        val ingress = HistoryTelemetryIngress(controller, { HistoryTick(100, 10, 1) }, manageConnections = true)
        bleRepositoryTest(serviceStart = { serviceStarts++ }, historyStartup = { ingress }) { repo ->
            ingress.installNativePlan("previous", emptyList(), null) { true }
            val connect = backgroundScope.async { repo.connectDemo() }
            var disconnect: Job? = null
            try {
                runCurrent(); assertTrue(entered); assertFalse(connect.isCompleted)
                disconnect = backgroundScope.launch { repo.disconnect() }; runCurrent()
                assertFalse(disconnect.isCompleted, "Native history selection still owns accepted control")
                release.complete(Unit); assertTrue(connect.await().isFailure); disconnect.join()
                assertEquals(0, serviceStarts)
                assertIs<ConnectionState.Disconnected>(repo.connectionState.value)
            } finally { release.complete(Unit); connect.cancelAndJoin(); disconnect?.cancelAndJoin(); repo.disconnect(); runCurrent() }
        }
    }

    @Test fun closedRepositoryIgnoresLatePhysicalLinkStateAfterPreviouslyReachedOnline() = bleRepositoryTest { repo ->
        repo.installLinksForTest(null, "AA", BmsType.ANT_BMS)
        repo.markLinkOnlineForTest("AA")
        assertIs<ConnectionState.Connected>(repo.connectionState.value)
        repo.markLinkFailedForTest("AA", "before close")
        val beforeClose = assertIs<ConnectionState.Failed>(repo.connectionState.value)
        repo.close()
        repo.markLinkOnlineForTest("AA")
        assertEquals(beforeClose, repo.connectionState.value)
        repo.markLinkFailedForTest("AA", "late failure")
        assertEquals(beforeClose, repo.connectionState.value)
    }

    @Test fun ordinaryServiceFailureAfterRepoCloseCannotPublishLateFailed() {
        var repository: KableBmsRepository? = null
        var atClose: ConnectionState? = null
        bleRepositoryTest(historyStartup = { ingress() }, serviceStart = {
            val repo = assertNotNull(repository)
            atClose = assertIs<ConnectionState.Connected>(repo.connectionState.value)
            repo.close()
            error("service failed after close")
        }) { repo ->
            repository = repo
            assertTrue(repo.connectDemo().isFailure)
            assertNotNull(atClose)
            assertEquals(atClose, repo.connectionState.value)
        }
    }

    @Test fun ordinaryServiceFailureStillPublishesFailureForCurrentLiveRequest() =
        bleRepositoryTest(historyStartup = { ingress() }, serviceStart = { error("service unavailable") }) { repo ->
            try {
                assertTrue(repo.connectDemo().isFailure)
                assertIs<ConnectionState.Failed>(repo.connectionState.value)
            } finally { repo.disconnect(); runCurrent() }
        }

    @Test fun runtimeOwnedChoiceCannotBeOverriddenDuringPreparation() {
        val release = CompletableDeferred<Unit>(); var entered = false
        bleRepositoryTest(historyStartup = { entered = true; release.await(); ingress() }) { repo ->
            val connect = backgroundScope.async { repo.connectDemo() }
            try {
                runCurrent(); assertTrue(entered)
                assertFailsWith<IllegalStateException> { repo.configureHistoryTelemetryIngress(null) }
                release.complete(Unit); assertTrue(connect.await().isSuccess)
            } finally { release.complete(Unit); connect.cancelAndJoin(); repo.disconnect(); runCurrent() }
        }
    }

    @Test fun actualRuntimeRecoversBeforeRepositoryConnectionAndHasNoLegacyShadow() {
        val release = CompletableDeferred<Unit>(); val legacy = Legacy(); val writer = Writer()
        var preparations = 0; var allocations = 0; var runtime: HistoryRecordingRuntime? = null
        bleRepositoryTest(rideHistoryRepository = legacy, historyStartup = { assertNotNull(runtime).prepare().ingress }) { repo ->
            runtime = HistoryRecordingRuntime(HistoryRecordingClock({ preparations++; release.await(); 8 }, { 100 }, { 10 }),
                StandardTestDispatcher(testScheduler), { allocations++; writer }, MutableStateFlow(RideLocationState()),
                HistoryLocationForegroundAuthority({ true }) { "10000000-0000-0000-0000-000000000001" }, {},
                { _, _ -> error("Unsupported demo must not allocate native GPS") }, { "ride" })
            val connect = backgroundScope.async { repo.connectDemo() }
            try {
                runCurrent(); assertEquals(1, preparations); assertEquals(0, allocations)
                assertFalse(connect.isCompleted); assertTrue(legacy.begins.isEmpty())
                release.complete(Unit); assertTrue(connect.await().isSuccess); runCurrent()
                assertEquals(1, allocations); assertEquals(0, writer.begins); assertTrue(legacy.begins.isEmpty())
                repo.disconnect(); assertTrue(repo.connectDemo().isSuccess); runCurrent()
                assertEquals(1, preparations); assertEquals(1, allocations); assertTrue(legacy.begins.isEmpty())
            } finally { release.complete(Unit); connect.cancelAndJoin(); repo.disconnect(); runCurrent(); runtime?.close() }
            assertEquals(1, writer.closes)
        }
    }
}
