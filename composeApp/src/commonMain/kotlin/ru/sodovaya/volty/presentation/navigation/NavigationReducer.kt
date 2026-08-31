package ru.sodovaya.volty.presentation.navigation

import ru.sodovaya.volty.domain.navigation.ArrivalSocEstimate
import ru.sodovaya.volty.domain.navigation.ArrivalSocUnknownReason
import ru.sodovaya.volty.domain.navigation.NavigationFailure
import ru.sodovaya.volty.domain.navigation.PlaceCandidate
import ru.sodovaya.volty.domain.navigation.RouteGuidance
import ru.sodovaya.volty.domain.navigation.RoutePlan
import ru.sodovaya.volty.presentation.map.RideMapFollowState

enum class LocationUiStatus {
    NOT_REQUESTED,
    PERMISSION_REQUIRED,
    PERMISSION_DENIED,
    PROVIDER_DISABLED,
    SEARCHING,
    FRESH,
    STALE,
    POOR_ACCURACY,
}

sealed interface NavigationPhase {
    data object Idle : NavigationPhase

    data class Planning(
        val query: String,
        val searchResults: List<PlaceCandidate>,
        val destination: PlaceCandidate?,
        val requestInFlight: Boolean,
        val failure: NavigationFailure?,
    ) : NavigationPhase

    data class RouteReady(
        val plan: RoutePlan,
        val selectedRouteId: String,
    ) : NavigationPhase

    data class Navigating(
        val plan: RoutePlan,
        val selectedRouteId: String,
        val guidance: RouteGuidance?,
    ) : NavigationPhase

    data class Rerouting(
        val plan: RoutePlan,
        val selectedRouteId: String,
        val attempt: Int,
        val failure: NavigationFailure?,
    ) : NavigationPhase

    data class Arrived(
        val plan: RoutePlan,
        val selectedRouteId: String,
    ) : NavigationPhase
}

data class LightNavigationState(
    val phase: NavigationPhase = NavigationPhase.Idle,
    val locationStatus: LocationUiStatus = LocationUiStatus.NOT_REQUESTED,
    val arrivalSoc: ArrivalSocEstimate = ArrivalSocEstimate.Unknown(ArrivalSocUnknownReason.NO_ROUTE),
    val followState: RideMapFollowState = RideMapFollowState(),
    val requestGeneration: Long = 0L,
)

sealed interface NavigationAction {
    data object PlannerRequested : NavigationAction
    data class QueryChanged(val query: String) : NavigationAction
    data class PlaceSelected(val place: PlaceCandidate) : NavigationAction

    data class SearchStarted(val requestGeneration: Long) : NavigationAction
    data class SearchLoaded(
        val requestGeneration: Long,
        val results: List<PlaceCandidate>,
    ) : NavigationAction
    data class SearchFailed(
        val requestGeneration: Long,
        val failure: NavigationFailure,
    ) : NavigationAction

    data class RouteRequestStarted(val requestGeneration: Long) : NavigationAction
    data class RouteLoaded(
        val plan: RoutePlan,
        val requestGeneration: Long = 0L,
    ) : NavigationAction
    data class RouteFailed(
        val failure: NavigationFailure,
        val requestGeneration: Long = 0L,
    ) : NavigationAction
    data class RerouteStarted(
        val requestGeneration: Long,
        val attempt: Int,
    ) : NavigationAction
    data class RerouteLoaded(
        val plan: RoutePlan,
        val requestGeneration: Long,
    ) : NavigationAction
    data class RerouteFailed(
        val failure: NavigationFailure,
        val requestGeneration: Long,
    ) : NavigationAction

    data class AlternativeSelected(val routeId: String) : NavigationAction
    data object StartNavigation : NavigationAction
    data class GuidanceUpdated(val guidance: RouteGuidance) : NavigationAction
    data class BeginRerouting(val attempt: Int) : NavigationAction
    data object Arrived : NavigationAction
    data object GuidanceCleared : NavigationAction
    data object RequestsCancelled : NavigationAction
    data object LifecycleLocationUnavailable : NavigationAction
    data object StopNavigation : NavigationAction

    data class LocationStatusChanged(val status: LocationUiStatus) : NavigationAction
    data class ArrivalSocChanged(val estimate: ArrivalSocEstimate) : NavigationAction
    data class CameraGesture(val nowElapsedMillis: Long) : NavigationAction
    data object RecenterRequested : NavigationAction
}

/** Pure state machine for the retained planner and navigation scene. */
object NavigationReducer {
    fun reduce(state: LightNavigationState, action: NavigationAction): LightNavigationState = when (action) {
        NavigationAction.PlannerRequested -> if (state.phase is NavigationPhase.Idle) {
            state.copy(phase = emptyPlanning())
        } else {
            state
        }

        is NavigationAction.QueryChanged -> {
            state.copy(
                phase = NavigationPhase.Planning(
                    query = action.query,
                    searchResults = emptyList(),
                    destination = null,
                    requestInFlight = false,
                    failure = null,
                ),
                requestGeneration = nextGeneration(state),
                arrivalSoc = unknownArrivalSoc(),
            )
        }

        is NavigationAction.PlaceSelected -> when (val phase = state.phase) {
            is NavigationPhase.Planning -> state.copy(
                phase = phase.copy(
                    searchResults = emptyList(),
                    destination = action.place,
                    requestInFlight = false,
                    failure = null,
                ),
                requestGeneration = nextGeneration(state),
                arrivalSoc = unknownArrivalSoc(),
            )
            else -> state
        }

        is NavigationAction.SearchStarted -> if (isCurrent(state, action.requestGeneration)) {
            when (val phase = state.phase) {
                is NavigationPhase.Planning -> state.copy(
                    phase = phase.copy(requestInFlight = true, failure = null),
                )
                else -> state
            }
        } else {
            state
        }

        is NavigationAction.SearchLoaded -> if (isCurrent(state, action.requestGeneration)) {
            when (val phase = state.phase) {
                is NavigationPhase.Planning -> state.copy(
                    phase = phase.copy(
                        searchResults = action.results,
                        requestInFlight = false,
                        failure = null,
                    ),
                )
                else -> state
            }
        } else {
            state
        }

        is NavigationAction.SearchFailed -> if (isCurrent(state, action.requestGeneration)) {
            when (val phase = state.phase) {
                is NavigationPhase.Planning -> state.copy(
                    phase = phase.copy(requestInFlight = false, failure = action.failure),
                )
                else -> state
            }
        } else {
            state
        }

        is NavigationAction.RouteRequestStarted -> if (isCurrent(state, action.requestGeneration)) {
            when (val phase = state.phase) {
                is NavigationPhase.Planning -> if (phase.destination != null) {
                    state.copy(phase = phase.copy(requestInFlight = true, failure = null))
                } else {
                    state
                }
                else -> state
            }
        } else {
            state
        }

        is NavigationAction.RouteLoaded -> if (isCurrent(state, action.requestGeneration)) {
            state.copy(
                phase = NavigationPhase.RouteReady(
                    plan = action.plan,
                    selectedRouteId = action.plan.alternatives.first().id,
                ),
                arrivalSoc = unknownArrivalSoc(),
            )
        } else {
            state
        }

        is NavigationAction.RouteFailed -> if (isCurrent(state, action.requestGeneration)) {
            when (val phase = state.phase) {
                is NavigationPhase.Planning -> state.copy(
                    phase = phase.copy(requestInFlight = false, failure = action.failure),
                )
                else -> state
            }
        } else {
            state
        }

        is NavigationAction.RerouteStarted -> if (isCurrent(state, action.requestGeneration)) {
            when (val phase = state.phase) {
                is NavigationPhase.Rerouting -> state.copy(
                    phase = phase.copy(attempt = action.attempt, failure = null),
                )
                else -> state
            }
        } else {
            state
        }

        is NavigationAction.RerouteLoaded -> if (isCurrent(state, action.requestGeneration)) {
            when (state.phase) {
                is NavigationPhase.Rerouting -> state.copy(
                    phase = NavigationPhase.Navigating(
                        plan = action.plan,
                        selectedRouteId = action.plan.alternatives.first().id,
                        guidance = null,
                    ),
                    arrivalSoc = unknownArrivalSoc(),
                )
                else -> state
            }
        } else {
            state
        }

        is NavigationAction.RerouteFailed -> if (isCurrent(state, action.requestGeneration)) {
            when (val phase = state.phase) {
                is NavigationPhase.Rerouting -> state.copy(phase = phase.copy(failure = action.failure))
                else -> state
            }
        } else {
            state
        }

        is NavigationAction.AlternativeSelected -> state.copy(phase = state.phase.selectAlternative(action.routeId))

        NavigationAction.StartNavigation -> when (val phase = state.phase) {
            is NavigationPhase.RouteReady -> state.copy(
                phase = NavigationPhase.Navigating(
                    plan = phase.plan,
                    selectedRouteId = phase.selectedRouteId,
                    guidance = null,
                ),
            )
            else -> state
        }

        is NavigationAction.GuidanceUpdated -> when (val phase = state.phase) {
            is NavigationPhase.Navigating -> state.copy(
                phase = phase.copy(guidance = action.guidance),
            )
            else -> state
        }

        is NavigationAction.BeginRerouting -> when (val phase = state.phase) {
            is NavigationPhase.Navigating -> state.copy(
                phase = NavigationPhase.Rerouting(
                    plan = phase.plan,
                    selectedRouteId = phase.selectedRouteId,
                    attempt = action.attempt,
                    failure = null,
                ),
                requestGeneration = nextGeneration(state),
                arrivalSoc = unknownArrivalSoc(),
            )
            else -> state
        }

        NavigationAction.Arrived -> when (val phase = state.phase) {
            is NavigationPhase.Navigating -> state.copy(
                phase = NavigationPhase.Arrived(phase.plan, phase.selectedRouteId),
            )
            else -> state
        }

        NavigationAction.GuidanceCleared -> when (val phase = state.phase) {
            is NavigationPhase.Navigating -> state.copy(phase = phase.copy(guidance = null))
            else -> state
        }

        NavigationAction.RequestsCancelled -> state.copy(
            phase = when (val phase = state.phase) {
                is NavigationPhase.Planning -> phase.copy(requestInFlight = false)
                else -> phase
            },
            requestGeneration = nextGeneration(state),
        )

        NavigationAction.LifecycleLocationUnavailable -> state.copy(
            phase = when (val phase = state.phase) {
                is NavigationPhase.Navigating -> phase.copy(guidance = null)
                is NavigationPhase.Planning -> phase.copy(requestInFlight = false)
                else -> phase
            },
            locationStatus = LocationUiStatus.NOT_REQUESTED,
            arrivalSoc = unknownArrivalSoc(),
            requestGeneration = nextGeneration(state),
        )

        NavigationAction.StopNavigation -> state.copy(
            phase = NavigationPhase.Idle,
            locationStatus = LocationUiStatus.NOT_REQUESTED,
            arrivalSoc = unknownArrivalSoc(),
            followState = RideMapFollowState(),
            requestGeneration = nextGeneration(state),
        )

        is NavigationAction.LocationStatusChanged -> state.copy(locationStatus = action.status)
        is NavigationAction.ArrivalSocChanged -> state.copy(arrivalSoc = action.estimate)
        is NavigationAction.CameraGesture -> state.copy(
            followState = ru.sodovaya.volty.presentation.map.onRideMapCameraMoveStarted(
                state = state.followState,
                origin = ru.sodovaya.volty.presentation.map.RideMapCameraMoveOrigin.GESTURE,
                nowMillis = action.nowElapsedMillis,
            ),
        )
        NavigationAction.RecenterRequested -> state.copy(
            followState = ru.sodovaya.volty.presentation.map.recenterRideMap(state.followState),
        )
    }

    private fun NavigationPhase.selectAlternative(routeId: String): NavigationPhase = when (this) {
        is NavigationPhase.RouteReady -> if (plan.alternatives.any { it.id == routeId }) {
            copy(selectedRouteId = routeId)
        } else {
            this
        }
        is NavigationPhase.Navigating -> if (plan.alternatives.any { it.id == routeId }) {
            copy(selectedRouteId = routeId, guidance = null)
        } else {
            this
        }
        is NavigationPhase.Rerouting -> if (plan.alternatives.any { it.id == routeId }) {
            copy(selectedRouteId = routeId)
        } else {
            this
        }
        is NavigationPhase.Arrived -> if (plan.alternatives.any { it.id == routeId }) {
            copy(selectedRouteId = routeId)
        } else {
            this
        }
        else -> this
    }

    private fun emptyPlanning() = NavigationPhase.Planning(
        query = "",
        searchResults = emptyList(),
        destination = null,
        requestInFlight = false,
        failure = null,
    )

    private fun unknownArrivalSoc() = ArrivalSocEstimate.Unknown(ArrivalSocUnknownReason.NO_ROUTE)

    private fun nextGeneration(state: LightNavigationState): Long = state.requestGeneration + 1L

    private fun isCurrent(state: LightNavigationState, requestGeneration: Long): Boolean =
        state.requestGeneration == requestGeneration
}
