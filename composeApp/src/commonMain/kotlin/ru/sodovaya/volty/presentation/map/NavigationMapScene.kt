package ru.sodovaya.volty.presentation.map

import ru.sodovaya.volty.domain.location.RideLocationFix
import ru.sodovaya.volty.domain.navigation.GeoCoordinate
import ru.sodovaya.volty.presentation.nearby.ParticipantMarker

data class NavigationRouteLine(
    val routeId: String,
    val points: List<GeoCoordinate>,
    val selected: Boolean,
    val active: Boolean,
    val completedFraction: Double,
)

data class NavigationTrailPoint(
    val coordinate: GeoCoordinate,
    val sample: RideMapTrailSample,
)

sealed interface MapCameraRequest {
    val sequence: Long

    data class FitAlternatives(
        override val sequence: Long,
        val points: List<GeoCoordinate>,
    ) : MapCameraRequest

    data class FollowFix(
        override val sequence: Long,
        val fix: RideLocationFix,
    ) : MapCameraRequest

    data class Recenter(
        override val sequence: Long,
        val fix: RideLocationFix,
    ) : MapCameraRequest
}

data class NavigationMapScene(
    val ownFix: RideLocationFix?,
    val trail: List<NavigationTrailPoint>,
    val participantMarkers: List<ParticipantMarker>,
    val routes: List<NavigationRouteLine>,
    val destination: GeoCoordinate?,
    val followState: RideMapFollowState,
    val cameraRequest: MapCameraRequest?,
)
