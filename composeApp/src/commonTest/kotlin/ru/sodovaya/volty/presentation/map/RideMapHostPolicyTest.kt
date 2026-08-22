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
            RideMapHostState(mounted = true, visible = false),
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
            RideMapHostState(mounted = true, visible = true),
            rideMapHostState(
                rideAvailable = true,
                activeScreen = RideMapScreen.RIDE,
                activeStyle = DashboardStyle.LIGHT,
            ),
        )
        assertEquals(
            RideMapHostState(mounted = true, visible = false),
            rideMapHostState(
                rideAvailable = true,
                activeScreen = RideMapScreen.RIDE,
                activeStyle = DashboardStyle.CLEAN,
            ),
        )
    }
}
