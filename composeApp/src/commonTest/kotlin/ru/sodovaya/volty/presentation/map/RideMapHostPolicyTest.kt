package ru.sodovaya.volty.presentation.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import ru.sodovaya.volty.domain.model.DashboardStyle

class RideMapHostPolicyTest {
    @Test
    fun ride_map_uses_texture_rendering_for_map_capture() {
        assertTrue(rideMapTextureModeRequiredForMapCapture)
    }

    @Test
    fun ride_map_stays_mounted_when_the_rider_visits_another_screen() {
        assertEquals(
            RideMapHostState(
                mounted = true,
                visible = false,
                requestLocationPermission = false,
            ),
            rideMapHostState(
                rideAvailable = true,
                activeScreen = RideMapScreen.BATTERY,
                activeStyle = null,
            ),
        )
    }

    @Test
    fun ride_map_is_visible_only_for_the_light_ride_screen() {
        assertEquals(
            RideMapHostState(
                mounted = true,
                visible = true,
                requestLocationPermission = true,
            ),
            rideMapHostState(
                rideAvailable = true,
                activeScreen = RideMapScreen.RIDE,
                activeStyle = DashboardStyle.LIGHT,
            ),
        )
        assertEquals(
            RideMapHostState(
                mounted = true,
                visible = false,
                requestLocationPermission = false,
            ),
            rideMapHostState(
                rideAvailable = true,
                activeScreen = RideMapScreen.RIDE,
                activeStyle = DashboardStyle.CLEAN,
            ),
        )
    }

    @Test
    fun nearby_map_is_visible_without_a_vehicle_and_does_not_request_own_location() {
        assertEquals(
            RideMapHostState(
                mounted = true,
                visible = true,
                requestLocationPermission = false,
            ),
            rideMapHostState(
                rideAvailable = false,
                activeScreen = RideMapScreen.NEARBY,
                activeStyle = null,
            ),
        )
    }

    @Test
    fun nearby_map_keeps_location_request_disabled_even_when_a_vehicle_is_connected() {
        assertEquals(
            RideMapHostState(
                mounted = true,
                visible = true,
                requestLocationPermission = false,
            ),
            rideMapHostState(
                rideAvailable = true,
                activeScreen = RideMapScreen.NEARBY,
                activeStyle = DashboardStyle.LIGHT,
            ),
        )
    }

    @Test
    fun group_map_uses_remote_markers_without_requesting_own_location() {
        assertEquals(
            RideMapHostState(
                mounted = true,
                visible = true,
                requestLocationPermission = false,
            ),
            rideMapHostState(
                rideAvailable = true,
                activeScreen = RideMapScreen.GROUP_MAP,
                activeStyle = DashboardStyle.LIGHT,
            ),
        )
    }
}
