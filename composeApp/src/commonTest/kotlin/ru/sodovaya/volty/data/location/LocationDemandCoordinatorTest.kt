package ru.sodovaya.volty.data.location

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import ru.sodovaya.volty.domain.location.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class LocationDemandCoordinatorTest {
    @Test fun history_and_nearby_native_owners_are_independent() = runTest {
        val repository = Repository()
        val history = LocationDemandCoordinator(repository, LocationConsumer.HISTORY_RECORDING)
        val nearby = LocationDemandCoordinator(repository, LocationConsumer.NEARBY_CHANNEL_SHARING)
        val h = history.newTicket()
        val n = nearby.newTicket()
        assertTrue(history.acquire(h))
        assertTrue(nearby.acquire(n))
        assertTrue(history.release(h))
        assertEquals(setOf(LocationConsumer.NAVIGATION, LocationConsumer.NEARBY_CHANNEL_SHARING), repository.state.value.demands)
        assertFalse(history.nativeDemandHeld.value)
        assertTrue(nearby.nativeDemandHeld.value)
        assertFailsWith<IllegalArgumentException> { nearby.release(h) }
        assertTrue(nearby.release(n))
        assertFalse(nearby.nativeDemandHeld.value)
        assertEquals(setOf(LocationConsumer.NAVIGATION), repository.state.value.demands)
    }

    @Test fun partial_positive_is_held_before_native_ack_and_after_caller_cancellation() = runTest {
        val repository = Repository()
        repository.startGate = CompletableDeferred()
        val owner = LocationDemandCoordinator(repository, LocationConsumer.HISTORY_RECORDING)
        val ticket = owner.newTicket()
        val start = async { owner.acquire(ticket) }
        try {
            runCurrent()
            assertEquals(listOf(LocationConsumer.HISTORY_RECORDING to true), repository.issued)
            assertFalse(start.isCompleted)
            assertTrue(owner.nativeDemandHeld.value, "FGS cannot be released while acquisition is in flight")
            start.cancel()
            repository.startGate!!.complete(Unit)
            runCurrent()
            assertTrue(start.isCancelled)
            assertTrue(owner.cleanupPending.value)
            assertTrue(owner.nativeDemandHeld.value)
            assertTrue(owner.release(ticket))
            assertFalse(owner.nativeDemandHeld.value)
        } finally {
            repository.startGate!!.complete(Unit)
            start.cancelAndJoin()
            owner.release(ticket)
        }
    }

    @Test fun failed_native_negative_retains_hold_even_after_desired_consumer_disappears() = runTest {
        val repository = Repository()
        val owner = LocationDemandCoordinator(repository, LocationConsumer.HISTORY_RECORDING)
        val ticket = owner.newTicket()
        owner.acquire(ticket)
        repository.failStop = true
        assertFalse(owner.release(ticket))
        assertEquals(setOf(LocationConsumer.NAVIGATION), repository.state.value.demands)
        assertTrue(owner.cleanupPending.value)
        assertTrue(owner.nativeDemandHeld.value, "Desired policy removal is not native release ACK")
        repository.failStop = false
        assertTrue(owner.release(ticket))
        assertFalse(owner.cleanupPending.value)
        assertFalse(owner.nativeDemandHeld.value)
        assertEquals(listOf(true, false, false), repository.issued.map { it.second })
    }

    @Test fun native_hold_spans_awaited_release_and_serialized_successor_acquisition() = runTest {
        val repository = Repository()
        val owner = LocationDemandCoordinator(repository, LocationConsumer.HISTORY_RECORDING)
        val first = owner.newTicket()
        val next = owner.newTicket()
        owner.acquire(first)
        repository.stopGate = CompletableDeferred()
        val stop = async { owner.release(first) }
        val start = async { owner.acquire(next) }
        try {
            runCurrent()
            assertEquals(listOf(true, false), repository.issued.map { it.second })
            assertFalse(stop.isCompleted)
            assertFalse(start.isCompleted)
            assertTrue(owner.nativeDemandHeld.value)
            repository.stopGate!!.complete(Unit)
            assertTrue(stop.await())
            assertTrue(start.await())
            assertTrue(owner.nativeDemandHeld.value)
            assertTrue(owner.release(first))
            assertTrue(owner.nativeDemandHeld.value, "Old duplicate cleanup cannot release successor")
            assertTrue(owner.release(next))
            assertFalse(owner.nativeDemandHeld.value)
        } finally {
            repository.stopGate!!.complete(Unit)
            stop.cancelAndJoin()
            start.cancelAndJoin()
            owner.release(first)
            owner.release(next)
        }
    }

    @Test fun repeated_positive_requires_new_native_ack_and_failure_remains_originally_owned() = runTest {
        val repository = Repository()
        val owner = LocationDemandCoordinator(repository, LocationConsumer.HISTORY_RECORDING)
        val ticket = owner.newTicket()
        assertTrue(owner.acquire(ticket))
        repository.failStart = true
        assertFailsWith<IllegalStateException> { owner.acquire(ticket) }
        assertEquals(listOf(true, true), repository.issued.map { it.second })
        assertTrue(owner.cleanupPending.value)
        assertTrue(owner.nativeDemandHeld.value)
        assertFalse(owner.acquire(ticket))
        assertTrue(owner.release(ticket))
        assertFalse(owner.nativeDemandHeld.value)
    }

    private class Repository : RideLocationRepository {
        override val requiredPermissions = emptyList<String>()
        override val state = MutableStateFlow(RideLocationState(demands = setOf(LocationConsumer.NAVIGATION)))
        val issued = mutableListOf<Pair<LocationConsumer, Boolean>>()
        var startGate: CompletableDeferred<Unit>? = null
        var stopGate: CompletableDeferred<Unit>? = null
        var failStart = false
        var failStop = false
        override suspend fun refreshPermissionAndProviders() = Unit
        override suspend fun setDemand(consumer: LocationConsumer, enabled: Boolean) {
            issued += consumer to enabled
            if (enabled) withContext(NonCancellable) { startGate?.await() } else stopGate?.await()
            state.value = state.value.copy(demands = if (enabled) state.value.demands + consumer else state.value.demands - consumer)
            if (enabled && failStart) error("Native positive failed after side effect")
            if (!enabled && failStop) error("Native negative failed after desired removal")
        }
    }
}
