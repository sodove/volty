package ru.sodovaya.volty.domain.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class VoiceLeaseGrantTest {
    private val tag = VoiceSessionTag(VoiceSessionId("s"), VoiceGeneration(1))
    private val op = VoiceOperationId("op")
    private fun receipt(id: Long, start: Long, received: Long, remaining: Long) =
        VoiceLeaseGrant(VoiceLeaseRequestId(id), start, received, remaining)
    private fun active() = VoiceSessionState(generation = tag.generation, operationId = op,
        sessionId = tag.sessionId, wanted = true, leaseDeadlineMillis = 30_000,
        lastLeaseRequestId = VoiceLeaseRequestId(0))

    @Test fun responseDelayIsSubtractedFromInitialGrant() {
        val joining = VoiceSessionReducer.reduce(VoiceSessionState(), VoiceSessionEvent.Join(op)).state
        val result = VoiceSessionReducer.reduce(joining, VoiceSessionEvent.SessionGranted(op,
            joining.generation, tag.sessionId, receipt(0, 1_000, 8_000, 10_000)))
        assertEquals(11_000, result.state.leaseDeadlineMillis)
        assertTrue(result.effects.single() is VoiceSessionEffect.StartEngine)
        assertFalse(VoiceSessionReducer.reduce(result.state, VoiceSessionEvent.Tick(11_000)).state.wanted)
    }

    @Test fun expiredInitialGrantNeverStartsEngineAndReleasesSession() {
        val joining = VoiceSessionReducer.reduce(VoiceSessionState(), VoiceSessionEvent.Join(op)).state
        val result = VoiceSessionReducer.reduce(joining, VoiceSessionEvent.SessionGranted(op,
            joining.generation, tag.sessionId, receipt(0, 1_000, 11_000, 10_000)))
        assertFalse(result.state.wanted)
        assertTrue(result.effects.none { it is VoiceSessionEffect.StartEngine })
        assertTrue(VoiceSessionEffect.StopSession(tag.sessionId) in result.effects)
    }

    @Test fun equalStartTimesUseRequestOrderAndReplayCannotExtend() {
        val renewed = VoiceSessionReducer.reduce(active(), VoiceSessionEvent.LeaseAck(tag,
            receipt(2, 10_000, 11_000, 15_000))).state
        assertEquals(25_000, renewed.leaseDeadlineMillis)
        for (id in listOf(1L, 2L)) {
            val replay = VoiceSessionReducer.reduce(renewed, VoiceSessionEvent.LeaseAck(tag,
                receipt(id, 10_000, 12_000, 30_000)))
            assertEquals(VoiceTransition(renewed, emptyList()), replay)
        }
    }

    @Test fun renewalCannotReviveExpiredPriorLeaseWithoutTick() {
        val result = VoiceSessionReducer.reduce(active(), VoiceSessionEvent.LeaseAck(tag,
            receipt(1, 29_000, 30_000, 30_000)))
        assertFalse(result.state.wanted)
        assertTrue(VoiceSessionEffect.StopSession(tag.sessionId) in result.effects)
    }

    @Test fun freshButAlreadyExpiredReceiptStopsImmediately() {
        val result = VoiceSessionReducer.reduce(active(), VoiceSessionEvent.LeaseAck(tag,
            receipt(1, 10_000, 15_000, 5_000)))
        assertFalse(result.state.wanted)
        assertTrue(result.effects.any { it is VoiceSessionEffect.StopEngine })
    }

    @Test fun malformedOrOverflowingGrantsAreRejected() {
        listOf(-1L, 0L, 30_001L).forEach { remaining ->
            assertFailsWith<IllegalArgumentException> { receipt(0, 0, 0, remaining) }
        }
        assertFailsWith<IllegalArgumentException> { receipt(-1, 0, 0, 1) }
        assertFailsWith<IllegalArgumentException> { receipt(0, -1, 0, 1) }
        assertFailsWith<IllegalArgumentException> { receipt(0, 5, 4, 1) }
        assertFailsWith<IllegalArgumentException> { receipt(0, Long.MAX_VALUE, Long.MAX_VALUE, 1) }
    }
}
