package ru.sodovaya.volty.domain.navigation.offline

import ru.sodovaya.volty.domain.navigation.routing.RouteStyle
import ru.sodovaya.volty.domain.navigation.routing.RoutingPreferences

/**
 * Values understood by the bundled generic BRouter profile.
 *
 * BRouter expands profile parameters through [keyValues] before it calculates
 * a route. Keeping this mapping pure makes the fallback obey the same style
 * and top-speed contract as the Valhalla path without adding a vehicle type.
 */
data class BRouterProfileOverrides(
    val topSpeedKph: Int,
    val avoidTolls: Boolean,
    val avoidUnpaved: Boolean,
    val avoidMotorways: Boolean,
    val considerRiver: Boolean,
    val considerForest: Boolean,
    val considerTown: Boolean,
) {
    fun asKeyValues(): Map<String, String> = mapOf(
        "vmax" to topSpeedKph.toString(),
        "avoid_toll" to avoidTolls.toString(),
        "avoid_unpaved" to avoidUnpaved.toString(),
        "avoid_motorways" to avoidMotorways.toString(),
        "consider_river" to considerRiver.toString(),
        "consider_forest" to considerForest.toString(),
        "consider_town" to considerTown.toString(),
    )
}

object BRouterRouteProfilePolicy {
    fun overrides(
        style: RouteStyle,
        preferences: RoutingPreferences,
    ): BRouterProfileOverrides = BRouterProfileOverrides(
        topSpeedKph = preferences.declaredTopSpeedKph,
        avoidTolls = preferences.avoidTolls,
        avoidUnpaved = preferences.avoidUnpaved,
        // Curvy profiles should not silently turn into motorway routes when a
        // comparable local road exists, but the profile still keeps motorways
        // as a last-resort connection when no alternative is possible.
        avoidMotorways = style != RouteStyle.FAST_WITH_HIGHWAYS,
        considerRiver = style == RouteStyle.CURVY || style == RouteStyle.MAX_CURVY_TOURING,
        considerForest = style == RouteStyle.CURVY || style == RouteStyle.MAX_CURVY_TOURING,
        considerTown = style == RouteStyle.MAX_CURVY_TOURING,
    )
}
