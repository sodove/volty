package ru.sodovaya.volty.presentation.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RideMapCameraPolicyTest {
    @Test
    fun camera_targets_are_interpolated_between_frames() {
        val smoother = RideMapCameraSmoother()

        assertEquals(
            RideMapCameraFrame(16.5, 0.0),
            smoother.advance(targetZoom = 16.5, targetBearingDegrees = 0.0, deltaMillis = 16L),
        )

        val frame = smoother.advance(
            targetZoom = 13.5,
            targetBearingDegrees = 90.0,
            deltaMillis = 16L,
        )

        assertTrue(frame.zoom in 13.5..16.5)
        assertTrue(frame.zoom != 13.5 && frame.zoom != 16.5)
        assertTrue(frame.bearingDegrees in 0.0..90.0)
        assertTrue(frame.bearingDegrees != 0.0 && frame.bearingDegrees != 90.0)
    }

    @Test
    fun camera_center_is_interpolated_between_position_fixes() {
        val smoother = RideMapCameraSmoother()
        val firstTarget = RideMapPredictedCoordinate(56.8, 60.6)
        val secondTarget = RideMapPredictedCoordinate(56.801, 60.602)

        val first = smoother.advance(
            targetZoom = 16.5,
            targetBearingDegrees = 0.0,
            targetCenter = firstTarget,
            deltaMillis = 16L,
        )
        val frame = smoother.advance(
            targetZoom = 16.5,
            targetBearingDegrees = 0.0,
            targetCenter = secondTarget,
            deltaMillis = 16L,
        )

        val center = checkNotNull(frame.center)
        assertEquals(firstTarget, first.center)
        assertTrue(center.latitude > firstTarget.latitude)
        assertTrue(center.latitude < secondTarget.latitude)
        assertTrue(center.longitude > firstTarget.longitude)
        assertTrue(center.longitude < secondTarget.longitude)
    }

    @Test
    fun camera_bearing_uses_the_shortest_path_across_north() {
        val smoother = RideMapCameraSmoother()
        smoother.advance(targetZoom = 15.0, targetBearingDegrees = 359.0, deltaMillis = 16L)

        val frame = smoother.advance(
            targetZoom = 15.0,
            targetBearingDegrees = 1.0,
            deltaMillis = 16L,
        )

        assertTrue(frame.bearingDegrees > 359.0 || frame.bearingDegrees < 1.0)
        assertTrue(frame.bearingDegrees < 360.0)
    }

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
        val topSpeed = rideMapZoomForSpeed(140f)

        assertTrue(stopped > city)
        assertTrue(city > fast)
        assertTrue(fast >= RIDE_MAP_MIN_ZOOM)
        assertTrue(fast > RIDE_MAP_MIN_ZOOM)
        assertEquals(RIDE_MAP_MIN_ZOOM, topSpeed)
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
