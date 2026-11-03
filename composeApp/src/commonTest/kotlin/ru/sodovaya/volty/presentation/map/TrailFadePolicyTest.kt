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

class TrailSegmentOpacityTest {
    @Test
    fun a_fresh_segment_is_fully_opaque() {
        assertEquals(1f, trailSegmentOpacity(0.0, 0L))
    }

    @Test
    fun a_visible_segment_never_drops_below_the_legibility_floor() {
        var distance = 0.0
        while (distance < TRAIL_FADE_DISTANCE_METERS) {
            var age = 0L
            while (age < TRAIL_MAX_AGE_MILLIS) {
                assertTrue(trailSegmentOpacity(distance, age) >= TRAIL_MIN_OPACITY, "d=$distance age=$age")
                age += 500L
            }
            distance += 5.0
        }
    }

    @Test
    fun the_trail_still_fades_older_and_farther_segments_toward_the_floor() {
        assertTrue(trailSegmentOpacity(90.0, 0L) < trailSegmentOpacity(10.0, 0L))
        assertTrue(trailSegmentOpacity(0.0, 25_000L) < trailSegmentOpacity(0.0, 5_000L))
        assertTrue(trailSegmentOpacity(179.0, 29_000L) < 0.6f)
    }

    @Test
    fun segments_outside_the_window_are_dropped_not_drawn_at_the_floor() {
        assertEquals(0f, trailSegmentOpacity(TRAIL_FADE_DISTANCE_METERS, 0L))
        assertEquals(0f, trailSegmentOpacity(0.0, TRAIL_MAX_AGE_MILLIS))
        assertEquals(0f, trailSegmentOpacity(Double.NaN, 0L))
    }

    @Test
    fun trail_is_wider_than_the_old_line_and_casing_is_wider_than_the_core() {
        assertTrue(TRAIL_LINE_WIDTH > 3.2f)
        assertTrue(TRAIL_CASING_EXTRA_WIDTH > 0f)
    }
}
