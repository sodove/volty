package ru.sodovaya.volty.data.history

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryLocationForegroundAuthorityTest {
    private fun recording(id: String = "ride") = CapturedHistoryGpsRecording(id, 1, Any())
    private val uuid = "10000000-0000-0000-0000-000000000001"
    private fun authority(permission: () -> Boolean = { true }) = HistoryLocationForegroundAuthority(permission) { uuid }

    @Test fun foregroundAckAloneCannotIssueGpsBeforeNativeProviderAck() {
        val a = authority(); val owner = recording(); val ticket = a.request(owner) { }
        assertTrue(a.acknowledge(ticket, Any()))
        assertTrue(a.allows(owner))
        assertEquals(HistoryOffer.RETIRED, a.offer(owner) { error("Provider acquisition was not acknowledged") })
        assertFalse(a.canCapture(owner))
        assertTrue(a.admitCapture(ticket)); assertTrue(a.canCapture(owner))
        assertEquals(HistoryOffer.ACCEPTED, a.offer(owner) { HistoryOffer.ACCEPTED })
    }

    @Test fun captureFenceBlocksWriterBeforeCleanupWithoutBorrowingForegroundRelease() {
        val a = authority(); val owner = recording(); val ticket = a.request(owner) { }
        assertFalse(a.admitCapture(ticket))
        assertTrue(a.acknowledge(ticket, Any())); assertTrue(a.admitCapture(ticket))
        assertTrue(a.fenceCapture(ticket)); assertFalse(a.canCapture(owner))
        assertTrue(a.allows(owner), "Foreground remains retained while provider cleanup awaits")
        assertEquals(HistoryOffer.RETIRED, a.offer(owner) { error("GPS after capture fence") })
        val fresh = a.request(recording("next")) { }
        assertFalse(a.admitCapture(ticket)); assertFalse(a.fenceCapture(ticket))
        assertSame(fresh, a.state.value.ticket)
    }

    @Test fun duplicateNativeForegroundAckPreservesEarnedProviderAdmission() {
        val a = authority(); val owner = recording(); val ticket = a.request(owner) { }; val service = Any()
        assertTrue(a.acknowledge(ticket, service)); assertTrue(a.admitCapture(ticket))
        assertTrue(a.acknowledge(ticket, service))
        assertTrue(a.canCapture(owner)); assertTrue(a.state.value.captureReady)
    }

    @Test fun onlyAcknowledgedPermissionAndExactRecordingCanIssueGps() {
        var permission = false
        val a = authority { permission }; val owner = recording(); val foreign = recording()
        val ticket = a.request(owner) { }
        var issued = 0
        assertSame(ticket, a.find(ticket.id))
        assertEquals(HistoryOffer.RETIRED, a.offer(owner) { issued++; HistoryOffer.ACCEPTED })
        assertFalse(a.acknowledge(ticket, Any()))
        permission = true
        assertFalse(a.allows(owner))
        assertTrue(a.acknowledge(ticket, Any()))
        assertTrue(a.allows(owner)); assertFalse(a.allows(foreign))
        assertEquals(HistoryOffer.RETIRED, a.offer(foreign) { issued++; HistoryOffer.ACCEPTED })
        assertTrue(a.admitCapture(ticket))
        assertEquals(HistoryOffer.ACCEPTED, a.offer(owner) { issued++; HistoryOffer.ACCEPTED })
        assertEquals(1, issued)
    }

    @Test fun oldServiceAndTicketCannotRevokeReplacementRecording() {
        val a = authority(); val old = recording(); val fresh = recording("new")
        val oldService = Any(); val newService = Any(); var lost = 0
        val previous = a.request(old) { assertFalse(a.allows(old)); lost++ }
        assertTrue(a.acknowledge(previous, oldService))
        val next = a.request(fresh) { lost++ }
        assertEquals(1, lost); assertTrue(a.acknowledge(next, newService))
        assertFalse(a.acknowledge(previous, oldService)); assertFalse(a.stop(previous))
        assertFalse(a.destroyed(oldService)); assertNull(a.find(previous.id))
        assertTrue(a.allows(fresh)); assertEquals(1, lost)
        assertTrue(a.destroyed(newService)); assertFalse(a.allows(fresh))
        assertEquals(2, lost)
    }

    @Test fun permissionLossFencesWriterBeforeRefreshAndCleanup() {
        var permission = true; var lost = 0
        val a = authority { permission }; val owner = recording()
        val ticket = a.request(owner) {
            assertFalse(a.allows(owner)); assertNull(a.state.value.ticket); assertNull(a.state.value.service); lost++
        }
        assertSame(ticket, a.request(owner) { error("Replaced original cleanup") })
        assertTrue(a.acknowledge(ticket, Any()))
        permission = false
        assertEquals(HistoryOffer.RETIRED, a.offer(owner) { error("Unpermitted GPS issued") })
        a.refreshPermissions()
        assertEquals(1, lost); assertFalse(a.stop(ticket))
    }

    @Test fun throwingPermissionFailsClosedAndRetiresAtRefresh() {
        var fail = false
        val a = authority { if (fail) error("native permission check") else true }
        val owner = recording(); var lost = 0
        val ticket = a.request(owner) { lost++ }; assertTrue(a.acknowledge(ticket, Any()))
        fail = true
        assertFalse(a.allows(owner))
        assertEquals(HistoryOffer.RETIRED, a.offer(owner) { error("Must not issue") })
        a.refreshPermissions(); assertEquals(1, lost)
    }

    @Test fun malformedGeneratedIdCannotReplaceOwnerAndRepeatedUuidCannotReplayOldTicket() {
        var proposed = uuid
        val a = HistoryLocationForegroundAuthority({ true }) { proposed }
        val owner = recording(); val previous = a.request(owner) { }
        assertTrue(a.acknowledge(previous, Any()))
        proposed = "bad"
        assertFailsWith<IllegalArgumentException> { a.request(recording("other")) { } }
        assertTrue(a.allows(owner)); assertSame(previous, a.state.value.ticket)
        proposed = uuid
        assertTrue(a.stop(previous))
        val next = a.request(owner) { }
        assertNotEquals(previous.id, next.id)
        assertNull(a.find(previous.id)); assertSame(next, a.find(next.id))
        assertFalse(a.acknowledge(previous, Any()))
    }

    @Test fun synchronousObserverReplacementCannotBeOverwrittenByOlderRequest() = runTest {
        val a = authority(); var retired = 0
        val old = a.request(recording("old")) { retired++ }; assertTrue(a.acknowledge(old, Any()))
        var successor: HistoryLocationForegroundTicket? = null
        val observer = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            a.state.collect { value ->
                if (value.ticket?.recording?.rideId == "intermediate")
                    successor = a.request(recording("latest")) { retired++ }
            }
        }
        try {
            val intermediate = a.request(recording("intermediate")) { retired++ }
            assertSame(assertNotNull(successor), a.state.value.ticket)
            assertFalse(a.acknowledge(intermediate, Any()))
            assertTrue(a.acknowledge(assertNotNull(successor), Any()))
            assertEquals(2, retired)
        } finally { observer.cancelAndJoin() }
    }

    @Test fun acknowledgementRevokedBySynchronousObserverCannotReturnReady() = runTest {
        val a = authority(); val owner = recording(); var lost = 0
        val ticket = a.request(owner) { lost++ }
        val observer = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            a.state.collect { value -> if (value.service != null) value.ticket?.let(a::stop) }
        }
        try {
            assertFalse(a.acknowledge(ticket, Any()))
            assertFalse(a.allows(owner)); assertEquals(1, lost)
        } finally { observer.cancelAndJoin() }
    }

    @Test fun acceptedOfferMayFinishAcrossReentrantRetirementButNoSuccessorOfferCanStart() {
        val a = authority(); val owner = recording(); val ticket = a.request(owner) { }
        assertTrue(a.acknowledge(ticket, Any()))
        assertTrue(a.admitCapture(ticket))
        var issued = 0
        assertEquals(HistoryOffer.ACCEPTED, a.offer(owner) {
            assertTrue(a.stop(ticket)); issued++; HistoryOffer.ACCEPTED
        })
        assertEquals(1, issued)
        assertEquals(HistoryOffer.RETIRED, a.offer(owner) { error("Retired GPS issued") })
    }

    @Test fun pendingAwaitHasFiniteDeadlineAndCannotAcceptLateService() = runTest {
        val a = authority(); var lost = 0
        val ticket = a.request(recording()) { lost++ }
        val waiting = backgroundScope.async { a.awaitReady(ticket, 100) }
        try {
            runCurrent(); assertFalse(waiting.isCompleted)
            advanceTimeBy(100); runCurrent()
            assertFalse(waiting.await()); assertEquals(1, lost)
            assertFalse(a.acknowledge(ticket, Any()))
        } finally { waiting.cancelAndJoin(); a.stop(ticket) }
    }

    @Test fun cancelledOldWaitCannotStopNewAcknowledgedOwner() = runTest {
        val a = authority(); val old = a.request(recording()) { }
        val waiting = backgroundScope.async { a.awaitReady(old) }
        try {
            runCurrent(); assertFalse(waiting.isCompleted)
            val fresh = a.request(recording("next")) { }; val service = Any()
            assertTrue(a.acknowledge(fresh, service))
            waiting.cancelAndJoin()
            assertTrue(a.allows(fresh.recording))
            assertTrue(a.awaitReady(fresh, 100))
        } finally { waiting.cancelAndJoin() }
    }
}
