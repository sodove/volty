package ru.sodovaya.volty.domain.navigation.routing

import kotlinx.coroutines.flow.Flow
import ru.sodovaya.volty.domain.navigation.NavigationFailure
import ru.sodovaya.volty.domain.navigation.RouteAlternative
import ru.sodovaya.volty.domain.navigation.RoutePlan
import ru.sodovaya.volty.domain.navigation.RouteRequest

/** The rider's route preference; it is intentionally independent of vehicle type. */
enum class RouteStyle {
    FAST_WITH_HIGHWAYS,
    FAST_WITHOUT_HIGHWAYS,
    CURVY,
    MAX_CURVY_TOURING,
}

data class RoutingPreferences(
    val declaredTopSpeedKph: Int = 50,
    val avoidUnpaved: Boolean = false,
    val avoidTolls: Boolean = false,
    val avoidFerries: Boolean = false,
) {
    init {
        require(declaredTopSpeedKph in MIN_TOP_SPEED_KPH..MAX_TOP_SPEED_KPH) {
            "Declared top speed must be between $MIN_TOP_SPEED_KPH and $MAX_TOP_SPEED_KPH km/h"
        }
    }

    private companion object {
        const val MIN_TOP_SPEED_KPH = 20
        const val MAX_TOP_SPEED_KPH = 130
    }
}

enum class RouteSource {
    OFFLINE,
    ONLINE,
    ONLINE_FALLBACK,
}

sealed interface RoutePlanningUpdate {
    data object PlanningStarted : RoutePlanningUpdate

    data class PrimaryRouteReady(
        val plan: RoutePlan,
        val source: RouteSource,
    ) : RoutePlanningUpdate {
        init {
            require(plan.alternatives.size == 1) {
                "PrimaryRouteReady must contain exactly one route"
            }
        }
    }

    data class AlternativeAdded(
        val route: RouteAlternative,
        val source: RouteSource,
    ) : RoutePlanningUpdate

    data object PlanningCompleted : RoutePlanningUpdate

    data class CoverageRequired(val regionIds: List<String>) : RoutePlanningUpdate

    data class FallbackActivated(val reason: NavigationFailure) : RoutePlanningUpdate

    data class PlanningFailed(val failure: NavigationFailure) : RoutePlanningUpdate
}

interface RoutePlanner {
    fun plan(request: RouteRequest): Flow<RoutePlanningUpdate>
}
