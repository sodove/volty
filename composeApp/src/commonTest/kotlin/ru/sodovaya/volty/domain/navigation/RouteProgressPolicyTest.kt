package ru.sodovaya.volty.domain.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RouteProgressPolicyTest {
    @Test
    fun defaults_match_the_navigation_trust_contract() {
        assertEquals(5_000L, defaultRouteProgressPolicy.freshFixMaxAgeMillis)
        assertEquals(50.0, defaultRouteProgressPolicy.maxAccuracyMeters)
        assertEquals(30.0, defaultRouteProgressPolicy.minimumOffRouteDistanceMeters)
        assertEquals(3, defaultRouteProgressPolicy.offRouteConfirmationFixes)
        assertEquals(2_000L, defaultRouteProgressPolicy.offRouteConfirmationWindowMillis)
        assertEquals(40.0, defaultRouteProgressPolicy.arrivalRemainingDistanceMeters)
        assertEquals(25.0, defaultRouteProgressPolicy.arrivalDestinationDistanceMeters)
        assertEquals(2, defaultRouteProgressPolicy.arrivalConfirmationFixes)
        assertEquals(30.0, defaultRouteProgressPolicy.backwardsProgressToleranceMeters)

        assertEquals(30.0, defaultRouteProgressPolicy.offRouteThreshold(5.0))
        assertEquals(40.0, defaultRouteProgressPolicy.offRouteThreshold(20.0))
    }

    @Test
    fun policy_rejects_non_positive_or_incoherent_thresholds() {
        assertFailsWith<IllegalArgumentException> {
            RouteProgressPolicy(freshFixMaxAgeMillis = 0L)
        }
        assertFailsWith<IllegalArgumentException> {
            RouteProgressPolicy(maxAccuracyMeters = 0.0)
        }
        assertFailsWith<IllegalArgumentException> {
            RouteProgressPolicy(offRouteConfirmationFixes = 1)
        }
        assertFailsWith<IllegalArgumentException> {
            RouteProgressPolicy(offRouteConfirmationWindowMillis = 0L)
        }
        assertFailsWith<IllegalArgumentException> {
            RouteProgressPolicy(arrivalConfirmationFixes = 1)
        }
        assertFailsWith<IllegalArgumentException> {
            RouteProgressPolicy(backwardsProgressToleranceMeters = -1.0)
        }
    }
}
