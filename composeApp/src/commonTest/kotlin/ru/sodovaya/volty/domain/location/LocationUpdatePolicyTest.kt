package ru.sodovaya.volty.domain.location

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class LocationUpdatePolicyTest {
    @Test fun nearbyChannelRequestsFreshFixesEvenWhileStationary() {
        val request=LocationUpdatePolicy.requestFor(setOf(LocationConsumer.NEARBY_CHANNEL_SHARING))
        assertEquals(1_000L,request.intervalMillis);assertEquals(0f,request.minDistanceMeters)
    }
    @Test fun nearbyDemandChangesEffectiveMapRequestButPreservesActiveNavigationRequest() {
        val map=setOf(LocationConsumer.MAP)
        val nearby=map+LocationConsumer.NEARBY_CHANNEL_SHARING
        assertNotEquals(LocationUpdatePolicy.requestFor(map),LocationUpdatePolicy.requestFor(nearby))
        assertEquals(LocationUpdatePolicy.requestFor(nearby),LocationUpdatePolicy.requestFor(nearby+LocationConsumer.NAVIGATION))
        assertEquals(LocationUpdatePolicy.requestFor(map+LocationConsumer.NAVIGATION),LocationUpdatePolicy.requestFor(nearby+LocationConsumer.NAVIGATION))
    }
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
