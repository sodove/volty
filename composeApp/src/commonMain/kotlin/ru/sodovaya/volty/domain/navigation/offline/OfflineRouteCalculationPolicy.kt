package ru.sodovaya.volty.domain.navigation.offline

data class OfflineRouteCalculationBudget(
    val maxAlternatives: Int,
    val maxRuntimeMillis: Long,
)

object OfflineRouteCalculationPolicy {
    private const val FIRST_RESULT_ALTERNATIVES = 1
    private const val FIRST_RESULT_RUNTIME_MILLIS = 10_000L

    fun firstResultBudget(): OfflineRouteCalculationBudget = OfflineRouteCalculationBudget(
        maxAlternatives = FIRST_RESULT_ALTERNATIVES,
        maxRuntimeMillis = FIRST_RESULT_RUNTIME_MILLIS,
    )
}
