package ru.sodovaya.volty.domain.navigation.offline

import kotlin.test.Test
import kotlin.test.assertEquals

class OfflineRouteCalculationPolicyTest {
    @Test
    fun first_result_uses_one_alternative_and_a_bounded_runtime() {
        val budget = OfflineRouteCalculationPolicy.firstResultBudget()

        assertEquals(1, budget.maxAlternatives)
        assertEquals(10_000L, budget.maxRuntimeMillis)
    }
}
