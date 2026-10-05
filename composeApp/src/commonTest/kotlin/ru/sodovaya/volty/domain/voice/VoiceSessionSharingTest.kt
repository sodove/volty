package ru.sodovaya.volty.domain.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VoiceSessionSharingTest {
    @Test fun originalSharingCommandCannotEnableNewerConversation() {
        val original = VoiceOperationId("old-operation"); val current = VoiceOperationId("current-operation")
        val state = active().copy(operationId = current)
        assertEquals(VoiceTransition(state, emptyList()), reduce(state, VoiceSessionEvent.EnableSharing(original)))
        val accepted = reduce(state, VoiceSessionEvent.EnableSharing(current))
        assertTrue(accepted.state.sharingEnabled)
        assertEquals(listOf(VoiceSessionEffect.AddNearbyDemand), accepted.effects)
    }
    private val tag = VoiceSessionTag(VoiceSessionId("s1"), VoiceGeneration(1))
    private fun active() = VoiceSessionState(
        generation = tag.generation, sessionId = tag.sessionId, wanted = true,
        phase = VoicePhase.CONNECTED, leaseDeadlineMillis = 30_000, lastLeaseRequestId = VoiceLeaseRequestId(0),
    )

    @Test
    fun hidePositionKeepsVoiceAndRemovesOnlyNearbyDemand() {
        val initial = active().copy(sharingEnabled = true, captureStarted = true, published = true)
        val hidden = reduce(initial, VoiceSessionEvent.HidePosition)
        assertEquals(initial.copy(sharingEnabled = false), hidden.state)
        assertEquals(listOf(VoiceSessionEffect.RemoveNearbyDemand, VoiceSessionEffect.ClearSharingQueue), hidden.effects)
    }

    @Test
    fun hideTwiceIsIdempotent() {
        val hidden = reduce(active().copy(sharingEnabled = true), VoiceSessionEvent.HidePosition).state
        assertFalse(hidden.sharingEnabled)
        assertEquals(VoiceTransition(hidden, emptyList()), reduce(hidden, VoiceSessionEvent.HidePosition))
    }

    @Test
    fun enableSharingNeedsActiveGrantedSession() {
        val states = listOf(
            VoiceSessionState(), VoiceSessionState(wanted = true, phase = VoicePhase.JOINING),
            active().copy(wanted = false, phase = VoicePhase.STOPPING),
        )
        states.forEach { state -> assertEquals(VoiceTransition(state, emptyList()), reduce(state, VoiceSessionEvent.EnableSharing())) }
    }

    @Test
    fun activeEnableIssuesOnlyNearbyDemandAndIsIdempotent() {
        val enabled = reduce(active(), VoiceSessionEvent.EnableSharing())
        assertTrue(enabled.state.sharingEnabled)
        assertEquals(listOf(VoiceSessionEffect.AddNearbyDemand), enabled.effects)
        assertEquals(VoiceTransition(enabled.state, emptyList()), reduce(enabled.state, VoiceSessionEvent.EnableSharing()))
    }

    @Test
    fun fullDisconnectClearsSharingAndIssuesSessionCleanup() {
        val result = reduce(active().copy(sharingEnabled = true), VoiceSessionEvent.Stop)
        assertFalse(result.state.wanted)
        assertFalse(result.state.sharingEnabled)
        assertEquals(listOf(VoiceSessionEffect.StopEngine(tag.generation), VoiceSessionEffect.StopSession(tag.sessionId),
            VoiceSessionEffect.RemoveNearbyDemand, VoiceSessionEffect.ClearSharingQueue), result.effects)
    }

    @Test
    fun replacingSessionDoesNotTransferLocationConsent() {
        val result = reduce(active().copy(sharingEnabled = true), VoiceSessionEvent.Join(VoiceOperationId("new")))
        assertFalse(result.state.sharingEnabled)
        assertTrue(result.effects.indexOf(VoiceSessionEffect.RemoveNearbyDemand) >= 0)
        assertEquals(VoiceSessionEffect.RequestJoin(VoiceOperationId("new"), result.state.generation), result.effects.last())
    }

    @Test
    fun stopClearsStraySharingEvenWithoutVoiceIntent() {
        // Deliberately incoherent: cleanup must not assume all producers keep these flags aligned.
        val initial = VoiceSessionState(wanted = false, sharingEnabled = true)
        val stopped = reduce(initial, VoiceSessionEvent.Stop)
        assertFalse(stopped.state.sharingEnabled)
        assertEquals(listOf(VoiceSessionEffect.RemoveNearbyDemand, VoiceSessionEffect.ClearSharingQueue), stopped.effects)
    }

    @Test
    fun restartDefaultsNeverResumeCaptureOrSharing() {
        val restored = VoiceSessionState()
        assertFalse(restored.wanted)
        assertFalse(restored.sharingEnabled)
        assertFalse(restored.captureStarted)
        assertEquals(VoiceTransition(restored, emptyList()), reduce(restored, VoiceSessionEvent.EnableSharing()))
    }

    @Test
    fun newJoinCleansStraySharingBeforeRequestingSession() {
        val result = reduce(VoiceSessionState(sharingEnabled = true), VoiceSessionEvent.Join(VoiceOperationId("new")))
        assertFalse(result.state.sharingEnabled)
        assertEquals(listOf(VoiceSessionEffect.RemoveNearbyDemand, VoiceSessionEffect.ClearSharingQueue,
            VoiceSessionEffect.RequestJoin(VoiceOperationId("new"), result.state.generation)), result.effects)
    }

    @Test
    fun leaseExpiryAlsoClearsSharing() {
        val result = reduce(active().copy(sharingEnabled = true), VoiceSessionEvent.Tick(30_001))
        assertFalse(result.state.wanted)
        assertFalse(result.state.sharingEnabled)
        assertTrue(result.effects.contains(VoiceSessionEffect.ClearSharingQueue))
        assertTrue(result.effects.contains(VoiceSessionEffect.RemoveNearbyDemand))
    }

    private fun reduce(state: VoiceSessionState, event: VoiceSessionEvent) = VoiceSessionReducer.reduce(state, event)
}
