package ru.sodovaya.volty.presentation.navigation

import ru.sodovaya.volty.domain.navigation.ArrivalSocEstimate
import ru.sodovaya.volty.domain.navigation.ArrivalSocUnknownReason
import ru.sodovaya.volty.domain.navigation.ManeuverKind
import ru.sodovaya.volty.domain.navigation.NavigationFailure
import ru.sodovaya.volty.domain.navigation.PlaceCandidate
import ru.sodovaya.volty.domain.navigation.RouteGuidance
import ru.sodovaya.volty.domain.navigation.RoutePlan
import ru.sodovaya.volty.util.UnitFormatter
import ru.sodovaya.volty.util.UnitSystem

enum class NavigationUiPhase {
    IDLE,
    PLANNING,
    ROUTE_READY,
    NAVIGATING,
    REROUTING,
    ARRIVED,
}

enum class NavigationUiManeuverIcon {
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

/** Stable keys keep copy and localization out of the state machine and pure mapper. */
enum class NavigationUiCopyKey {
    LOCATION_PERMISSION_REQUIRED,
    LOCATION_PERMISSION_DENIED,
    LOCATION_PROVIDER_DISABLED,
    LOCATION_SEARCHING,
    LOCATION_STALE,
    LOCATION_POOR_ACCURACY,
    LOCATION_FRESH_REQUIRED,
    SEARCH_OFFLINE,
    SEARCH_RATE_LIMITED,
    ROUTE_OFFLINE,
    ROUTE_NO_ROUTE,
    ROUTE_RATE_LIMITED,
    ROUTE_PROVIDER_UNAVAILABLE,
    ROUTE_INVALID_REQUEST,
    ROUTE_MALFORMED_RESPONSE,
    SOC_NO_ROUTE,
    SOC_BMS_DISCONNECTED,
    SOC_PACKS_PARTIAL,
    SOC_UNEARNED,
    SOC_CAPACITY_UNEARNED,
    SOC_TELEMETRY_STALE,
    SOC_CONSUMPTION_UNEARNED,
}

data class NavigationUiAlternative(
    val routeId: String,
    val distanceText: String,
    val durationMinutes: Long,
    val selected: Boolean,
)

data class NavigationUiManeuver(
    val icon: NavigationUiManeuverIcon,
    val distanceText: String,
    val instruction: String,
    val streetName: String?,
)

data class NavigationUiModel(
    val phase: NavigationUiPhase,
    val query: String,
    val searchResults: List<PlaceCandidate>,
    val destination: PlaceCandidate?,
    val requestInFlight: Boolean,
    val selectedRouteId: String?,
    val alternatives: List<NavigationUiAlternative>,
    val maneuver: NavigationUiManeuver?,
    val remainingDistanceText: String?,
    val remainingDurationMinutes: Long?,
    val locationBanner: NavigationUiCopyKey?,
    val failureBanner: NavigationUiCopyKey?,
    val retryAfterSeconds: Long?,
    val arrivalSocPercent: Int?,
    val arrivalSocReason: NavigationUiCopyKey?,
    val canStart: Boolean,
    val canRetry: Boolean,
    val canStop: Boolean,
)

object LightNavigationUiMapper {
    fun map(state: LightNavigationState, units: UnitSystem): NavigationUiModel {
        val phase = state.phase
        val routePlan = phase.routePlanOrNull()
        val selectedRouteId = phase.selectedRouteIdOrNull()
        val selectedRoute = routePlan?.alternatives?.firstOrNull { it.id == selectedRouteId }
        val guidance = (phase as? NavigationPhase.Navigating)?.guidance
            ?.takeIf { state.locationStatus == LocationUiStatus.FRESH }

        val planning = phase as? NavigationPhase.Planning
        val locationBanner = locationBanner(state.locationStatus, phase)
        val failure = phase.failureOrNull()
        return NavigationUiModel(
            phase = phase.toUiPhase(),
            query = planning?.query.orEmpty(),
            searchResults = planning?.searchResults.orEmpty(),
            destination = phase.destinationOrNull(),
            requestInFlight = planning?.requestInFlight == true,
            selectedRouteId = selectedRouteId,
            alternatives = routePlan?.alternatives.orEmpty().map { route ->
                NavigationUiAlternative(
                    routeId = route.id,
                    distanceText = distanceText(route.distanceMeters, units),
                    durationMinutes = durationMinutes(route.durationSeconds),
                    selected = route.id == selectedRouteId,
                )
            },
            maneuver = guidance?.toUiManeuver(units),
            remainingDistanceText = guidance?.let { distanceText(it.remainingDistanceMeters, units) },
            remainingDurationMinutes = guidance?.let { durationMinutes(it.remainingDurationSeconds) },
            locationBanner = locationBanner,
            failureBanner = failure?.let {
                failureBanner(
                    failure = it,
                    searchFailure = planning?.destination == null,
                )
            },
            retryAfterSeconds = (failure as? NavigationFailure.RateLimited)?.retryAfterSeconds,
            arrivalSocPercent = (state.arrivalSoc as? ArrivalSocEstimate.Known)?.percent,
            arrivalSocReason = (state.arrivalSoc as? ArrivalSocEstimate.Unknown)
                ?.reason
                ?.takeUnless { it == ArrivalSocUnknownReason.NO_ROUTE && routePlan != null }
                ?.toUiCopyKey(),
            canStart = phase is NavigationPhase.RouteReady,
            canRetry = when (phase) {
                is NavigationPhase.Planning -> !phase.requestInFlight &&
                    (phase.destination != null || phase.failure != null)
                is NavigationPhase.Rerouting -> phase.failure != null
                else -> false
            },
            canStop = phase !is NavigationPhase.Idle,
        )
    }

    private fun NavigationPhase.toUiPhase(): NavigationUiPhase = when (this) {
        NavigationPhase.Idle -> NavigationUiPhase.IDLE
        is NavigationPhase.Planning -> NavigationUiPhase.PLANNING
        is NavigationPhase.RouteReady -> NavigationUiPhase.ROUTE_READY
        is NavigationPhase.Navigating -> NavigationUiPhase.NAVIGATING
        is NavigationPhase.Rerouting -> NavigationUiPhase.REROUTING
        is NavigationPhase.Arrived -> NavigationUiPhase.ARRIVED
    }

    private fun NavigationPhase.routePlanOrNull(): RoutePlan? = when (this) {
        is NavigationPhase.RouteReady -> plan
        is NavigationPhase.Navigating -> plan
        is NavigationPhase.Rerouting -> plan
        is NavigationPhase.Arrived -> plan
        NavigationPhase.Idle,
        is NavigationPhase.Planning -> null
    }

    private fun NavigationPhase.selectedRouteIdOrNull(): String? = when (this) {
        is NavigationPhase.RouteReady -> selectedRouteId
        is NavigationPhase.Navigating -> selectedRouteId
        is NavigationPhase.Rerouting -> selectedRouteId
        is NavigationPhase.Arrived -> selectedRouteId
        NavigationPhase.Idle,
        is NavigationPhase.Planning -> null
    }

    private fun NavigationPhase.destinationOrNull(): PlaceCandidate? = when (this) {
        is NavigationPhase.Planning -> destination
        is NavigationPhase.RouteReady -> plan.destination
        is NavigationPhase.Navigating -> plan.destination
        is NavigationPhase.Rerouting -> plan.destination
        is NavigationPhase.Arrived -> plan.destination
        NavigationPhase.Idle -> null
    }

    private fun NavigationPhase.failureOrNull(): NavigationFailure? =
        (this as? NavigationPhase.Planning)?.failure
            ?: (this as? NavigationPhase.Rerouting)?.failure

    private fun locationBanner(status: LocationUiStatus, phase: NavigationPhase): NavigationUiCopyKey? = when (status) {
        LocationUiStatus.NOT_REQUESTED -> if (
            phase is NavigationPhase.RouteReady ||
            (phase is NavigationPhase.Planning && phase.destination != null)
        ) NavigationUiCopyKey.LOCATION_FRESH_REQUIRED else null
        LocationUiStatus.PERMISSION_REQUIRED -> NavigationUiCopyKey.LOCATION_PERMISSION_REQUIRED
        LocationUiStatus.PERMISSION_DENIED -> NavigationUiCopyKey.LOCATION_PERMISSION_DENIED
        LocationUiStatus.PROVIDER_DISABLED -> NavigationUiCopyKey.LOCATION_PROVIDER_DISABLED
        LocationUiStatus.SEARCHING -> NavigationUiCopyKey.LOCATION_SEARCHING
        LocationUiStatus.STALE -> NavigationUiCopyKey.LOCATION_STALE
        LocationUiStatus.POOR_ACCURACY -> NavigationUiCopyKey.LOCATION_POOR_ACCURACY
        LocationUiStatus.FRESH -> null
    }

    private fun failureBanner(failure: NavigationFailure, searchFailure: Boolean): NavigationUiCopyKey = when (failure) {
        NavigationFailure.Offline -> if (searchFailure) NavigationUiCopyKey.SEARCH_OFFLINE else NavigationUiCopyKey.ROUTE_OFFLINE
        NavigationFailure.NoRoute -> NavigationUiCopyKey.ROUTE_NO_ROUTE
        is NavigationFailure.RateLimited -> if (searchFailure) NavigationUiCopyKey.SEARCH_RATE_LIMITED else NavigationUiCopyKey.ROUTE_RATE_LIMITED
        NavigationFailure.ProviderUnavailable -> NavigationUiCopyKey.ROUTE_PROVIDER_UNAVAILABLE
        is NavigationFailure.InvalidRequest -> NavigationUiCopyKey.ROUTE_INVALID_REQUEST
        NavigationFailure.MalformedResponse -> NavigationUiCopyKey.ROUTE_MALFORMED_RESPONSE
    }

    private fun RouteGuidance.toUiManeuver(units: UnitSystem): NavigationUiManeuver = NavigationUiManeuver(
        icon = maneuver.kind.toUiIcon(),
        distanceText = distanceText(distanceToManeuverMeters, units),
        instruction = maneuver.instruction,
        streetName = maneuver.streetName,
    )

    private fun ManeuverKind.toUiIcon(): NavigationUiManeuverIcon = when (this) {
        ManeuverKind.DEPART -> NavigationUiManeuverIcon.DEPART
        ManeuverKind.STRAIGHT -> NavigationUiManeuverIcon.STRAIGHT
        ManeuverKind.SLIGHT_LEFT -> NavigationUiManeuverIcon.SLIGHT_LEFT
        ManeuverKind.LEFT -> NavigationUiManeuverIcon.LEFT
        ManeuverKind.SHARP_LEFT -> NavigationUiManeuverIcon.SHARP_LEFT
        ManeuverKind.SLIGHT_RIGHT -> NavigationUiManeuverIcon.SLIGHT_RIGHT
        ManeuverKind.RIGHT -> NavigationUiManeuverIcon.RIGHT
        ManeuverKind.SHARP_RIGHT -> NavigationUiManeuverIcon.SHARP_RIGHT
        ManeuverKind.U_TURN -> NavigationUiManeuverIcon.U_TURN
        ManeuverKind.ROUNDABOUT -> NavigationUiManeuverIcon.ROUNDABOUT
        ManeuverKind.ARRIVE -> NavigationUiManeuverIcon.ARRIVE
        ManeuverKind.UNKNOWN -> NavigationUiManeuverIcon.UNKNOWN
    }

    private fun ArrivalSocUnknownReason.toUiCopyKey(): NavigationUiCopyKey = when (this) {
        ArrivalSocUnknownReason.NO_ROUTE -> NavigationUiCopyKey.SOC_NO_ROUTE
        ArrivalSocUnknownReason.BMS_DISCONNECTED -> NavigationUiCopyKey.SOC_BMS_DISCONNECTED
        ArrivalSocUnknownReason.PACKS_PARTIAL -> NavigationUiCopyKey.SOC_PACKS_PARTIAL
        ArrivalSocUnknownReason.SOC_UNEARNED -> NavigationUiCopyKey.SOC_UNEARNED
        ArrivalSocUnknownReason.CAPACITY_UNEARNED -> NavigationUiCopyKey.SOC_CAPACITY_UNEARNED
        ArrivalSocUnknownReason.TELEMETRY_STALE -> NavigationUiCopyKey.SOC_TELEMETRY_STALE
        ArrivalSocUnknownReason.CONSUMPTION_UNEARNED -> NavigationUiCopyKey.SOC_CONSUMPTION_UNEARNED
    }

    private fun distanceText(meters: Double, units: UnitSystem): String =
        UnitFormatter.distance((meters / 1_000.0).toFloat(), units, decimals = 1)

    private fun durationMinutes(seconds: Long): Long =
        ((seconds.coerceAtLeast(0L) + 59L) / 60L).coerceAtLeast(1L)
}
