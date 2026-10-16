package ru.sodovaya.volty.data.ble

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import ru.sodovaya.volty.data.history.*
import ru.sodovaya.volty.domain.history.*
import ru.sodovaya.volty.domain.model.ConnectionState
import ru.sodovaya.volty.domain.repository.*
import kotlin.test.*
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class HistoryRecorderSelectionTest {
    private class Legacy : RideHistoryRepository by NoOpRideHistoryRepository {
        val started = mutableListOf<String>()
        val finished = mutableListOf<String>()
        override suspend fun startRide(summary: RideSummary) { started += summary.id }
        override suspend fun finishRide(rideId: String, endedAt: Instant) { finished += rideId }
    }
    private class Writer : HistoryWriter {
        override val diagnostics = MutableStateFlow(HistoryWriterDiagnostics())
        override suspend fun begin(generation: Long, ride: HistoryRide, sources: List<HistorySource>) = Unit
        override fun offer(generation: Long, batch: HistoryBatch) = HistoryOffer.ACCEPTED
        override suspend fun finish(generation: Long, tick: HistoryTick, status: HistoryRideStatus, rideId: String?) = Unit
        override suspend fun flush() = Unit
        override suspend fun delete(rideId: String) = Unit
        override suspend fun close() = Unit
    }
    private fun ingress() = HistoryTelemetryIngress(RideRecordingController(Writer(), HistoryRecordingDemand {},
        { "native" }, { 0 }), { HistoryTick(1, 1, 1) }, manageConnections = true)

    @Test fun defaultChoiceStillRecordsAndFinishesLegacyRide() {
        val legacy = Legacy()
        bleRepositoryTest(rideHistoryRepository = legacy) { repo ->
            try {
                assertTrue(repo.connectDemo().isSuccess); runCurrent()
                assertIs<ConnectionState.Connected>(repo.connectionState.value)
                assertEquals(1, legacy.started.size)
                repo.disconnect(); runCurrent()
                assertEquals(legacy.started, legacy.finished)
            } finally { repo.disconnect(); runCurrent() }
        }
    }

    @Test fun installedFullStreamOwnerNeverAlsoStartsLegacyEvenForUnsupportedDemo() {
        val legacy = Legacy()
        bleRepositoryTest(rideHistoryRepository = legacy) { repo ->
            try {
                repo.configureHistoryTelemetryIngress(ingress())
                assertTrue(repo.connectDemo().isSuccess); runCurrent()
                assertIs<ConnectionState.Connected>(repo.connectionState.value)
                assertTrue(legacy.started.isEmpty(), "A configured native owner must not have a shadow legacy recorder")
                repo.disconnect(); runCurrent()
                assertTrue(legacy.finished.isEmpty())
            } finally { repo.disconnect(); runCurrent() }
        }
    }

    @Test fun startupChoiceCanBeRevisedBeforeSealingButCannotBeChangedByLaterConnections() {
        val legacy = Legacy()
        bleRepositoryTest(rideHistoryRepository = legacy) { repo ->
            try {
                repo.configureHistoryTelemetryIngress(ingress())
                repo.configureHistoryTelemetryIngress(null)
                assertTrue(repo.connectDemo().isSuccess); runCurrent()
                assertEquals(1, legacy.started.size)
                assertFailsWith<IllegalStateException> { repo.configureHistoryTelemetryIngress(ingress()) }
                repo.disconnect(); runCurrent()
                assertTrue(repo.connectDemo().isSuccess); runCurrent()
                assertEquals(2, legacy.started.size)
                repo.disconnect(); runCurrent()
                assertEquals(legacy.started, legacy.finished)
            } finally { repo.disconnect(); runCurrent() }
        }
    }

    @Test fun sealedNativeChoiceDoesNotFallBackToLegacyOnAnotherConnection() {
        val legacy = Legacy()
        bleRepositoryTest(rideHistoryRepository = legacy) { repo ->
            try {
                repo.configureHistoryTelemetryIngress(ingress())
                assertTrue(repo.connectDemo().isSuccess); runCurrent()
                repo.disconnect(); runCurrent()
                assertFailsWith<IllegalStateException> { repo.configureHistoryTelemetryIngress(null) }
                assertTrue(repo.connectDemo().isSuccess); runCurrent()
                assertIs<ConnectionState.Connected>(repo.connectionState.value)
                assertTrue(legacy.started.isEmpty())
            } finally { repo.disconnect(); runCurrent() }
        }
    }
}
