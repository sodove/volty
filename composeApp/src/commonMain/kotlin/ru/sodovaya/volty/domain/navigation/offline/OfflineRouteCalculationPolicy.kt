package ru.sodovaya.volty.domain.navigation.offline

data class OfflineRouteCalculationBudget(
    val maxAlternatives: Int,
    val maxRuntimeMillis: Long,
)

object OfflineRouteCalculationPolicy {
    private const val MAX_ALTERNATIVES = 3
    private const val ROUTE_RUNTIME_MILLIS = 10_000L

    fun routeBudget(alternativesLimit: Int): OfflineRouteCalculationBudget = OfflineRouteCalculationBudget(
        maxAlternatives = alternativesLimit.coerceIn(1, MAX_ALTERNATIVES),
        maxRuntimeMillis = ROUTE_RUNTIME_MILLIS,
    )
}
