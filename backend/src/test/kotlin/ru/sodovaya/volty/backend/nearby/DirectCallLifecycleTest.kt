package ru.sodovaya.volty.backend.nearby

import kotlin.test.*

class DirectCallLifecycleTest {
    private val policy = DirectCallLifecyclePolicy()
    private fun ringing() = assertNotNull(policy.create(null, "call", "caller", "callee", "key", "caller-op", 100, true).call)

    @Test fun creationRequiresFriendshipAndRetryPreservesIdentityAndDeadline() {
        assertEquals(DirectCallRejection.NOT_FRIENDS, policy.create(null, "call", "caller", "callee", "key", "caller-op", 100, false).rejection)
        val original = ringing()
        assertEquals(45_100L, original.ringDeadlineMillis); assertEquals(0L, original.revision)
        assertEquals(original, policy.create(original, "different-generated-id", "caller", "callee", "key", "caller-op", 500, true).call)
        assertEquals(DirectCallRejection.IDENTITY_MISMATCH, policy.create(original, "call", "caller", "other", "key", "caller-op", 500, true).rejection)
        assertEquals(DirectCallRejection.IDENTITY_MISMATCH, policy.create(original, "call", "caller", "callee", "key", "new-op", 500, true).rejection)
        assertFailsWith<IllegalArgumentException> {policy.create(null, "call", "caller", "caller", "key", "op", 100, true)}
    }

    @Test fun stopBeforeAcceptWinsAndAcceptBeforeStopStillEndsConversation() {
        val original = ringing()
        val cancelled = assertNotNull(policy.stop(original, "caller", 101).call)
        assertEquals(DirectCallPhase.CANCELLED, cancelled.phase)
        assertEquals(DirectCallRejection.TERMINAL, policy.accept(cancelled, "callee", 0, 102).rejection)
        val accepted = assertNotNull(policy.accept(original, "callee", 0, 101).call)
        assertEquals(DirectCallPhase.ACCEPTED, accepted.phase)
        val ended = assertNotNull(policy.stop(accepted, "caller", 102).call)
        assertEquals(DirectCallPhase.ENDED, ended.phase); assertEquals(DirectCallOutcome.CALLER_ENDED, ended.outcome)
        assertEquals(2L, ended.revision)
    }

    @Test fun ringDeadlineIsInclusiveAndDoesNotExpireAcceptedCalls() {
        val original = ringing()
        val before = assertNotNull(policy.accept(original, "callee", 0, 45_099).call)
        assertEquals(DirectCallPhase.ACCEPTED, before.phase)
        assertEquals(before, policy.expire(before, 90_000).call)
        val deadline = policy.accept(original, "callee", 0, 45_100)
        assertEquals(DirectCallRejection.TERMINAL, deadline.rejection)
        assertEquals(DirectCallPhase.MISSED, assertNotNull(deadline.call).phase)
        assertEquals(DirectCallOutcome.RING_TIMEOUT, deadline.call?.outcome)
        assertEquals(1L, deadline.call?.revision)
    }

    @Test fun rolesAndRevisionAreCheckedBeforeMutatingAnotherCall() {
        val original = ringing()
        for (actor in listOf("caller", "outsider")) {
            val result = policy.accept(original, actor, 0, 90_000)
            assertEquals(DirectCallRejection.ACCESS_DENIED, result.rejection); assertEquals(original, result.call)
        }
        assertEquals(DirectCallRejection.ACCESS_DENIED, policy.stop(original, "outsider", 90_000).rejection)
        val stale = policy.accept(original.copy(revision = 1), "callee", 0, 101)
        assertEquals(DirectCallRejection.REVISION_MISMATCH, stale.rejection)
        assertEquals(DirectCallPhase.RINGING, stale.call?.phase)
        val declined = assertNotNull(policy.decline(original, "callee", 101).call)
        assertEquals(DirectCallPhase.DECLINED, declined.phase)
        assertEquals(DirectCallOutcome.CALLEE_DECLINED, declined.outcome)
    }

    @Test fun terminalOutcomesAndAcceptedRetriesAreImmutable() {
        val original = ringing()
        val accepted = assertNotNull(policy.accept(original, "callee", 0, 101).call)
        assertEquals(accepted, policy.accept(accepted, "callee", 0, 102).call)
        assertEquals(DirectCallRejection.NOT_RINGING, policy.decline(accepted, "callee", 102).rejection)
        for (terminal in listOf(
            assertNotNull(policy.stop(original, "caller", 101).call),
            assertNotNull(policy.decline(original, "callee", 101).call),
            assertNotNull(policy.expire(original, 45_100).call),
            assertNotNull(policy.stop(accepted, "callee", 102).call),
        )) {
            assertEquals(terminal, policy.stop(terminal, "caller", 90_000).call)
            assertEquals(terminal, policy.expire(terminal, 90_000).call)
            assertEquals(terminal, policy.revokeFriendship(terminal, 90_000).call)
            assertEquals(DirectCallRejection.TERMINAL, policy.accept(terminal, "callee", terminal.revision, 90_000).rejection)
        }
    }

    @Test fun authorizedStaleAcceptStillPersistsElapsedRingDeadline() {
        val original = ringing().copy(revision = 1)
        val result = policy.accept(original, "callee", 0, 45_100)
        assertEquals(DirectCallRejection.TERMINAL, result.rejection)
        assertEquals(DirectCallPhase.MISSED, result.call?.phase)
        assertEquals(DirectCallOutcome.RING_TIMEOUT, result.call?.outcome)
        assertEquals(2L, result.call?.revision)
    }

    @Test fun friendshipRevocationCancelsRingingAndEndsAccepted() {
        val original = ringing()
        val cancelled = assertNotNull(policy.revokeFriendship(original, 101).call)
        assertEquals(DirectCallPhase.CANCELLED, cancelled.phase); assertEquals(DirectCallOutcome.FRIENDSHIP_REVOKED, cancelled.outcome)
        val accepted = assertNotNull(policy.accept(original, "callee", 0, 101).call)
        val ended = assertNotNull(policy.revokeFriendship(accepted, 102).call)
        assertEquals(DirectCallPhase.ENDED, ended.phase); assertEquals(DirectCallOutcome.FRIENDSHIP_REVOKED, ended.outcome)
    }
}
