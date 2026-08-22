package ru.sodovaya.volty.presentation.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RideMapFollowPolicyTest {
    @Test
    fun gesture_enters_free_mode_and_records_time() {
        val state = onRideMapCameraMoveStarted(
            state = RideMapFollowState(),
            origin = RideMapCameraMoveOrigin.GESTURE,
            nowMillis = 1_000L,
        )

        assertEquals(RideMapFollowMode.FREE, state.mode)
        assertEquals(1_000L, state.lastGestureAtMillis)
    }

    @Test
    fun programmatic_move_keeps_follow_mode() {
        val state = onRideMapCameraMoveStarted(
            state = RideMapFollowState(),
            origin = RideMapCameraMoveOrigin.PROGRAMMATIC,
            nowMillis = 1_000L,
        )

        assertEquals(RideMapFollowState(), state)
    }

    @Test
    fun manual_recenter_returns_to_following() {
        val free = RideMapFollowState(RideMapFollowMode.FREE, lastGestureAtMillis = 1_000L)

        assertEquals(RideMapFollowState(), recenterRideMap(free))
    }

    @Test
    fun moving_map_auto_returns_after_two_seconds() {
        val free = RideMapFollowState(RideMapFollowMode.FREE, lastGestureAtMillis = 1_000L)

        assertFalse(shouldAutoReturnToFollow(free, speedKmh = 20f, nowMillis = 2_999L))
        assertTrue(shouldAutoReturnToFollow(free, speedKmh = 20f, nowMillis = 3_000L))
    }

    @Test
    fun stopped_or_unknown_speed_does_not_auto_return() {
        val free = RideMapFollowState(RideMapFollowMode.FREE, lastGestureAtMillis = 1_000L)

        assertFalse(shouldAutoReturnToFollow(free, speedKmh = 0f, nowMillis = 5_000L))
        assertFalse(shouldAutoReturnToFollow(free, speedKmh = null, nowMillis = 5_000L))
    }

    @Test
    fun a_new_gesture_resets_the_auto_return_deadline() {
        val first = onRideMapCameraMoveStarted(
            state = RideMapFollowState(),
            origin = RideMapCameraMoveOrigin.GESTURE,
            nowMillis = 1_000L,
        )
        val second = onRideMapCameraMoveStarted(
            state = first,
            origin = RideMapCameraMoveOrigin.GESTURE,
            nowMillis = 2_500L,
        )

        assertFalse(shouldAutoReturnToFollow(second, speedKmh = 20f, nowMillis = 4_499L))
        assertTrue(shouldAutoReturnToFollow(second, speedKmh = 20f, nowMillis = 4_500L))
    }
}
