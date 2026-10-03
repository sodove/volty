package ru.sodovaya.volty.backend.nearby

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SessionOperationTest {
    private val policy = SessionOperationPolicy()
    private fun pending() = policy.begin(null, "actor", "op", "channel", "session", 100).operation!!

    @Test fun cancelBeforeJoinCreatesPermanentTombstone() {
        val cancelled = policy.cancel(null, "actor", "op", 100).operation!!
        val late = policy.begin(cancelled, "actor", "op", "channel", "session", 101)
        assertEquals(OperationPhase.CANCELLED, late.operation!!.phase)
        assertEquals(OperationRejection.TERMINAL, late.rejection)
        assertNull(late.operation!!.sessionId)
    }

    @Test fun operationCannotBeReplayedByAnotherActor() {
        val original = pending()
        val result = policy.cancel(original, "stranger", "op", 101)
        assertEquals(OperationRejection.IDENTITY_MISMATCH, result.rejection)
        assertEquals(original, result.operation)
    }

    @Test fun operationIdMismatchCannotMutateExistingState() {
        val original = pending()
        val result = policy.cancel(original, "actor", "other-op", 101)
        assertEquals(OperationRejection.IDENTITY_MISMATCH, result.rejection)
        assertEquals(original, result.operation)
    }

    @Test fun destinationMismatchRejectsReplay() {
        val original = pending()
        val result = policy.begin(original, "actor", "op", "other-channel", "new-session", 101)
        assertEquals(OperationRejection.DESTINATION_MISMATCH, result.rejection)
        assertEquals(original, result.operation)
    }

    @Test fun duplicateBeginKeepsSessionAndPendingDeadline() {
        val original = pending()
        assertEquals(original, policy.begin(original, "actor", "op", "channel", "new-session", 500).operation)
    }

    @Test fun acknowledgementAtPendingDeadlineCannotActivate() {
        val original = pending()
        val result = policy.admit(original, "session", original.deadlineEpochMillis!!, 60_000)
        assertEquals(OperationPhase.EXPIRED, result.operation!!.phase)
        assertEquals(OperationRejection.TERMINAL, result.rejection)
    }

    @Test fun lateAdmissionAfterCancelStaysTerminalAndRetainsCleanupIdentity() {
        val cancelled = policy.cancel(pending(), "actor", "op", 101).operation!!
        val result = policy.admit(cancelled, "session", 102, 60_000)
        assertEquals(cancelled, result.operation)
        assertEquals("session", result.operation!!.sessionId)
        assertEquals(OperationRejection.TERMINAL, result.rejection)
    }

    @Test fun oldSessionAcknowledgementCannotActivateReplacement() {
        val original = pending()
        val result = policy.admit(original, "older-session", 101, 60_000)
        assertEquals(OperationRejection.SESSION_MISMATCH, result.rejection)
        assertEquals(original, result.operation)
    }

    @Test fun duplicateAdmissionCannotExtendActiveDeadline() {
        val active = policy.admit(pending(), "session", 101, 60_000).operation!!
        assertEquals(OperationPhase.ACTIVE, active.phase)
        assertEquals(active, policy.admit(active, "session", 201, 60_000).operation)
    }

    @Test fun heartbeatAtExactDeadlineCannotResurrectActiveSession() {
        val active = policy.admit(pending(), "session", 101, 60_000).operation!!
        val result = policy.heartbeat(active, "session", active.deadlineEpochMillis!!, 60_000)
        assertEquals(OperationPhase.EXPIRED, result.operation!!.phase)
        assertEquals(OperationRejection.TERMINAL, result.rejection)
    }

    @Test fun heartbeatOnlyExtendsMatchingActiveSession() {
        val original = pending()
        assertEquals(OperationRejection.NOT_ACTIVE, policy.heartbeat(original, "session", 101, 60_000).rejection)
        val active = policy.admit(original, "session", 101, 60_000).operation!!
        assertEquals(OperationRejection.SESSION_MISMATCH, policy.heartbeat(active, "old-session", 102, 60_000).rejection)
        val renewed = policy.heartbeat(active, "session", 201, 60_000).operation!!
        assertEquals(30_201, renewed.deadlineEpochMillis)
        assertEquals(active.sessionId, renewed.sessionId)
    }

    @Test fun expiryAndRepeatedStopAreIdempotent() {
        val original = pending()
        assertEquals(original, policy.expire(original, 101).operation)
        val expired = policy.expire(original, original.deadlineEpochMillis!!).operation!!
        assertEquals(OperationPhase.EXPIRED, expired.phase)
        assertEquals(expired, policy.cancel(expired, "actor", "op", 40_000).operation)
        assertEquals(expired, policy.expire(expired, 50_000).operation)
    }

    @Test fun delayedAdmissionCannotOutliveConfirmedSfuLease() {
        val result = policy.admit(pending(), "session", 500, confirmedLeaseDeadlineEpochMillis = 10_000)
        assertEquals(10_000, result.operation!!.deadlineEpochMillis)
    }

    @Test fun delayedHeartbeatCannotOutliveConfirmedSfuLease() {
        val active = policy.admit(pending(), "session", 101, 60_000).operation!!
        val result = policy.heartbeat(active, "session", 500, confirmedLeaseDeadlineEpochMillis = 10_000)
        assertEquals(10_000, result.operation!!.deadlineEpochMillis)
    }

    @Test fun alreadyExpiredSfuAcknowledgementCannotActivate() {
        val result = policy.admit(pending(), "session", 500, confirmedLeaseDeadlineEpochMillis = 500)
        assertEquals(OperationPhase.EXPIRED, result.operation!!.phase)
        assertEquals(OperationRejection.TERMINAL, result.rejection)
    }
}
