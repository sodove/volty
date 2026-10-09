package ru.sodovaya.volty.domain.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VoiceSessionLifecycleTest {
    @Test fun server_closed_engine_releases_media_without_a_failure_and_keeps_cleanup_pending() {
        val active = VoiceSessionReducer.reduce(join().state, grant(join().state)).state
        val ended = VoiceSessionReducer.reduce(active, VoiceSessionEvent.EngineClosed(VoiceSessionTag(session, active.generation)))
        assertEquals(VoicePhase.STOPPING, ended.state.phase)
        assertFalse(ended.state.wanted)
        assertTrue(ended.state.cleanupPending)
        assertTrue(ended.effects.contains(VoiceSessionEffect.StopEngine(active.generation)))
        assertTrue(ended.effects.contains(VoiceSessionEffect.StopSession(session)))
        assertTrue(ended.effects.contains(VoiceSessionEffect.RemoveNearbyDemand))
        assertTrue(ended.effects.contains(VoiceSessionEffect.ClearSharingQueue))
        val released = VoiceSessionReducer.reduce(ended.state, VoiceSessionEvent.Stopped(active.generation))
        assertEquals(VoicePhase.IDLE, released.state.phase)
    }

    @Test fun an_old_room_closure_cannot_stop_a_new_session() {
        val active = VoiceSessionReducer.reduce(join().state, grant(join().state)).state
        val stale = VoiceSessionReducer.reduce(active, VoiceSessionEvent.EngineClosed(VoiceSessionTag(VoiceSessionId("old-session"), active.generation)))
        assertEquals(active, stale.state)
        assertTrue(stale.effects.isEmpty())
    }
    private val op = VoiceOperationId("op1")
    private val session = VoiceSessionId("s1")

    @Test
    fun lateGrantAfterStopCannotStartEngine() {
        val joining = join()
        assertEquals(listOf(VoiceSessionEffect.RequestJoin(op, joining.state.generation)), joining.effects)
        val stopped = VoiceSessionReducer.reduce(joining.state, VoiceSessionEvent.Stop)
        assertTrue(stopped.effects.contains(VoiceSessionEffect.CancelJoin(op, joining.state.generation)))
        val late = VoiceSessionReducer.reduce(stopped.state, grant(joining.state))
        assertFalse(late.state.wanted)
        assertEquals(listOf(VoiceSessionEffect.StopSession(session)), late.effects)
    }

    @Test
    fun oldStopCompletionCannotCloseNewJoin() {
        val one = join()
        val stopping = VoiceSessionReducer.reduce(one.state, VoiceSessionEvent.Stop)
        val two = VoiceSessionReducer.reduce(stopping.state, VoiceSessionEvent.Join(VoiceOperationId("two")))
        assertTrue(two.state.generation.value > one.state.generation.value)
        assertTrue(two.state.wanted)
        assertEquals(VoicePhase.JOINING, two.state.phase)
        assertEquals(listOf(VoiceSessionEffect.RequestJoin(VoiceOperationId("two"), two.state.generation)), two.effects)
        val old = VoiceSessionReducer.reduce(two.state, VoiceSessionEvent.Stopped(one.state.generation))
        assertEquals(two.state, old.state)
        assertTrue(old.effects.isEmpty())
    }

    @Test
    fun activeGrantStartsExactlyOneTaggedEngine() {
        val joining = join().state
        val granted = VoiceSessionReducer.reduce(joining, grant(joining))
        assertEquals(VoicePhase.CONNECTING, granted.state.phase)
        assertEquals(session, granted.state.sessionId)
        assertEquals(listOf(VoiceSessionEffect.StartEngine(VoiceSessionTag(session, joining.generation))), granted.effects)
        val duplicate = VoiceSessionReducer.reduce(granted.state, grant(joining))
        assertEquals(granted.state, duplicate.state)
        assertTrue(duplicate.effects.isEmpty())
    }

    @Test
    fun duplicateStopDoesNotIssueCleanupAgain() {
        val joining = join().state
        val active = VoiceSessionReducer.reduce(joining, grant(joining)).state
        val stopped = VoiceSessionReducer.reduce(active, VoiceSessionEvent.Stop)
        assertEquals(listOf(
            VoiceSessionEffect.StopEngine(active.generation),
            VoiceSessionEffect.StopSession(session),
            VoiceSessionEffect.RemoveNearbyDemand,
            VoiceSessionEffect.ClearSharingQueue,
        ), stopped.effects)
        assertFalse(stopped.state.wanted)
        assertTrue(stopped.state.cleanupPending)
        val duplicate = VoiceSessionReducer.reduce(stopped.state, VoiceSessionEvent.Stop)
        assertEquals(stopped.state, duplicate.state)
        assertTrue(duplicate.effects.isEmpty())
    }

    @Test
    fun failedJoinCancelsPendingOperation() {
        val joining = join().state
        val failed = VoiceSessionReducer.reduce(joining, VoiceSessionEvent.Failed(joining.generation))
        assertFalse(failed.state.wanted)
        assertEquals(VoicePhase.FAILED, failed.state.phase)
        assertTrue(failed.effects.contains(VoiceSessionEffect.CancelJoin(op, joining.generation)))
        assertTrue(failed.effects.contains(VoiceSessionEffect.StopEngine(joining.generation)))
    }

    @Test
    fun newJoinCleansOldSessionBeforeRequestingNewOne() {
        val joining = join().state
        val active = VoiceSessionReducer.reduce(joining, grant(joining)).state
        val nextOp = VoiceOperationId("two")
        val next = VoiceSessionReducer.reduce(active, VoiceSessionEvent.Join(nextOp))
        assertEquals(listOf(
            VoiceSessionEffect.StopEngine(active.generation),
            VoiceSessionEffect.StopSession(session),
            VoiceSessionEffect.RemoveNearbyDemand,
            VoiceSessionEffect.ClearSharingQueue,
            VoiceSessionEffect.RequestJoin(nextOp, next.state.generation),
        ), next.effects)
        assertEquals(nextOp, next.state.operationId)
        assertEquals(VoicePhase.JOINING, next.state.phase)
        assertEquals(null, next.state.sessionId)
    }

    @Test
    fun oldGrantWhileAnotherJoinIsPendingOnlyClosesOldSession() {
        val one = join().state
        val two = VoiceSessionReducer.reduce(one, VoiceSessionEvent.Join(VoiceOperationId("two"))).state
        val late = VoiceSessionReducer.reduce(two, grant(one))
        assertEquals(two, late.state)
        assertEquals(listOf(VoiceSessionEffect.StopSession(session)), late.effects)
    }

    @Test
    fun failureFromOldGenerationCannotEndNewJoin() {
        val one = join().state
        val stopped = VoiceSessionReducer.reduce(one, VoiceSessionEvent.Stop)
        assertTrue(stopped.effects.contains(VoiceSessionEffect.CancelJoin(op, one.generation)))
        val two = VoiceSessionReducer.reduce(stopped.state, VoiceSessionEvent.Join(VoiceOperationId("two")))
        assertTrue(two.state.wanted)
        assertEquals(VoicePhase.JOINING, two.state.phase)
        assertTrue(two.state.generation.value > one.generation.value)
        assertEquals(listOf(VoiceSessionEffect.RequestJoin(VoiceOperationId("two"), two.state.generation)), two.effects)
        val late = VoiceSessionReducer.reduce(two.state, VoiceSessionEvent.Failed(one.generation))
        assertEquals(two.state, late.state)
        assertTrue(late.effects.isEmpty())
    }

    @Test
    fun stopAcknowledgementPreservesFailedPhase() {
        val joining = join().state
        val failed = VoiceSessionReducer.reduce(joining, VoiceSessionEvent.Failed(joining.generation)).state
        val released = VoiceSessionReducer.reduce(failed, VoiceSessionEvent.Stopped(joining.generation))
        assertFalse(released.state.cleanupPending)
        assertEquals(VoicePhase.FAILED, released.state.phase)
        assertTrue(released.effects.isEmpty())
    }

    @Test
    fun duplicateJoinCannotStartAnotherGeneration() {
        val joining = join().state
        val duplicate = VoiceSessionReducer.reduce(joining, VoiceSessionEvent.Join(op))
        assertEquals(joining, duplicate.state)
        assertTrue(duplicate.effects.isEmpty())
    }

    @Test
    fun cancelledOperationCannotBeReusedToRestoreIntent() {
        val stopped = VoiceSessionReducer.reduce(join().state, VoiceSessionEvent.Stop).state
        val duplicate = VoiceSessionReducer.reduce(stopped, VoiceSessionEvent.Join(op))
        assertEquals(stopped, duplicate.state)
        assertTrue(duplicate.effects.isEmpty())
    }

    private fun join(): VoiceTransition {
        val result = VoiceSessionReducer.reduce(VoiceSessionState(), VoiceSessionEvent.Join(op))
        assertTrue(result.state.wanted)
        assertEquals(VoicePhase.JOINING, result.state.phase)
        assertEquals(listOf(VoiceSessionEffect.RequestJoin(op, result.state.generation)), result.effects)
        return result
    }
    private fun grant(state: VoiceSessionState) = VoiceSessionEvent.SessionGranted(op, state.generation, session, VoiceLeaseGrant(VoiceLeaseRequestId(0), 0, 0, 30_000))
}
