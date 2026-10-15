package ru.sodovaya.volty.data.location

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.sodovaya.volty.domain.location.RideLocationRepository
import ru.sodovaya.volty.domain.location.LocationConsumer

internal class LocationDemandTicket internal constructor(private val authority: Any) {
    internal var retired=false
        private set
    internal fun belongsTo(authority: Any)=this.authority===authority
    internal fun retire() {retired=true}
}

/** One consumer's exact native owners; mutations stay serialized through native awaits. */
internal class LocationDemandCoordinator(
    private val repository: RideLocationRepository,
    private val consumer: LocationConsumer,
) {
    private val authority=Any()
    private val mutex=Mutex()
    private val demands=mutableSetOf<LocationDemandTicket>()
    private val pending=MutableStateFlow(false)
    val cleanupPending=pending.asStateFlow()
    private val held=MutableStateFlow(false)
    /** Conservative native/in-flight ownership; false only after original release ACK. */
    val nativeDemandHeld=held.asStateFlow()
    // Allocate before positive work, so cancelled/partly committed native work stays revocable.
    fun newTicket()=LocationDemandTicket(authority)
    internal fun isFor(consumer: LocationConsumer) = this.consumer == consumer
    internal fun owns(repository: RideLocationRepository)=this.repository===repository
    suspend fun acquire(ticket: LocationDemandTicket): Boolean {
        require(ticket.belongsTo(authority)) {"Location demand belongs to another coordinator"}
        currentCoroutineContext().ensureActive()
        return mutex.withLock {
            currentCoroutineContext().ensureActive()
            if(ticket.retired) return@withLock false
            // Retraction: a cached ticket cannot prove that a shared native listener survived
            // another consumer's cadence restart or a failed platform reconciliation.
            demands+=ticket
            updatePending()
            try {
                repository.setDemand(consumer,true)
                currentCoroutineContext().ensureActive()
                true
            } catch(failure: Throwable) {
                ticket.retire();updatePending();throw failure
            }
        }
    }
    suspend fun release(ticket: LocationDemandTicket): Boolean {
        require(ticket.belongsTo(authority)) {"Location demand belongs to another coordinator"}
        return withContext(NonCancellable) {
            mutex.withLock {
                ticket.retire();updatePending()
                if(ticket !in demands) return@withLock true
                if(demands.any {it!==ticket && !it.retired}) {
                    demands-=ticket;updatePending();return@withLock true
                }
                try {
                    repository.setDemand(consumer,false)
                    demands-=ticket;updatePending();true
                } catch(_: Throwable) {
                    // Keep this exact original owner until a native stop succeeds or a new
                    // admitted owner takes responsibility for the still-shared registration.
                    updatePending();false
                }
            }
        }
    }
    private fun updatePending() {
        pending.value=demands.any {it.retired}
        // Includes in-flight and failed partial acquisition. Desired repository state can
        // already be empty while original native removal still needs retrying.
        held.value=demands.isNotEmpty()
    }
}
