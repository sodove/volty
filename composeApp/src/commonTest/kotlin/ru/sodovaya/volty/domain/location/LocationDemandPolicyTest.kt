package ru.sodovaya.volty.domain.location

import ru.sodovaya.volty.domain.navigation.GeoCoordinate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocationDemandPolicyTest {
    @Test
    fun removing_map_and_nearby_demands_preserves_history_recording_ownership() {
        var state = LocationDemandPolicy.initialState
        state = LocationDemandPolicy.setDemand(state, LocationConsumer.HISTORY_RECORDING, enabled = true).state
        state = LocationDemandPolicy.setDemand(state, LocationConsumer.MAP, enabled = true).state
        state = LocationDemandPolicy.setDemand(state, LocationConsumer.NEARBY_CHANNEL_SHARING, enabled = true).state

        val withoutMap = LocationDemandPolicy.setDemand(state, LocationConsumer.MAP, enabled = false)
        val withoutNearby = LocationDemandPolicy.setDemand(
            withoutMap.state,
            LocationConsumer.NEARBY_CHANNEL_SHARING,
            enabled = false,
        )

        assertEquals(setOf(LocationConsumer.HISTORY_RECORDING), withoutNearby.state.demands)
        assertFalse(withoutMap.shouldStop)
        assertFalse(withoutNearby.shouldStop)
        assertEquals(RideLocationStatus.Searching, withoutNearby.state.status)
    }

    @Test
    fun removing_final_history_owner_stops_registration_and_rejects_old_generation_callback() {
        val started = LocationDemandPolicy.setDemand(
            LocationDemandPolicy.initialState,
            LocationConsumer.HISTORY_RECORDING,
            enabled = true,
        )
        val stopped = LocationDemandPolicy.setDemand(
            started.state,
            LocationConsumer.HISTORY_RECORDING,
            enabled = false,
        )

        val lateFix = LocationDemandPolicy.acceptFix(
            state = stopped.state,
            callbackGeneration = started.state.generation,
            fix = testFix(source = LocationSource.GPS, elapsedRealtimeMillis = 100L),
        )

        assertTrue(started.shouldStart)
        assertTrue(stopped.shouldStop)
        assertTrue(stopped.state.generation > started.state.generation)
        assertEquals(emptySet(), stopped.state.demands)
        assertEquals(RideLocationStatus.NotRequested, stopped.state.status)
        assertFalse(lateFix.accepted)
        assertEquals(stopped.state, lateFix.state)
    }

    @Test
    fun stopping_map_does_not_stop_navigation_or_social_sharing() {
        var state = LocationDemandPolicy.initialState
        state = LocationDemandPolicy.setDemand(state, LocationConsumer.MAP, enabled = true).state
        state = LocationDemandPolicy.setDemand(state, LocationConsumer.NAVIGATION, enabled = true).state
        state = LocationDemandPolicy.setDemand(state, LocationConsumer.SOCIAL_SHARING, enabled = true).state

        val transition = LocationDemandPolicy.setDemand(
            state,
            LocationConsumer.MAP,
            enabled = false,
        )

        assertEquals(
            setOf(LocationConsumer.NAVIGATION, LocationConsumer.SOCIAL_SHARING),
            transition.state.demands,
        )
        assertEquals(RideLocationStatus.Searching, transition.state.status)
        assertFalse(transition.shouldStop)
    }

    @Test
    fun duplicate_enable_and_disable_are_idempotent() {
        val firstEnable = LocationDemandPolicy.setDemand(
            LocationDemandPolicy.initialState,
            LocationConsumer.NAVIGATION,
            enabled = true,
        )
        val duplicateEnable = LocationDemandPolicy.setDemand(
            firstEnable.state,
            LocationConsumer.NAVIGATION,
            enabled = true,
        )
        val firstDisable = LocationDemandPolicy.setDemand(
            duplicateEnable.state,
            LocationConsumer.NAVIGATION,
            enabled = false,
        )
        val duplicateDisable = LocationDemandPolicy.setDemand(
            firstDisable.state,
            LocationConsumer.NAVIGATION,
            enabled = false,
        )

        assertTrue(firstEnable.changed)
        assertTrue(firstEnable.shouldStart)
        assertFalse(duplicateEnable.changed)
        assertFalse(duplicateEnable.shouldStart)
        assertEquals(firstEnable.state.generation, duplicateEnable.state.generation)
        assertTrue(firstDisable.changed)
        assertTrue(firstDisable.shouldStop)
        assertFalse(duplicateDisable.changed)
        assertFalse(duplicateDisable.shouldStop)
        assertEquals(firstDisable.state.generation, duplicateDisable.state.generation)
    }

    @Test
    fun no_demand_is_not_requested() {
        val started = LocationDemandPolicy.setDemand(
            LocationDemandPolicy.initialState,
            LocationConsumer.MAP,
            enabled = true,
        )
        val stopped = LocationDemandPolicy.setDemand(
            started.state,
            LocationConsumer.MAP,
            enabled = false,
        )

        assertEquals(emptySet(), stopped.state.demands)
        assertEquals(RideLocationStatus.NotRequested, stopped.state.status)
    }

    @Test
    fun old_generation_callback_is_rejected() {
        val started = LocationDemandPolicy.setDemand(
            LocationDemandPolicy.initialState,
            LocationConsumer.NAVIGATION,
            enabled = true,
        )
        val fix = testFix(source = LocationSource.GPS, elapsedRealtimeMillis = 100L)

        val accepted = LocationDemandPolicy.acceptFix(
            state = started.state,
            callbackGeneration = started.state.generation,
            fix = fix,
        )
        val rejected = LocationDemandPolicy.acceptFix(
            state = accepted.state,
            callbackGeneration = started.state.generation - 1L,
            fix = testFix(source = LocationSource.GPS, elapsedRealtimeMillis = 200L),
        )

        assertTrue(accepted.accepted)
        assertFalse(rejected.accepted)
        assertEquals(RideLocationStatus.Available(fix), rejected.state.status)
    }

    @Test
    fun gps_takes_precedence_after_the_first_accepted_gps_fix() {
        val started = LocationDemandPolicy.setDemand(
            LocationDemandPolicy.initialState,
            LocationConsumer.NAVIGATION,
            enabled = true,
        )
        val network = LocationDemandPolicy.acceptFix(
            state = started.state,
            callbackGeneration = started.state.generation,
            fix = testFix(source = LocationSource.NETWORK, elapsedRealtimeMillis = 100L),
        )
        val gps = LocationDemandPolicy.acceptFix(
            state = network.state,
            callbackGeneration = started.state.generation,
            fix = testFix(source = LocationSource.GPS, elapsedRealtimeMillis = 200L),
        )
        val laterNetwork = LocationDemandPolicy.acceptFix(
            state = gps.state,
            callbackGeneration = started.state.generation,
            fix = testFix(source = LocationSource.NETWORK, elapsedRealtimeMillis = 300L),
        )

        assertTrue(network.accepted)
        assertTrue(gps.accepted)
        assertFalse(laterNetwork.accepted)
        assertEquals(gps.state.status, laterNetwork.state.status)
    }

    @Test
    fun out_of_order_monotonic_fix_is_rejected() {
        val started = LocationDemandPolicy.setDemand(
            LocationDemandPolicy.initialState,
            LocationConsumer.MAP,
            enabled = true,
        )
        val current = LocationDemandPolicy.acceptFix(
            state = started.state,
            callbackGeneration = started.state.generation,
            fix = testFix(source = LocationSource.PASSIVE, elapsedRealtimeMillis = 200L),
        )
        val old = LocationDemandPolicy.acceptFix(
            state = current.state,
            callbackGeneration = started.state.generation,
            fix = testFix(source = LocationSource.PASSIVE, elapsedRealtimeMillis = 199L),
        )

        assertTrue(current.accepted)
        assertFalse(old.accepted)
        assertEquals(current.state.status, old.state.status)
    }

    private fun testFix(source: LocationSource, elapsedRealtimeMillis: Long) = RideLocationFix(
        coordinate = GeoCoordinate(56.8, 60.6),
        accuracyMeters = 5.0,
        speedMetersPerSecond = null,
        bearingDegrees = null,
        capturedAtEpochMillis = elapsedRealtimeMillis,
        elapsedRealtimeMillis = elapsedRealtimeMillis,
        source = source,
    )
}
