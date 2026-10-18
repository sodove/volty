package ru.sodovaya.volty.presentation.map

import ru.sodovaya.volty.domain.location.RideLocationFix
import ru.sodovaya.volty.domain.navigation.GeoCoordinate
import ru.sodovaya.volty.presentation.nearby.ParticipantMarker
import ru.sodovaya.volty.presentation.history.HistoryMapScene
import ru.sodovaya.volty.presentation.history.HistoryMapIdentity

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

    data class FitParticipants(
        override val sequence: Long,
        val points: List<GeoCoordinate>,
    ) : MapCameraRequest

    data class FitHistory(
        override val sequence: Long,
        val identity: HistoryMapIdentity,
        val insets: HistoryMapInsets,
        val reduceMotion: Boolean,
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
    val history: HistoryMapScene? = null,
)

data class HistoryMapInsets(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    init { require(left >= 0 && top >= 0 && right >= 0 && bottom >= 0) }
}

/** Pixel insets measured by the host, not guessed from the current live HUD. */
fun historyFitPadding(width: Int, height: Int, insets: HistoryMapInsets): HistoryMapInsets? {
    require(width >= 0 && height >= 0)
    return insets.takeIf { it.left.toLong() + it.right < width && it.top.toLong() + it.bottom < height }
}

fun mapCameraRequestApplies(scene: NavigationMapScene, request: MapCameraRequest): Boolean =
    if (request is MapCameraRequest.FitHistory) scene.history?.identity == request.identity
    else scene.history == null

/** Live data can change under a consumed command. Its number and kind own the
 * action; history additionally fences the ride/viewport, not its cursor data.
 */
fun sameMapCameraCommand(previous: MapCameraRequest?, next: MapCameraRequest): Boolean {
    if (previous == null || previous::class != next::class || previous.sequence != next.sequence) return false
    return if (previous is MapCameraRequest.FitHistory && next is MapCameraRequest.FitHistory)
        previous.identity == next.identity && previous.insets == next.insets && previous.reduceMotion == next.reduceMotion
    else true
}
