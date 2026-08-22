package ru.sodovaya.volty.presentation.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrailFadePolicyTest {
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
