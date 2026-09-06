package ru.sodovaya.volty.backend

import kotlinx.serialization.Serializable

@Serializable
data class GeoCoordinateDto(
    val latitude: Double,
    val longitude: Double,
)

data class ProviderSearchRequest(
    val query: String,
    val near: GeoCoordinateDto?,
    val languageTag: String,
    val limit: Int,
)

enum class NavigationRoutingProfile(val wireName: String) {
    GENERIC("generic"),
    MOTORCYCLE("motorcycle"),
    BICYCLE("bicycle"),
    PEDESTRIAN("pedestrian"),
    ;

    companion object {
        fun parse(value: String): NavigationRoutingProfile? = entries.firstOrNull {
            it.wireName == value.trim().lowercase()
        }
    }
}

data class ProviderRouteRequest(
    val origin: GeoCoordinateDto,
    val destination: GeoCoordinateDto,
    val languageTag: String,
    val alternativesLimit: Int,
    val routingProfile: NavigationRoutingProfile = NavigationRoutingProfile.GENERIC,
)

@Serializable
data class NavigationPlaceDto(
    val id: String,
    val title: String,
    val subtitle: String?,
    val latitude: Double,
    val longitude: Double,
)

@Serializable
data class NavigationManeuverDto(
    val id: String,
    val kind: String,
    val instruction: String,
    val streetName: String?,
    val shapeIndex: Int,
    val distanceMeters: Double,
)

@Serializable
data class NavigationRouteDto(
    val id: String,
    val distanceMeters: Double,
    val durationSeconds: Long,
    val geometry: List<GeoCoordinateDto>,
    val maneuvers: List<NavigationManeuverDto>,
)

@Serializable
data class NavigationRouteResponse(
    val schemaVersion: Int = 1,
    val destination: NavigationPlaceDto,
    val routes: List<NavigationRouteDto>,
)

@Serializable
data class NavigationRouteRequestDto(
    val origin: GeoCoordinateDto,
    val destination: NavigationPlaceDto,
    // Accepted only to keep older app builds wire-compatible; it is ignored.
    val profile: String? = null,
    val routingProfile: String = NavigationRoutingProfile.GENERIC.wireName,
    val languageTag: String,
    val alternativesLimit: Int = 3,
)

sealed interface ProviderResult<out T> {
    data class Success<T>(val value: T) : ProviderResult<T>
    data object NoRoute : ProviderResult<Nothing>
    data class RateLimited(val retryAfterSeconds: Long?) : ProviderResult<Nothing>
    data object Unavailable : ProviderResult<Nothing>
    data object MalformedResponse : ProviderResult<Nothing>
}
