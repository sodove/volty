package ru.sodovaya.volty.backend

import io.ktor.server.routing.Route
import io.ktor.server.application.call
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

interface NavigationProvider {
    suspend fun search(request: ProviderSearchRequest): ProviderResult<List<NavigationPlaceDto>>
    suspend fun routes(request: ProviderRouteRequest): ProviderResult<NavigationRouteResponse>
}

class DisabledNavigationProvider : NavigationProvider {
    override suspend fun search(request: ProviderSearchRequest): ProviderResult<List<NavigationPlaceDto>> =
        ProviderResult.Unavailable

    override suspend fun routes(request: ProviderRouteRequest): ProviderResult<NavigationRouteResponse> =
        ProviderResult.Unavailable
}

fun navigationProviderFor(@Suppress("UNUSED_PARAMETER") config: AppConfig): NavigationProvider =
    DisabledNavigationProvider()

fun Route.installNavigationRoutes(dependencies: AppDependencies) {
    route("navigation") {
        get("search") { handleNavigationSearch(call, dependencies) }
        post("routes") { handleNavigationRoutes(call, dependencies) }
    }
}
