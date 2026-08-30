package ru.sodovaya.volty.presentation.map

import ru.sodovaya.volty.domain.location.RideLocationFix
import ru.sodovaya.volty.presentation.navigation.LightNavigationState
import ru.sodovaya.volty.presentation.navigation.NavigationPhase

/** Projects retained navigation state into renderer input; no map SDK decisions live here. */
object NavigationMapRenderPolicy {
    fun scene(
        state: LightNavigationState,
        ownFix: RideLocationFix?,
        trail: List<NavigationTrailPoint> = emptyList(),
        participantMarkers: List<ru.sodovaya.volty.presentation.nearby.ParticipantMarker> = emptyList(),
        cameraSequence: Long = state.requestGeneration,
        recenterSequence: Long? = null,
    ): NavigationMapScene {
        val phase = state.phase
        val plan = when (phase) {
            is NavigationPhase.RouteReady -> phase.plan
            is NavigationPhase.Navigating -> phase.plan
            is NavigationPhase.Rerouting -> phase.plan
            is NavigationPhase.Arrived -> phase.plan
            NavigationPhase.Idle,
            is NavigationPhase.Planning -> null
        }
        val selectedRouteId = when (phase) {
            is NavigationPhase.RouteReady -> phase.selectedRouteId
            is NavigationPhase.Navigating -> phase.selectedRouteId
            is NavigationPhase.Rerouting -> phase.selectedRouteId
            is NavigationPhase.Arrived -> phase.selectedRouteId
            NavigationPhase.Idle,
            is NavigationPhase.Planning -> null
        }
        val guidance = (phase as? NavigationPhase.Navigating)?.guidance
        val lines = plan?.alternatives.orEmpty().map { route ->
            val isSelected = route.id == selectedRouteId
            val completedFraction = when {
                phase is NavigationPhase.Arrived && isSelected -> 1.0
                guidance?.routeId == route.id && route.distanceMeters > 0.0 -> {
                    1.0 - (guidance.remainingDistanceMeters / route.distanceMeters)
                }
                else -> 0.0
            }.coerceIn(0.0, 1.0)
            NavigationRouteLine(
                routeId = route.id,
                points = route.geometry,
                selected = isSelected,
                active = when (phase) {
                    is NavigationPhase.Rerouting -> false
                    is NavigationPhase.RouteReady,
                    is NavigationPhase.Navigating,
                    is NavigationPhase.Arrived -> isSelected
                    else -> false
                },
                completedFraction = completedFraction,
            )
        }
        val cameraRequest = when {
            phase is NavigationPhase.RouteReady -> MapCameraRequest.FitAlternatives(
                sequence = cameraSequence,
                points = lines.flatMap(NavigationRouteLine::points),
            )
            recenterSequence != null && recenterSequence > 0L && ownFix != null -> MapCameraRequest.Recenter(
                sequence = recenterSequence,
                fix = ownFix,
            )
            phase is NavigationPhase.Navigating &&
                ownFix != null &&
                state.followState.mode == RideMapFollowMode.FOLLOWING -> MapCameraRequest.FollowFix(
                sequence = cameraSequence,
                fix = ownFix,
            )
            else -> null
        }
        return NavigationMapScene(
            ownFix = ownFix,
            trail = trail,
            participantMarkers = participantMarkers,
            routes = lines,
            destination = plan?.destination?.coordinate,
            followState = state.followState,
            cameraRequest = cameraRequest,
        )
    }
}
