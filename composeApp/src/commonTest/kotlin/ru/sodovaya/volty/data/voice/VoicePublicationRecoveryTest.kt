package ru.sodovaya.volty.data.voice

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceTimeBy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class VoicePublicationRecoveryTest {
    @Test fun mutedFullReconnectRestoresOnlyAfterTransportAndAcknowledgesClosedPublication() = runTest {
        val port = Port(block = true)
        var published = 0; var failed = 0
        val recovery = VoicePublicationRecovery(backgroundScope, port, { published++ }, { failed++ })
        recovery.reconnecting(); recovery.unpublished(wasMuted = true); runCurrent()
        assertEquals(0, port.calls)
        recovery.connected(); runCurrent()
        assertEquals(1, port.calls); assertTrue(port.entered.isCompleted)
        assertEquals(0, published)
        assertFalse(recovery.sdkPublished()) // SDK callback inside suspended restore is not final acknowledgement.
        port.gate.complete(Unit); runCurrent()
        assertEquals(1, published); assertEquals(0, failed)
        assertTrue(port.available)
        recovery.close()
    }

    @Test fun lateSdkRemovalRestoresAgainEvenIfFirstRestorationWasSubsequentlyUnmuted() = runTest {
        val port = Port(); var published = 0
        val recovery = VoicePublicationRecovery(backgroundScope, port, { published++ }, { error("unexpected failure") })
        recovery.reconnecting(); recovery.unpublished(true); recovery.connected(); runCurrent()
        assertEquals(1, published); assertEquals(1, port.calls)
        // The driver may now apply unmute. SDK still holds the original skipped muted publication.
        port.available = false
        recovery.unpublished(wasMuted = false); runCurrent()
        assertEquals(2, port.calls); assertEquals(2, published)
        assertEquals(1, port.maxConcurrent)
        recovery.close()
    }

    @Test fun duplicateConnectedAndRemovalObservationsNeverStartParallelRestores() = runTest {
        val port = Port(block = true); var published = 0
        val recovery = VoicePublicationRecovery(backgroundScope, port, { published++ }, { error("unexpected failure") })
        recovery.reconnecting(); recovery.unpublished(true); recovery.connected(); runCurrent()
        assertEquals(1, port.calls)
        recovery.connected(); recovery.unpublished(true); recovery.connected(); runCurrent()
        assertEquals(1, port.calls)
        port.gate.complete(Unit); runCurrent()
        assertEquals(1, published); assertEquals(1, port.maxConcurrent)
        recovery.close()
    }

    @Test fun sdkOwnedUnmutedRestorationAndSoftResumeDoNotCreateAppPublication() = runTest {
        val port = Port()
        val recovery = VoicePublicationRecovery(backgroundScope, port, {}, { error("unexpected failure") })
        recovery.reconnecting(); recovery.unpublished(false); recovery.connected(); runCurrent()
        assertEquals(0, port.calls); assertTrue(recovery.sdkPublished())
        recovery.reconnecting(); recovery.connected(); runCurrent()
        assertEquals(0, port.calls)
        recovery.close()
    }

    @Test fun closeRejectsCancellationResistantLatePublicationAndFutureCallbacks() = runTest {
        val port = Port(block = true, resistCancellation = true); var published = 0; var failed = 0
        val recovery = VoicePublicationRecovery(backgroundScope, port, { published++ }, { failed++ })
        recovery.reconnecting(); recovery.unpublished(true); recovery.connected(); runCurrent()
        assertTrue(port.entered.isCompleted)
        recovery.close(); port.gate.complete(Unit); runCurrent()
        recovery.connected(); recovery.unpublished(true); runCurrent()
        assertEquals(1, port.calls); assertEquals(0, published); assertEquals(0, failed)
        assertFalse(recovery.sdkPublished())
    }

    @Test fun missingOwnedPublicationAfterSuccessCannotPublishEvidence() = runTest {
        val port = Port(incoherentSuccess = true); var published = 0; var failed = 0
        val recovery = VoicePublicationRecovery(backgroundScope, port, { published++ }, { failed++ })
        recovery.reconnecting(); recovery.unpublished(true); recovery.connected(); runCurrent()
        assertEquals(1, port.calls); assertEquals(0, published); assertEquals(1, failed)
        recovery.close()
    }

    @Test fun hangingRestoreIsBoundedAndEmitsOneFailure() = runTest {
        val port = Port(block = true); var published = 0; var failed = 0
        val recovery = VoicePublicationRecovery(backgroundScope, port, { published++ }, { failed++ })
        recovery.reconnecting(); recovery.unpublished(true); recovery.connected(); runCurrent()
        assertTrue(port.entered.isCompleted)
        advanceTimeBy(5_000); runCurrent()
        assertEquals(1, failed); assertEquals(0, published)
        recovery.connected(); recovery.unpublished(true); runCurrent()
        assertEquals(1, port.calls); assertEquals(1, failed)
        recovery.close(); port.gate.complete(Unit)
    }

    @Test fun repeatedConfirmedLossHasBoundedAttemptsUntilNextReconnectCycle() = runTest {
        val port = Port(); var failed = 0
        val recovery = VoicePublicationRecovery(backgroundScope, port, {}, { failed++ })
        recovery.reconnecting(); recovery.unpublished(true); recovery.connected(); runCurrent()
        port.available = false; recovery.unpublished(false); runCurrent()
        assertEquals(2, port.calls)
        port.available = false; recovery.unpublished(false); runCurrent()
        recovery.connected(); runCurrent()
        assertEquals(2, port.calls); assertEquals(1, failed)
        recovery.reconnecting(); recovery.unpublished(true); recovery.connected(); runCurrent()
        assertEquals(3, port.calls); assertEquals(1, failed)
        recovery.close()
    }

    @Test fun delayedMutedRemovalAfterReconnectedStillSelectsClosedRestoration() = runTest {
        val port = Port(); var published = 0
        val recovery = VoicePublicationRecovery(backgroundScope, port, { published++ }, { error("unexpected failure") })
        recovery.reconnecting(); recovery.connected(); runCurrent()
        assertEquals(0, port.calls)
        recovery.unpublished(wasMuted = true); runCurrent()
        assertEquals(1, port.calls); assertEquals(1, published)
        recovery.close()
    }

    @Test fun settledSdkPublicationDoesNotSelectAppRestoreForUnrelatedLaterRemoval() = runTest {
        val port = Port()
        val recovery = VoicePublicationRecovery(backgroundScope, port, {}, { error("unexpected failure") })
        recovery.reconnecting(); recovery.connected()
        assertTrue(recovery.sdkPublished())
        recovery.unpublished(wasMuted = true); runCurrent()
        assertEquals(0, port.calls)
        recovery.close()
    }

    @Test fun interruptedRestoreCannotAcknowledgeNextCycleOrOverlapItsReplacement() = runTest {
        val port = Port(block = true, resistCancellation = true); var published = 0; var failed = 0
        val recovery = VoicePublicationRecovery(backgroundScope, port, { published++ }, { failed++ })
        recovery.reconnecting(); recovery.unpublished(true); recovery.connected(); runCurrent()
        assertEquals(1, port.calls)
        recovery.reconnecting(); recovery.connected(); runCurrent()
        assertEquals(1, port.calls) // Old cancellation-resistant operation still owns the slot.
        port.gate.complete(Unit); runCurrent()
        assertEquals(2, port.calls); assertEquals(1, published); assertEquals(0, failed)
        assertEquals(1, port.maxConcurrent)
        recovery.close()
    }

    @Test fun rejectedOrThrowingRestoreReportsOnceWithoutStartingAnotherAttempt() = runTest {
        for (throws in listOf(false, true)) {
            var calls = 0; var published = 0; var failed = 0
            val recovery = VoicePublicationRecovery(backgroundScope, object : VoicePublicationRecoveryPort {
                override suspend fun restoreClosed(): Boolean {
                    calls++
                    if (throws) error("private-sdk-error")
                    return false
                }
                override fun hasOwnedPublication(): Boolean = true // Deliberately incoherent: rejection still wins.
            }, { published++ }, { failed++ })
            recovery.reconnecting(); recovery.unpublished(true); recovery.connected(); runCurrent()
            recovery.connected(); recovery.unpublished(true); runCurrent()
            assertEquals(1, calls); assertEquals(0, published); assertEquals(1, failed)
            recovery.close()
        }
    }

    private class Port(
        private val block: Boolean = false,
        private val resistCancellation: Boolean = false,
        private val incoherentSuccess: Boolean = false,
    ) : VoicePublicationRecoveryPort {
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        var available = false
        var concurrent = 0
        var maxConcurrent = 0
        override suspend fun restoreClosed(): Boolean {
            calls++; concurrent++; maxConcurrent = maxOf(maxConcurrent, concurrent)
            entered.complete(Unit)
            try {
                if (block) {
                    if (resistCancellation) withContext(NonCancellable) { gate.await() } else gate.await()
                }
                available = !incoherentSuccess
                return true
            } finally { concurrent-- }
        }
        override fun hasOwnedPublication(): Boolean = available
    }
}
