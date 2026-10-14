package ru.sodovaya.volty.data.ble

import ru.sodovaya.volty.data.bms.*
import ru.sodovaya.volty.domain.model.*
import kotlin.test.*

class SessionTelemetryCaptureTest {
    private class Emitter : BmsProtocol() {
        override val uuids = BmsUuids("s", "n", "w")
        var decodes = 0
        var failDecode = false
        var latest: BmsData? = null
        override fun handshakeCommands() = emptyList<ByteArray>()
        override fun pollCommands() = emptyList<ByteArray>()
        override fun latestData(packIndex: Int) = latest
        override fun reset() { latest = null }
        override fun onNotification(data: ByteArray) {
            decodes++
            for (value in data) {
                latest = BmsData(current = value.toFloat())
                observeTelemetry(TelemetryOrigin.BATTERY, 0) { listOf(TelemetryReading.measured(TelemetryMetric.CURRENT, value.toDouble())) }
            }
            if (failDecode) error("injected decoder failure")
        }
    }

    @Test fun oneReceiptIsReadBeforeSideEffectsAndSharedByAllCoalescedFacts() {
        val protocol = Emitter(); val events = mutableListOf<ReceivedTelemetry>(); val order = mutableListOf<String>()
        val capture = SessionTelemetryCapture(protocol, "attempt-a", { order += "time"; TelemetryReceiptTime(1000, 50) }, { true }, { events += it })
        capture.processNotification(byteArrayOf(1, 9, 1)) { order += "before" }
        assertEquals(listOf("time", "before"), order)
        assertEquals(3, events.size); assertEquals(listOf(1.0, 9.0, 1.0), events.map { it.observation.readings.single().value })
        assertTrue(events.all { it.receipt.sessionId == "attempt-a" && it.receipt.notificationOrdinal == 1L })
        assertSame(events.first().receipt, events.last().receipt)
        capture.processNotification(byteArrayOf(2))
        assertEquals(2L, events.last().receipt.notificationOrdinal); assertEquals(2, protocol.decodes)
    }

    @Test fun closedAndSupersededAttemptsDoNotDecodeOrReadClock() {
        val protocol = Emitter(); var owner = true; var reads = 0; val events = mutableListOf<ReceivedTelemetry>()
        val capture = SessionTelemetryCapture(protocol, "old", { reads++; TelemetryReceiptTime(1, 1) }, { owner }, { events += it })
        owner = false; assertFalse(capture.processNotification(byteArrayOf(1)))
        assertEquals(0, reads); assertEquals(0, protocol.decodes)
        owner = true; capture.close(); assertFalse(capture.processNotification(byteArrayOf(2)))
        assertEquals(0, reads); assertTrue(events.isEmpty())
    }

    @Test fun ownershipLossAfterClockReadPreventsDecode() {
        val protocol = Emitter(); var owner = true
        val capture = SessionTelemetryCapture(protocol, "old", { owner = false; TelemetryReceiptTime(1, 1) }, { owner }, { error("stale event") })
        assertFalse(capture.processNotification(byteArrayOf(1)))
        assertEquals(0, protocol.decodes)
    }

    @Test fun closureDuringFirstFactSuppressesFurtherFactsButLiveDecodeCompletes() {
        val protocol = Emitter(); val events = mutableListOf<ReceivedTelemetry>()
        lateinit var capture: SessionTelemetryCapture
        capture = SessionTelemetryCapture(protocol, "old", { TelemetryReceiptTime(1, 1) }, { true }, { events += it; capture.close() })
        capture.processNotification(byteArrayOf(1, 2, 3))
        assertEquals(1, events.size); assertEquals(3f, assertNotNull(protocol.latest).current)
        assertEquals(1, protocol.decodes)
    }

    @Test fun exhaustedOrdinalStillRefreshesReceiptContextForEachActualDecodedLoss() {
        val protocol = Emitter(); var owner = "a"; var capturedOwner = "none"; var clocks = 0
        val stamped = mutableListOf<ReceivedTelemetry>(); val lostOwners = mutableListOf<String>()
        val capture = SessionTelemetryCapture(protocol, "attempt", {
            clocks++; capturedOwner = owner; TelemetryReceiptTime(clocks.toLong(), clocks.toLong())
        }, { true }, { stamped += it }, { lostOwners += capturedOwner }, initialNotificationOrdinal = Long.MAX_VALUE - 1)
        capture.processNotification(byteArrayOf(1))
        assertEquals(Long.MAX_VALUE, stamped.single().receipt.notificationOrdinal)
        owner = "b"
        capture.processNotification(byteArrayOf(2))
        owner = "c"
        capture.processNotification(byteArrayOf(3))
        assertEquals(3, clocks, "receipt/context clock must run once even when no ordinal remains")
        assertEquals(listOf("b", "c"), lostOwners)
        assertEquals(1, stamped.size)
        assertEquals(2L, capture.receiptFailures)
        assertEquals(3, protocol.decodes)
        assertEquals(0L, protocol.telemetryObserverFailures)
    }

    @Test fun clockFailureReportsOnlyActuallyDecodedObservationsWithoutInventedReceipt() {
        val protocol = Emitter(); val lost = mutableListOf<TelemetryObservation>()
        val capture = SessionTelemetryCapture(protocol, "attempt", { error("clock failed") }, { true },
            { error("no stamped event permitted") }, { lost += it })
        capture.processNotification(byteArrayOf(1, 99, 0))
        assertEquals(1, protocol.decodes)
        assertEquals(1L, capture.receiptFailures)
        assertEquals(listOf(1.0, 99.0, 0.0), lost.map { it.readings.single().value })
        assertEquals(0L, protocol.telemetryObserverFailures)
        assertNull(protocol.telemetryObserver)
    }

    @Test fun clockFailureKeepsLiveDecodeAndIsCountedWithoutInventingReceipt() {
        val protocol = Emitter(); val events = mutableListOf<ReceivedTelemetry>(); var fail = true
        val capture = SessionTelemetryCapture(protocol, "a", { if (fail) error("clock unavailable"); TelemetryReceiptTime(2, 2) }, { true }, { events += it })
        capture.processNotification(byteArrayOf(7))
        assertEquals(7f, assertNotNull(protocol.latest).current); assertTrue(events.isEmpty()); assertEquals(1, capture.receiptFailures)
        fail = false; capture.processNotification(byteArrayOf(8))
        assertEquals(2L, events.single().receipt.notificationOrdinal)
    }

    @Test fun scopedObserverIsRestoredOnDecodeFailureAndDoesNotReplaceNewOwnerObserver() {
        val protocol = Emitter(); var priorCalls = 0
        val prior: (TelemetryObservation) -> Unit = { priorCalls++ }; protocol.telemetryObserver = prior
        val capture = SessionTelemetryCapture(protocol, "a", { TelemetryReceiptTime(1, 1) }, { true }, {})
        assertFailsWith<IllegalStateException> { capture.processNotification(byteArrayOf(1)) { error("before decode failed") } }
        assertSame(prior, protocol.telemetryObserver)
        protocol.failDecode = true
        assertFailsWith<IllegalStateException> { capture.processNotification(byteArrayOf(1)) }
        assertSame(prior, protocol.telemetryObserver)
        protocol.failDecode = false
        var replacementCalls = 0
        val replacement: (TelemetryObservation) -> Unit = { replacementCalls++ }
        val replacing = SessionTelemetryCapture(protocol, "b", { TelemetryReceiptTime(2, 2) }, { true }, { protocol.telemetryObserver = replacement })
        replacing.processNotification(byteArrayOf(1))
        assertSame(replacement, protocol.telemetryObserver)
        assertEquals(0, priorCalls); assertEquals(0, replacementCalls)
    }

    @Test fun observerFailureCannotBreakLiveDecodeOrLeakScopedBinding() {
        val protocol = Emitter(); var callbacks = 0
        val capture = SessionTelemetryCapture(protocol, "a", { TelemetryReceiptTime(1, 1) }, { true }, { callbacks++; error("recording callback failed") })
        capture.processNotification(byteArrayOf(1, 2))
        assertEquals(2, callbacks); assertEquals(2, protocol.telemetryObserverFailures)
        assertEquals(2f, assertNotNull(protocol.latest).current); assertNull(protocol.telemetryObserver)
    }

    @Test fun rawDecodesOutsideReceiptScopeCannotAcquireAStaleReceipt() {
        val protocol = Emitter(); val events = mutableListOf<ReceivedTelemetry>()
        val capture = SessionTelemetryCapture(protocol, "a", { TelemetryReceiptTime(1, 1) }, { true }, { events += it })
        capture.processNotification(byteArrayOf(1)); protocol.onNotification(byteArrayOf(2))
        assertEquals(1, events.size); assertNull(protocol.telemetryObserver)
        assertFailsWith<IllegalArgumentException> { capture.requireProtocol(Emitter()) }
    }

    @Test fun actualNotificationBoundaryCapturesTimeBeforeExternalTraceAndPreservesDefaultDecode() {
        val protocol = Emitter(); val events = mutableListOf<ReceivedTelemetry>(); val order = mutableListOf<String>()
        val capture = SessionTelemetryCapture(protocol, "a", { order += "time"; TelemetryReceiptTime(1, 1) }, { true }, { events += it })
        val activity = NoSampleEverWatchdogActivity(protocol)
        processObservedSessionNotification(protocol, byteArrayOf(1, 2), activity, capture) { order += "trace" }
        assertEquals(listOf("time", "trace"), order); assertEquals(2, events.size)
        processObservedSessionNotification(protocol, byteArrayOf(3), activity)
        assertEquals(2, events.size); assertEquals(2, protocol.decodes); assertEquals(3f, assertNotNull(protocol.latest).current)
    }

    @Test fun realBegodeFragmentsUseCompletionReceiptAndNeverInventPerFrameTimes() {
        val protocol = BegodeProtocol(); val events = mutableListOf<ReceivedTelemetry>(); var clocks = 0L
        val capture = SessionTelemetryCapture(protocol, "et-max-attempt", { clocks++; TelemetryReceiptTime(1000 + clocks, clocks) }, { true }, { events += it })
        val chunks = BegodeDumpFixture.chunks()
        chunks.forEach { capture.processNotification(it) }
        assertEquals(chunks.size.toLong(), clocks)
        assertEquals(228, events.size)
        assertEquals(2L, events.first().receipt.notificationOrdinal) // the first chunk has no complete frame yet.
        assertTrue(events.all { it.receipt.time == TelemetryReceiptTime(1000 + it.receipt.notificationOrdinal, it.receipt.notificationOrdinal) })
        assertEquals(114, events.count { it.observation.origin == TelemetryOrigin.CONTROLLER })
        assertEquals(76, events.count { it.observation.origin == TelemetryOrigin.BATTERY && it.observation.role == TelemetryRole.CONTRIBUTOR })
        assertEquals(38, events.count { it.observation.role == TelemetryRole.ASSEMBLY })
        assertTrue(events.zipWithNext().all { (a, b) -> b.receipt.notificationOrdinal >= a.receipt.notificationOrdinal })
        for (group in events.groupBy { it.receipt.notificationOrdinal }.values) assertTrue(group.all { it.receipt === group.first().receipt })
        assertNull(protocol.telemetryObserver); assertEquals(0, capture.receiptFailures)
    }
}
