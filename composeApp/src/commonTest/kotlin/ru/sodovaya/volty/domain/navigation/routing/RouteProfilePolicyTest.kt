package ru.sodovaya.volty.domain.navigation.routing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import ru.sodovaya.volty.domain.navigation.GeoCoordinate
import ru.sodovaya.volty.domain.navigation.PlaceCandidate
import ru.sodovaya.volty.domain.navigation.RouteRequest
import ru.sodovaya.volty.domain.navigation.routing.RoutingPreferences

class RouteProfilePolicyTest {
    @Test
    fun low_speed_requests_start_on_bicycle_and_never_start_on_generic_auto() {
        RouteStyle.entries.forEach { style ->
            val profiles = RouteProfilePolicy.profilesFor(style, topSpeedKph = 20)

            assertEquals(RouteProfile.BICYCLE, profiles.first(), style.name)
            assertTrue(RouteProfile.GENERIC in profiles)
            assertTrue(profiles.indexOf(RouteProfile.BICYCLE) < profiles.indexOf(RouteProfile.GENERIC))
        }
    }

    @Test
    fun low_speed_requests_can_fall_back_to_pedestrian_before_generic() {
        assertEquals(
            listOf(RouteProfile.BICYCLE, RouteProfile.PEDESTRIAN, RouteProfile.GENERIC),
            RouteProfilePolicy.profilesFor(RouteStyle.CURVY, topSpeedKph = 30),
        )
        assertEquals(
            listOf(RouteProfile.BICYCLE, RouteProfile.PEDESTRIAN, RouteProfile.GENERIC),
            RouteProfilePolicy.profilesFor(RouteStyle.FAST_WITH_HIGHWAYS, topSpeedKph = 25),
        )
    }

    @Test
    fun motorcycle_is_primary_above_low_speed_and_bicycle_is_curvy_fallback_until_60() {
        assertEquals(
            listOf(RouteProfile.MOTORCYCLE, RouteProfile.BICYCLE, RouteProfile.GENERIC),
            RouteProfilePolicy.profilesFor(RouteStyle.CURVY, topSpeedKph = 50),
        )
        assertEquals(
            listOf(RouteProfile.MOTORCYCLE, RouteProfile.GENERIC),
            RouteProfilePolicy.profilesFor(RouteStyle.CURVY, topSpeedKph = 61),
        )
        assertEquals(
            listOf(RouteProfile.MOTORCYCLE, RouteProfile.GENERIC),
            RouteProfilePolicy.profilesFor(RouteStyle.FAST_WITH_HIGHWAYS, topSpeedKph = 50),
        )
    }

    @Test
    fun highway_free_policy_follows_speed_and_style() {
        fun request(style: RouteStyle, speed: Int) = RouteRequest(
            origin = GeoCoordinate(56.84, 60.61),
            destination = PlaceCandidate(
                id = "finish",
                title = "Плотинка",
                subtitle = null,
                coordinate = GeoCoordinate(56.85, 60.62),
            ),
            languageTag = "ru-RU",
            style = style,
            preferences = RoutingPreferences(declaredTopSpeedKph = speed),
        )

        assertTrue(
            RouteProfilePolicy.requiresHighwayFreeRoute(
                request(RouteStyle.FAST_WITH_HIGHWAYS, speed = 20),
            ),
        )
        assertTrue(
            RouteProfilePolicy.requiresHighwayFreeRoute(
                request(RouteStyle.CURVY, speed = 90),
            ),
        )
        assertTrue(
            RouteProfilePolicy.requiresHighwayFreeRoute(
                request(RouteStyle.FAST_WITHOUT_HIGHWAYS, speed = 90),
            ),
        )
        assertTrue(
            !RouteProfilePolicy.requiresHighwayFreeRoute(
                request(RouteStyle.FAST_WITH_HIGHWAYS, speed = 90),
            ),
        )
    }
}
