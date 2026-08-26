package ru.sodovaya.volty.presentation.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrailFadePolicyTest {
    @Test
    fun trail_source_updates_are_throttled_to_a_stable_render_cadence() {
        assertTrue(shouldRenderTrail(nowMillis = 1_000L, lastRenderMillis = null))
        assertTrue(!shouldRenderTrail(nowMillis = 1_050L, lastRenderMillis = 1_000L))
        assertTrue(shouldRenderTrail(nowMillis = 1_100L, lastRenderMillis = 1_000L))
    }

    @Test
    fun trail_opacity_fades_during_the_last_ten_seconds_of_its_lifetime() {
        assertEquals(1f, trailOpacityForAgeMillis(0L))
        assertEquals(1f, trailOpacityForAgeMillis(TRAIL_FADE_START_AGE_MILLIS))

        val fading = trailOpacityForAgeMillis(
            TRAIL_FADE_START_AGE_MILLIS + TRAIL_FADE_DURATION_MILLIS / 2L,
        )
        assertTrue(fading in 0f..1f)
        assertTrue(fading < 1f)
        assertTrue(fading > 0f)
        assertEquals(0f, trailOpacityForAgeMillis(TRAIL_MAX_AGE_MILLIS))
    }

    @Test
    fun a_trail_point_is_removed_after_thirty_seconds_even_without_new_gps() {
        assertTrue(shouldRetainTrailPoint(0L))
        assertTrue(shouldRetainTrailPoint(TRAIL_MAX_AGE_MILLIS - 1L))
        assertTrue(!shouldRetainTrailPoint(TRAIL_MAX_AGE_MILLIS))
        assertTrue(!shouldRetainTrailPoint(TRAIL_MAX_AGE_MILLIS + 1L))
    }

    @Test
    fun stationary_trail_fade_is_repeatable_at_the_same_render_time() {
        val ageMillis = TRAIL_FADE_START_AGE_MILLIS + TRAIL_FADE_DURATION_MILLIS / 2L

        val first = trailOpacityForAgeMillis(ageMillis)
        val second = trailOpacityForAgeMillis(ageMillis)

        assertEquals(first, second)
        assertTrue(first in 0f..1f)
    }

    @Test
    fun trail_opacity_decreases_with_distance_behind_the_rider() {
        val fresh = trailOpacityForDistanceMeters(0.0)
        val middle = trailOpacityForDistanceMeters(TRAIL_FADE_DISTANCE_METERS / 2.0)
        val old = trailOpacityForDistanceMeters(TRAIL_FADE_DISTANCE_METERS)

        assertTrue(fresh > middle)
        assertTrue(middle > old)
        assertEquals(0f, old)
        assertEquals(0f, trailOpacityForDistanceMeters(TRAIL_FADE_DISTANCE_METERS * 2.0))
    }
}
