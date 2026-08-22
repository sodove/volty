package ru.sodovaya.volty.presentation.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RideMapCameraPolicyTest {
    @Test
    fun oblique_tilt_is_applied_before_a_location_fix() {
        assertEquals(58.0, defaultRideMapCameraPolicy.tiltDegrees)
        assertTrue(defaultRideMapCameraPolicy.appliesWithoutTarget)
    }

    @Test
    fun moving_gps_bearing_controls_the_camera_heading() {
        assertEquals(
            90.0,
            rideMapBearingDegrees(gpsBearingDegrees = 90f, speedKmh = 12f, fallbackDegrees = 0.0),
        )
    }

    @Test
    fun a_stationary_or_invalid_bearing_does_not_spin_the_camera() {
        assertEquals(
            17.0,
            rideMapBearingDegrees(gpsBearingDegrees = 180f, speedKmh = 0.2f, fallbackDegrees = 17.0),
        )
        assertEquals(
            17.0,
            rideMapBearingDegrees(gpsBearingDegrees = 999f, speedKmh = 12f, fallbackDegrees = 17.0),
        )
        assertNotEquals(
            180.0,
            rideMapBearingDegrees(gpsBearingDegrees = 180f, speedKmh = 0.2f, fallbackDegrees = 17.0),
        )
    }

    @Test
    fun faster_motion_zooms_the_camera_out() {
        val stopped = rideMapZoomForSpeed(0f)
        val city = rideMapZoomForSpeed(25f)
        val fast = rideMapZoomForSpeed(70f)

        assertTrue(stopped > city)
        assertTrue(city > fast)
        assertTrue(fast >= RIDE_MAP_MIN_ZOOM)
        assertTrue(stopped <= RIDE_MAP_MAX_ZOOM)
    }

    @Test
    fun invalid_speed_keeps_a_safe_existing_zoom() {
        assertEquals(
            15.25,
            rideMapZoomForSpeed(Float.NaN, fallbackZoom = 15.25),
        )
    }
}
