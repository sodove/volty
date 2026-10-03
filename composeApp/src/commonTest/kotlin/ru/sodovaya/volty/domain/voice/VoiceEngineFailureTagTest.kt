package ru.sodovaya.volty.domain.voice

import kotlin.test.*

class VoiceEngineFailureTagTest {
    private val tag = VoiceSessionTag(VoiceSessionId("current"), VoiceGeneration(2))
    private val current = VoiceSessionState(generation = tag.generation, sessionId = tag.sessionId, wanted = true,
        phase = VoicePhase.CONNECTED, leaseDeadlineMillis = 100, lastLeaseRequestId = VoiceLeaseRequestId(0), published = true)
    @Test fun foreignEngineFailureInSameGenerationCannotStopCurrent() {
        val transition = VoiceSessionReducer.reduce(current, VoiceSessionEvent.EngineFailed(tag.copy(sessionId = VoiceSessionId("foreign"))))
        assertEquals(current, transition.state); assertTrue(transition.effects.isEmpty())
    }
    @Test fun currentEngineFailureStopsExactSessionAndClearsAuthority() {
        val transition = VoiceSessionReducer.reduce(current, VoiceSessionEvent.EngineFailed(tag))
        assertFalse(transition.state.wanted); assertNull(transition.state.leaseDeadlineMillis)
        assertEquals(VoicePhase.FAILED, transition.state.phase)
        assertTrue(VoiceSessionEffect.StopSession(tag.sessionId) in transition.effects)
        assertTrue(VoiceSessionEffect.StopEngine(tag.generation) in transition.effects)
    }
}
