package ru.sodovaya.volty.domain.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VoiceRejoinPolicyTest {
    private val policy = VoiceRejoinPolicy()

    @Test fun delaysGrowThenRepeatTheLast() {
        val seen = (1..8).map { policy.nextDelay(it, 0, 1_000) }
        assertEquals(listOf<Long?>(2_000, 4_000, 8_000, 15_000, 30_000, 30_000, 30_000, 30_000), seen)
    }

    @Test fun rejoinStopsAfterTheBudget() {
        assertEquals(30_000L, policy.nextDelay(9, 1_000, 1_000 + 10 * 60_000 - 1))
        assertNull(policy.nextDelay(9, 1_000, 1_000 + 10 * 60_000))
        assertNull(policy.nextDelay(1, 0, Long.MAX_VALUE / 2))
    }
}
