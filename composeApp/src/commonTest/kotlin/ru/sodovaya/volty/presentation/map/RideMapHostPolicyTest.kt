package ru.sodovaya.volty.presentation.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import ru.sodovaya.volty.domain.model.DashboardStyle

class RideMapHostPolicyTest {
    @Test fun demoRetainsTheLightMapWithoutRequestingLocation() {
        assertEquals(
            RideMapHostState(true, true, false),
            rideMapHostState(true, RideMapScreen.RIDE, DashboardStyle.LIGHT, demoMode = true),
        )
    }
    @Test fun historyUsesRootMapWithoutAnActiveVehicleOrLiveLocationPermission() {
        assertEquals(RideMapHostState(true, true, false), rideMapHostState(false, RideMapScreen.HISTORY, null))
        assertEquals(RideMapHostState(true, true, false), rideMapHostState(true, RideMapScreen.HISTORY, DashboardStyle.LIGHT))
    }
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

    // Retraction: these tests used to require the Nearby tab to show the shared
    // map. The current Nearby tab (VoiceSpacesNearbyScreen) is fully opaque, so
    // the map only flashed through its crossfade and then rendered unseen under
    // it. The group map is the Nearby surface that actually shows the map.
    @Test
    fun nearby_tab_does_not_mount_the_map_without_a_vehicle() {
        assertEquals(
            RideMapHostState(
                mounted = false,
                visible = false,
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
    fun nearby_tab_keeps_the_ride_map_retained_but_hidden_and_never_requests_location() {
        assertEquals(
            RideMapHostState(
                mounted = true,
                visible = false,
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

    @Test fun hiddenMapStopsRenderingOnlyAfterItHasFadedOut() {
        assertTrue(rideMapRenderActive(visible = true, alpha = 0f))
        assertTrue(rideMapRenderActive(visible = false, alpha = 0.4f))
        assertEquals(false, rideMapRenderActive(visible = false, alpha = 0f))
    }

    // The outgoing Ride HUD is still composed for the whole crossfade, but the
    // stack's active child has already changed, so `visible` is false from the
    // first frame. A HUD that asked `visible` painted its opaque fallback over
    // the fading map (the 2026-11-04 flash). It must ask whether the map exists.
    @Test fun outgoingRideHudStaysTransparentWhileTheRetainedMapFadesOut() {
        for (screen in listOf(RideMapScreen.OTHER, RideMapScreen.BATTERY, RideMapScreen.NEARBY)) {
            val host = rideMapHostState(rideAvailable = true, activeScreen = screen, activeStyle = null)
            assertEquals(false, host.visible)
            assertTrue(rideHudMapBacked(host), "$screen")
        }
        assertTrue(rideHudMapBacked(rideMapHostState(true, RideMapScreen.RIDE, DashboardStyle.LIGHT)))
    }

    @Test fun rideHudFallsBackOnlyWhenNoMapIsMountedUnderIt() {
        assertEquals(false, rideHudMapBacked(rideMapHostState(false, RideMapScreen.RIDE, DashboardStyle.LIGHT)))
        assertEquals(false, rideHudMapBacked(rideMapHostState(false, RideMapScreen.OTHER, null)))
    }

    @Test fun mapFadeMatchesTheScreenCrossfade() {
        assertEquals(300, RIDE_MAP_FADE_MILLIS)
    }
}
