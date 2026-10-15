package ru.sodovaya.volty.data.ble

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import ru.sodovaya.volty.data.bms.*
import ru.sodovaya.volty.domain.model.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SessionTelemetryPreparationTest {
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
    private fun capture(p: Emitter, owns: () -> Boolean = { true }, events: MutableList<ReceivedTelemetry> = mutableListOf(),
        lost: MutableList<TelemetryObservation> = mutableListOf(), prepare: suspend () -> Unit) =
        SessionTelemetryCapture(p, "attempt", { TelemetryReceiptTime(1000, 10) }, owns, { events += it },
            onUnreceiptedObservation = { lost += it }, onPrepare = prepare)

    @Test fun preparationWaitsForAckOnceAndNeverPublishesPrematureFacts() = runTest {
        val entered = CompletableDeferred<Unit>(); val ack = CompletableDeferred<Unit>()
        val p = Emitter(); val events = mutableListOf<ReceivedTelemetry>(); val lost = mutableListOf<TelemetryObservation>(); var calls = 0
        val c = capture(p, events = events, lost = lost) { calls++; entered.complete(Unit); ack.await() }
        val first = backgroundScope.async { c.prepareForObservation() }
        val second = backgroundScope.async { c.prepareForObservation() }
        try {
            runCurrent(); assertTrue(entered.isCompleted); assertFalse(first.isCompleted); assertFalse(second.isCompleted)
            c.processNotification(byteArrayOf(3))
            assertTrue(events.isEmpty()); assertEquals(1, lost.size); assertEquals(1L, c.unpreparedNotifications)
            ack.complete(Unit); runCurrent(); assertTrue(first.await()); assertTrue(second.await())
            assertEquals(1, calls); assertTrue(c.isPreparedForObservation)
            c.processNotification(byteArrayOf(9)); assertEquals(9.0, events.single().observation.readings.single().value)
            assertEquals(2, p.decodes)
        } finally { ack.complete(Unit); c.close(); runCurrent() }
    }

    @Test fun failedPreparationKeepsLiveDecodeButReportsActualLostObservations() = runTest {
        val p = Emitter(); val events = mutableListOf<ReceivedTelemetry>(); val lost = mutableListOf<TelemetryObservation>(); var calls = 0
        val c = capture(p, events = events, lost = lost) { calls++; error("Preparation failed") }
        assertFalse(c.prepareForObservation()); assertFalse(c.prepareForObservation())
        c.processNotification(byteArrayOf(1, 2)); assertEquals(1, calls)
        assertEquals(1L, c.preparationFailures); assertEquals(1L, c.unpreparedNotifications)
        assertEquals(1, p.decodes); assertEquals(2, lost.size); assertTrue(events.isEmpty()); assertFalse(c.isPreparedForObservation)
    }

    @Test fun closedAndRetiredAttemptsCannotBecomePreparedAfterAck() = runTest {
        for (close in listOf(true, false)) {
            val entered = CompletableDeferred<Unit>(); val ack = CompletableDeferred<Unit>(); var owned = true
            val p = Emitter(); val c = capture(p, { owned }) { entered.complete(Unit); ack.await() }
            val pending = backgroundScope.async { c.prepareForObservation() }
            try {
                runCurrent(); assertTrue(entered.isCompleted)
                if (close) c.close() else owned = false
                ack.complete(Unit); runCurrent(); assertFalse(pending.await()); assertFalse(c.isPreparedForObservation)
                assertFalse(c.processNotification(byteArrayOf(9))); assertEquals(0, p.decodes)
            } finally { ack.complete(Unit); c.close(); runCurrent() }
        }
        var calls = 0
        val closed = capture(Emitter()) { calls++ }; closed.close()
        assertFalse(closed.prepareForObservation()); assertEquals(0, calls)
    }

    @Test fun cancellationDuringUncancellablePreparationDoesNotPublishReadyOrRetry() = runTest {
        val entered = CompletableDeferred<Unit>(); val ack = CompletableDeferred<Unit>(); var calls = 0
        val c = capture(Emitter()) { calls++; withContext(NonCancellable) { entered.complete(Unit); ack.await() } }
        val pending = backgroundScope.async { c.prepareForObservation() }
        try {
            runCurrent(); assertTrue(entered.isCompleted); pending.cancel(); ack.complete(Unit); runCurrent()
            assertFailsWith<CancellationException> { pending.await() }
            assertFalse(c.isPreparedForObservation); assertFalse(c.prepareForObservation()); assertEquals(1, calls)
        } finally { ack.complete(Unit); c.close(); runCurrent() }
    }

    @Test fun alreadyCancelledWaiterCannotReturnSuccessOnTheReadyFastPath() = runTest {
        for (defaultReady in listOf(true, false)) {
            var calls = 0
            val c = if (defaultReady) SessionTelemetryCapture(Emitter(), "default", { TelemetryReceiptTime(1, 1) }, { true }, {})
                else capture(Emitter()) { calls++ }
            try {
                assertTrue(c.prepareForObservation())
                var publishedSuccess = 0
                val waiter = backgroundScope.launch {
                    currentCoroutineContext().cancel()
                    c.prepareForObservation()
                    publishedSuccess++
                }
                runCurrent(); assertTrue(waiter.isCancelled)
                assertEquals(0, publishedSuccess)
                assertTrue(c.prepareForObservation(), "A cancelled waiter must not poison the prepared attempt")
                assertEquals(if (defaultReady) 0 else 1, calls)
                assertEquals(0L, c.preparationFailures)
            } finally { c.close() }
        }
    }

}
