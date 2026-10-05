package ru.sodovaya.volty.data.voice

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import ru.sodovaya.volty.domain.location.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class NearbyLocationDemandCoordinatorTest {
    private class Repository : RideLocationRepository {
        override val requiredPermissions=emptyList<String>()
        override val state=MutableStateFlow(RideLocationState(demands=setOf(LocationConsumer.NAVIGATION)))
        val issued=mutableListOf<Boolean>()
        var stopRelease: CompletableDeferred<Unit>?=null
        var startRelease: CompletableDeferred<Unit>?=null
        var failStop=false
        override suspend fun refreshPermissionAndProviders()=Unit
        override suspend fun setDemand(consumer: LocationConsumer,enabled: Boolean) {
            assertEquals(LocationConsumer.NEARBY_CHANNEL_SHARING,consumer);issued+=enabled
            if(enabled) withContext(NonCancellable) {startRelease?.await()} else stopRelease?.await()
            state.value=state.value.copy(demands=if(enabled) state.value.demands+consumer else state.value.demands-consumer)
            if(!enabled && failStop) error("Native stop failed after side effect")
        }
    }
    @Test fun exactTicketsPreserveOtherOwnersAndNegativeBeforePositiveCannotResurrect()=runTest {
        val repository=Repository();val coordinator=NearbyLocationDemandCoordinator(repository)
        val first=coordinator.newTicket();val second=coordinator.newTicket();val never=coordinator.newTicket()
        assertTrue(coordinator.release(never));assertFalse(coordinator.acquire(never));assertTrue(repository.issued.isEmpty())
        assertTrue(coordinator.acquire(first));assertTrue(coordinator.acquire(second));assertTrue(coordinator.release(first))
        assertTrue(LocationConsumer.NEARBY_CHANNEL_SHARING in repository.state.value.demands)
        assertTrue(coordinator.release(first));assertTrue(coordinator.release(second))
        assertEquals(setOf(LocationConsumer.NAVIGATION),repository.state.value.demands)
        assertEquals(listOf(true,true,false),repository.issued)
        val foreign=NearbyLocationDemandCoordinator(repository)
        assertFailsWith<IllegalArgumentException> {foreign.acquire(first)}
        assertFailsWith<IllegalArgumentException> {foreign.release(first)}
    }
    @Test fun heldOldNativeStopCannotRunAfterNewTicketActivation()=runTest {
        val repository=Repository();val coordinator=NearbyLocationDemandCoordinator(repository)
        val first=coordinator.newTicket();val second=coordinator.newTicket();assertTrue(coordinator.acquire(first))
        repository.stopRelease=CompletableDeferred();val stop=async {coordinator.release(first)};runCurrent()
        val start=async {coordinator.acquire(second)};runCurrent();assertEquals(listOf(true,false),repository.issued);assertFalse(start.isCompleted)
        repository.stopRelease!!.complete(Unit);assertTrue(stop.await());assertTrue(start.await())
        assertEquals(listOf(true,false,true),repository.issued)
        assertTrue(coordinator.release(first));assertTrue(LocationConsumer.NEARBY_CHANNEL_SHARING in repository.state.value.demands)
        assertTrue(coordinator.release(second));assertEquals(setOf(LocationConsumer.NAVIGATION),repository.state.value.demands)
    }
    @Test fun cancelledActivationRemainsOriginallyOwnedAndFailedStopRemainsRetryable()=runTest {
        val repository=Repository();val coordinator=NearbyLocationDemandCoordinator(repository);val ticket=coordinator.newTicket()
        repository.startRelease=CompletableDeferred();val start=async {coordinator.acquire(ticket)};runCurrent();assertEquals(listOf(true),repository.issued)
        start.cancel();repository.startRelease!!.complete(Unit);runCurrent();assertTrue(start.isCancelled)
        assertTrue(coordinator.cleanupPending.value);assertTrue(LocationConsumer.NEARBY_CHANNEL_SHARING in repository.state.value.demands)
        repository.failStop=true;assertFalse(coordinator.release(ticket));assertTrue(coordinator.cleanupPending.value)
        assertFalse(coordinator.acquire(ticket))
        repository.failStop=false;assertTrue(coordinator.release(ticket));assertFalse(coordinator.cleanupPending.value)
        assertEquals(setOf(LocationConsumer.NAVIGATION),repository.state.value.demands)
    }
}
