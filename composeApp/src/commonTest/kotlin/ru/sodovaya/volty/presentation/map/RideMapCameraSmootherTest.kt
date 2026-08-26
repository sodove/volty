package ru.sodovaya.volty.presentation.map

import kotlin.test.Test
import kotlin.test.assertEquals

class RideMapCameraSmootherTest {
    @Test
    fun render_zoom_is_clamped_when_invalid_speed_uses_an_out_of_range_fallback() {
        assertEquals(
            RIDE_MAP_MAX_ZOOM,
            clampRideMapCameraZoom(rideMapZoomForSpeed(Float.NaN, fallbackZoom = 22.0)),
        )
        assertEquals(
            RIDE_MAP_MIN_ZOOM,
            clampRideMapCameraZoom(rideMapZoomForSpeed(Float.POSITIVE_INFINITY, fallbackZoom = 8.0)),
        )
    }
}
