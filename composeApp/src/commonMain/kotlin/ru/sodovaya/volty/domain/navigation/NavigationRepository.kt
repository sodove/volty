package ru.sodovaya.volty.domain.navigation

interface NavigationRepository {
    suspend fun search(
        query: String,
        near: GeoCoordinate?,
        languageTag: String,
    ): NavigationResult<List<PlaceCandidate>>

    suspend fun routes(request: RouteRequest): NavigationResult<RoutePlan>
}
