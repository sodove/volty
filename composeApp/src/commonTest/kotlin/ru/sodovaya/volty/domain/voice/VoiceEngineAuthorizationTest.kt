package ru.sodovaya.volty.domain.voice

import kotlin.test.*

class VoiceEngineAuthorizationTest {
    private val tag = VoiceSessionTag(VoiceSessionId("session"), VoiceGeneration(1))
    private val earned = VoiceSessionState(generation = tag.generation, sessionId = tag.sessionId, wanted = true,
        phase = VoicePhase.CONNECTING, leaseDeadlineMillis = 100, lastLeaseRequestId = VoiceLeaseRequestId(0))
    @Test fun inclusiveExpiryAndNegativeClockDeny() {
        assertTrue(earned.authorizesEngine(tag, 99))
        assertFalse(earned.authorizesEngine(tag, 100)); assertFalse(earned.authorizesEngine(tag, -1))
    }
    @Test fun unknownOrIncoherentCreditCannotAdmit() {
        assertFalse(earned.copy(leaseDeadlineMillis = null).authorizesEngine(tag, 0))
        assertFalse(earned.copy(lastLeaseRequestId = null).authorizesEngine(tag, 0))
    }
    @Test fun stoppedOrForeignSessionCannotAdmitDespiteEarnedCredit() {
        assertFalse(earned.copy(wanted = false).authorizesEngine(tag, 0))
        assertFalse(earned.copy(sessionId = VoiceSessionId("other")).authorizesEngine(tag, 0))
        assertFalse(earned.copy(generation = VoiceGeneration(2)).authorizesEngine(tag, 0))
    }
    @Test fun OnlyLiveIntentPhasesAdmit() {
        for (phase in VoicePhase.entries) assertEquals(phase in setOf(VoicePhase.CONNECTING, VoicePhase.CONNECTED, VoicePhase.RECONNECTING),
            earned.copy(phase = phase).authorizesEngine(tag, 0), phase.name)
    }
}
