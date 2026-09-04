package ru.sodovaya.volty.domain.navigation.routing

import ru.sodovaya.volty.domain.navigation.RouteRequest

/**
 * Engine routing profiles are selected from the rider's requested style and
 * speed. This is intentionally not a vehicle/transport type: it describes
 * which access graph is safest and most useful for this route request.
 */
enum class RouteProfile {
    GENERIC("generic"),
    MOTORCYCLE("motorcycle"),
    BICYCLE("bicycle"),
    PEDESTRIAN("pedestrian");

    val wireName: String

    private constructor(wireName: String) {
        this.wireName = wireName
    }
}

/**
 * Production profile matrix for a personal EV.
 *
 * At walking/cycle speeds a generic auto costing is too eager to use major
 * roads. Bicycle is the primary low-speed graph because it can use cycleways
 * without inheriting pedestrian stairs. Pedestrian is kept as an explicit
 * low-speed fallback when the bicycle graph cannot connect the corridor. Above 30 km/h motorcycle is
 * the closest road-access graph; bicycle remains a curvy fallback up to its
 * useful speed ceiling. Generic is always the last fallback.
 */
object RouteProfilePolicy {
    const val LOW_SPEED_MAX_KPH = 30
    const val BICYCLE_MAX_KPH = 60

    fun profilesFor(style: RouteStyle, topSpeedKph: Int): List<RouteProfile> {
        val speed = topSpeedKph.coerceIn(20, 130)
        return when {
            speed <= LOW_SPEED_MAX_KPH -> listOf(
                RouteProfile.BICYCLE,
                RouteProfile.PEDESTRIAN,
                RouteProfile.GENERIC,
            )
            style == RouteStyle.CURVY || style == RouteStyle.MAX_CURVY_TOURING -> buildList {
                add(RouteProfile.MOTORCYCLE)
                if (speed <= BICYCLE_MAX_KPH) add(RouteProfile.BICYCLE)
                add(RouteProfile.GENERIC)
            }
            else -> listOf(RouteProfile.MOTORCYCLE, RouteProfile.GENERIC)
        }
    }

    fun profilesFor(request: RouteRequest): List<RouteProfile> =
        profilesFor(
            style = request.style,
            topSpeedKph = request.preferences.declaredTopSpeedKph,
        )

    /**
     * Valhalla's highway factor is a soft costing preference. These requests
     * must therefore reject a response that still reports a highway segment.
     */
    fun requiresHighwayFreeRoute(request: RouteRequest): Boolean =
        request.preferences.declaredTopSpeedKph <= LOW_SPEED_MAX_KPH ||
            request.style != RouteStyle.FAST_WITH_HIGHWAYS
}
