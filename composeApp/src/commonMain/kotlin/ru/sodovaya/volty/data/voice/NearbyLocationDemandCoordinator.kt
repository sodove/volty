package ru.sodovaya.volty.data.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.sodovaya.volty.domain.location.RideLocationRepository
import ru.sodovaya.volty.domain.location.LocationConsumer

internal class NearbyLocationDemandTicket internal constructor(private val authority: Any) {
    internal var retired=false
        private set
    internal fun belongsTo(authority: Any)=this.authority===authority
    internal fun retire() {retired=true}
}

/** Single process owner for v2 demands; mutations stay serialized through native awaits. */
internal class NearbyLocationDemandCoordinator(private val repository: RideLocationRepository) {
    private val authority=Any()
    private val mutex=Mutex()
    private val demands=mutableSetOf<NearbyLocationDemandTicket>()
    private val pending=MutableStateFlow(false)
    val cleanupPending=pending.asStateFlow()
    // Allocate before positive work, so cancelled/partly committed native work stays revocable.
    fun newTicket()=NearbyLocationDemandTicket(authority)
    internal fun owns(repository: RideLocationRepository)=this.repository===repository
    suspend fun acquire(ticket: NearbyLocationDemandTicket): Boolean {
        require(ticket.belongsTo(authority)) {"Location demand belongs to another coordinator"}
        currentCoroutineContext().ensureActive()
        return mutex.withLock {
            currentCoroutineContext().ensureActive()
            if(ticket.retired) return@withLock false
            if(ticket in demands) return@withLock true
            demands+=ticket
            try {
                repository.setDemand(LocationConsumer.NEARBY_CHANNEL_SHARING,true)
                currentCoroutineContext().ensureActive()
                true
            } catch(failure: Throwable) {
                ticket.retire();updatePending();throw failure
            }
        }
    }
    suspend fun release(ticket: NearbyLocationDemandTicket): Boolean {
        require(ticket.belongsTo(authority)) {"Location demand belongs to another coordinator"}
        return withContext(NonCancellable) {
            mutex.withLock {
                ticket.retire();updatePending()
                if(ticket !in demands) return@withLock true
                if(demands.any {it!==ticket && !it.retired}) {
                    demands-=ticket;updatePending();return@withLock true
                }
                try {
                    repository.setDemand(LocationConsumer.NEARBY_CHANNEL_SHARING,false)
                    demands-=ticket;updatePending();true
                } catch(_: Throwable) {
                    // Keep this exact original owner until a native stop succeeds or a new
                    // admitted owner takes responsibility for the still-shared registration.
                    updatePending();false
                }
            }
        }
    }
    private fun updatePending() {pending.value=demands.any {it.retired}}
}
