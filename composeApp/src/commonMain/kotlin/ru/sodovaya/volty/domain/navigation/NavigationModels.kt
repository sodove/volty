package ru.sodovaya.volty.domain.navigation

data class GeoCoordinate(
    val latitude: Double,
    val longitude: Double,
) {
    init {
        require(latitude.isFinite() && latitude in -90.0..90.0) {
            "Latitude must be finite and within [-90, 90]"
        }
        require(longitude.isFinite() && longitude in -180.0..180.0) {
            "Longitude must be finite and within [-180, 180]"
        }
    }
}

data class PlaceCandidate(
    val id: String,
    val title: String,
    val subtitle: String?,
    val coordinate: GeoCoordinate,
)

enum class ManeuverKind {
    DEPART,
    STRAIGHT,
    SLIGHT_LEFT,
    LEFT,
    SHARP_LEFT,
    SLIGHT_RIGHT,
    RIGHT,
    SHARP_RIGHT,
    U_TURN,
    ROUNDABOUT,
    ARRIVE,
    UNKNOWN,
}

data class RouteManeuver(
    val id: String,
    val kind: ManeuverKind,
    val instruction: String,
    val streetName: String?,
    val shapeIndex: Int,
    val distanceMeters: Double,
) {
    init {
        require(shapeIndex >= 0) { "Shape index must not be negative" }
        require(distanceMeters.isFinite() && distanceMeters >= 0.0) {
            "Maneuver distance must be finite and non-negative"
        }
    }
}

data class RouteAlternative(
    val id: String,
    val distanceMeters: Double,
    val durationSeconds: Long,
    val geometry: List<GeoCoordinate>,
    val maneuvers: List<RouteManeuver>,
) {
    init {
        require(distanceMeters.isFinite() && distanceMeters >= 0.0) {
            "Route distance must be finite and non-negative"
        }
        require(durationSeconds > 0L) { "Route duration must be positive" }
        require(geometry.size >= 2) { "Route geometry must contain at least two points" }
        require(maneuvers.isNotEmpty()) { "Route must contain at least one maneuver" }
        require(maneuvers.last().kind == ManeuverKind.ARRIVE) {
            "The last route maneuver must be ARRIVE"
        }
        require(maneuvers.all { it.shapeIndex in geometry.indices }) {
            "Maneuver shape indices must refer to the route geometry"
        }
    }
}

data class RoutePlan(
    val destination: PlaceCandidate,
    val alternatives: List<RouteAlternative>,
) {
    init {
        require(alternatives.size in 1..3) { "Route plan must contain between one and three alternatives" }
        require(alternatives.map { it.id }.toSet().size == alternatives.size) {
            "Route alternative IDs must be unique"
        }
    }
}

data class RouteRequest(
    val origin: GeoCoordinate,
    val destination: PlaceCandidate,
    val languageTag: String,
    val alternativesLimit: Int = 3,
)

sealed interface NavigationFailure {
    data object Offline : NavigationFailure
    data object NoRoute : NavigationFailure
    data class RateLimited(val retryAfterSeconds: Long) : NavigationFailure
    data object ProviderUnavailable : NavigationFailure
    data class InvalidRequest(val reason: String) : NavigationFailure
    data object MalformedResponse : NavigationFailure
}

sealed interface NavigationResult<out T> {
    data class Success<T>(val value: T) : NavigationResult<T>
    data class Failure(val reason: NavigationFailure) : NavigationResult<Nothing>
}
