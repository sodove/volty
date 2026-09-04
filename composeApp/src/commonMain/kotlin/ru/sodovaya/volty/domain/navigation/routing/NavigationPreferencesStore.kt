package ru.sodovaya.volty.domain.navigation.routing

import kotlinx.coroutines.flow.Flow

/** Persistent planner defaults, deliberately separate from a destination or an active route. */
interface NavigationPreferencesStore {
    fun routeStyleFor(vehicleId: String?): Flow<RouteStyle>
    fun topSpeedKphFor(vehicleId: String?): Flow<Int>

    suspend fun setRouteStyle(vehicleId: String?, style: RouteStyle)
    suspend fun setTopSpeedKph(vehicleId: String?, speedKph: Int)
}
