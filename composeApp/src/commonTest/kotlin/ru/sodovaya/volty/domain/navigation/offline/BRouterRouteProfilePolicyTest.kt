package ru.sodovaya.volty.domain.navigation.offline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import ru.sodovaya.volty.domain.navigation.routing.RouteStyle
import ru.sodovaya.volty.domain.navigation.routing.RoutingPreferences

class BRouterRouteProfilePolicyTest {
    @Test
    fun fast_profile_keeps_highways_and_passes_the_declared_speed() {
        val values = BRouterRouteProfilePolicy.overrides(
            style = RouteStyle.FAST_WITH_HIGHWAYS,
            preferences = RoutingPreferences(declaredTopSpeedKph = 130),
        ).asKeyValues()

        assertEquals("130", values["vmax"])
        assertEquals("0", values["avoid_motorways"])
        assertEquals("0", values["consider_river"])
        assertEquals("0", values["consider_forest"])
        assertEquals("0", values["consider_town"])
    }

    @Test
    fun non_fast_profiles_discourage_motorways_and_touring_adds_scenic_biases() {
        val preferences = RoutingPreferences(
            declaredTopSpeedKph = 75,
            avoidTolls = true,
            avoidUnpaved = true,
        )

        val curvy = BRouterRouteProfilePolicy.overrides(RouteStyle.CURVY, preferences)
        assertTrue(curvy.avoidMotorways)
        assertTrue(curvy.considerRiver)
        assertTrue(curvy.considerForest)
        assertFalse(curvy.considerTown)

        val touring = BRouterRouteProfilePolicy.overrides(RouteStyle.MAX_CURVY_TOURING, preferences)
        assertTrue(touring.considerTown)
        assertEquals("1", touring.asKeyValues()["avoid_toll"])
        assertEquals("1", touring.asKeyValues()["avoid_unpaved"])
    }
}
