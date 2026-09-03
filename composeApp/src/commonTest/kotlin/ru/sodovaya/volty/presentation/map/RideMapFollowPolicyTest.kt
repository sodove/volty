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
    fun moving_map_auto_returns_after_five_seconds() {
        val free = RideMapFollowState(RideMapFollowMode.FREE, lastGestureAtMillis = 1_000L)

        assertFalse(shouldAutoReturnToFollow(free, speedKmh = 20f, nowMillis = 5_999L))
        assertTrue(shouldAutoReturnToFollow(free, speedKmh = 20f, nowMillis = 6_000L))
    }

    @Test
    fun stopped_or_unknown_speed_does_not_auto_return() {
        val free = RideMapFollowState(RideMapFollowMode.FREE, lastGestureAtMillis = 1_000L)

        assertFalse(shouldAutoReturnToFollow(free, speedKmh = 0f, nowMillis = 5_000L))
        assertFalse(shouldAutoReturnToFollow(free, speedKmh = null, nowMillis = 5_000L))
    }

    @Test
    fun a_slow_roll_does_not_auto_return_until_movement_reaches_threshold() {
        val free = RideMapFollowState(RideMapFollowMode.FREE, lastGestureAtMillis = 1_000L)

        assertFalse(shouldAutoReturnToFollow(free, speedKmh = 1.9f, nowMillis = 6_000L))
        assertTrue(shouldAutoReturnToFollow(free, speedKmh = 2f, nowMillis = 6_000L))
    }

    @Test
    fun programmatic_camera_motion_does_not_restart_manual_camera_grace_period() {
        val free = RideMapFollowState(RideMapFollowMode.FREE, lastGestureAtMillis = 1_000L)
        val afterProgrammaticMove = onRideMapCameraMoveStarted(
            state = free,
            origin = RideMapCameraMoveOrigin.PROGRAMMATIC,
            nowMillis = 2_500L,
        )

        assertFalse(shouldAutoReturnToFollow(afterProgrammaticMove, speedKmh = 20f, nowMillis = 5_999L))
        assertTrue(shouldAutoReturnToFollow(afterProgrammaticMove, speedKmh = 20f, nowMillis = 6_000L))
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

        assertFalse(shouldAutoReturnToFollow(second, speedKmh = 20f, nowMillis = 7_499L))
        assertTrue(shouldAutoReturnToFollow(second, speedKmh = 20f, nowMillis = 7_500L))
    }
}
