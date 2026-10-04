package ru.sodovaya.volty.domain.voice

import kotlin.test.*

class VoiceScopedControlsTest {
    private val old = VoiceOperationId("old")
    private val current = VoiceOperationId("current")
    private fun active() = VoiceSessionState(operationId = current, generation = VoiceGeneration(2),
        sessionId = VoiceSessionId("session"), wanted = true, phase = VoicePhase.CONNECTED)

    @Test fun staleControlsCannotChangeNewOperationOrIssueNativeEffects() {
        val initial = active()
        assertEquals(VoiceTransition(initial, emptyList()), VoiceSessionReducer.reduce(initial, VoiceSessionEvent.SetMuted(true, old)))
        assertEquals(VoiceTransition(initial, emptyList()), VoiceSessionReducer.reduce(initial, VoiceSessionEvent.SetDeafened(true, old)))
    }

    @Test fun retainedSessionAfterWithdrawalCannotEarnControlAdmission() {
        val initial = active().copy(wanted = false, phase = VoicePhase.STOPPING)
        assertEquals(VoiceTransition(initial, emptyList()), VoiceSessionReducer.reduce(initial, VoiceSessionEvent.SetMuted(true, current)))
        assertEquals(VoiceTransition(initial, emptyList()), VoiceSessionReducer.reduce(initial, VoiceSessionEvent.SetDeafened(true, current)))
    }

    @Test fun currentDeafenAndMutePreserveOriginalMuteIntent() {
        val muted = VoiceSessionReducer.reduce(active(), VoiceSessionEvent.SetMuted(true, current))
        assertTrue(muted.state.muted)
        assertEquals(listOf(VoiceSessionEffect.SetMuted(VoiceSessionTag(VoiceSessionId("session"), VoiceGeneration(2)), true)), muted.effects)
        val deafened = VoiceSessionReducer.reduce(muted.state, VoiceSessionEvent.SetDeafened(true, current))
        assertTrue(deafened.state.deafened); assertTrue(deafened.state.muted)
        val restored = VoiceSessionReducer.reduce(deafened.state, VoiceSessionEvent.SetDeafened(false, current))
        assertFalse(restored.state.deafened); assertTrue(restored.state.muted)
        assertEquals(listOf(VoiceSessionEffect.SetDeafened(VoiceSessionTag(VoiceSessionId("session"), VoiceGeneration(2)), false)), restored.effects)
    }
}
