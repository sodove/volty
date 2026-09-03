package ru.sodovaya.volty.domain.location

import kotlin.test.Test
import kotlin.test.assertEquals

class LocationUpdatePolicyTest {
    @Test
    fun `ordinary request is selected without navigation demand`() {
        val request = LocationUpdatePolicy.requestFor(
            demands = setOf(LocationConsumer.MAP, LocationConsumer.SOCIAL_SHARING),
        )

        assertEquals(1_000L, request.intervalMillis)
        assertEquals(5f, request.minDistanceMeters)
    }

    @Test
    fun `navigation request is selected when navigation demand is active`() {
        val request = LocationUpdatePolicy.requestFor(
            demands = setOf(LocationConsumer.MAP, LocationConsumer.NAVIGATION),
        )

        assertEquals(1_000L, request.intervalMillis)
        assertEquals(0f, request.minDistanceMeters)
    }

    @Test
    fun `ordinary updates keep the distance filter`() {
        val request = LocationUpdatePolicy.ordinaryRequest

        assertEquals(1_000L, request.intervalMillis)
        assertEquals(5f, request.minDistanceMeters)
    }

    @Test
    fun `navigation asks for callbacks even while the rider is stationary`() {
        val request = LocationUpdatePolicy.navigationRequest

        assertEquals(1_000L, request.intervalMillis)
        assertEquals(0f, request.minDistanceMeters)
    }
}
