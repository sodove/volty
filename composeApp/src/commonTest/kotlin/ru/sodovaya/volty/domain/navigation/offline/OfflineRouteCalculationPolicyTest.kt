package ru.sodovaya.volty.domain.navigation.offline

import kotlin.test.Test
import kotlin.test.assertEquals

class OfflineRouteCalculationPolicyTest {
    @Test
    fun route_budget_allows_three_alternatives_with_a_bounded_runtime() {
        val budget = OfflineRouteCalculationPolicy.routeBudget(alternativesLimit = 3)

        assertEquals(3, budget.maxAlternatives)
        assertEquals(10_000L, budget.maxRuntimeMillis)
    }

    @Test
    fun route_budget_respects_a_smaller_requested_limit() {
        val budget = OfflineRouteCalculationPolicy.routeBudget(alternativesLimit = 1)

        assertEquals(1, budget.maxAlternatives)
    }

    @Test
    fun route_budget_clamps_invalid_limits_to_supported_range() {
        assertEquals(1, OfflineRouteCalculationPolicy.routeBudget(0).maxAlternatives)
        assertEquals(3, OfflineRouteCalculationPolicy.routeBudget(99).maxAlternatives)
    }
}
