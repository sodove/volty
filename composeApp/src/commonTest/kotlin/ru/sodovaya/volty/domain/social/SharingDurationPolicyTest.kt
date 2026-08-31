package ru.sodovaya.volty.domain.social

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SharingDurationPolicyTest {
    @Test
    fun offers_durations_longer_than_one_hour_without_exceeding_server_limit() {
        assertTrue(SharingDurationPolicy.options.any { it.ttlMillis > HOUR_MILLIS })
        assertEquals(24L * HOUR_MILLIS, SharingDurationPolicy.maxTtlMillis)
        assertTrue(SharingDurationPolicy.options.all { it.ttlMillis in HOUR_MILLIS..SharingDurationPolicy.maxTtlMillis })
    }

    @Test
    fun options_are_sorted_and_have_stable_labels() {
        assertEquals(
            listOf(1L, 2L, 4L, 8L, 24L),
            SharingDurationPolicy.options.map { it.hours },
        )
        assertEquals("1 час", SharingDurationPolicy.options.first().label)
        assertEquals("24 часа", SharingDurationPolicy.options.last().label)
    }

    private companion object {
        const val HOUR_MILLIS = 60L * 60L * 1_000L
    }
}
