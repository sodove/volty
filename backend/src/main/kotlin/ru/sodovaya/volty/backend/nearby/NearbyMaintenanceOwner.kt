package ru.sodovaya.volty.backend.nearby

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Reported work is an attempt, never proof of remote cleanup acknowledgement. */
interface NearbyMaintenancePort {
    fun reconcileSession(): Boolean
    fun reconcileWebhook(): Boolean
}
internal data class NearbyMaintenanceState(val wanted: Boolean=true,val producerPending: Boolean=true,
    val inFlight: Int=0,val failures: Long=0)

/** Serial bounded slices; borrowed root/ports remain owned by the application. */
internal class NearbyMaintenanceOwner private constructor(parent: CoroutineScope,dispatcher: CoroutineDispatcher,
    pulses: Flow<Unit>,private val port: NearbyMaintenancePort) {
    private val root=checkNotNull(parent.coroutineContext[Job])
    private val lifetime=SupervisorJob(root)
    private val scope=CoroutineScope(parent.coroutineContext+lifetime+dispatcher)
    private val mutableState=MutableStateFlow(NearbyMaintenanceState())
    val state=mutableState.asStateFlow()
    private val mutableStopped=MutableStateFlow(false)
    val stopped=mutableStopped.asStateFlow()
    private var guardian: Job?=null
    private val runner=scope.launch(start=CoroutineStart.LAZY) {
        pulses.collect {
            attempt(port::reconcileSession)
            attempt(port::reconcileWebhook)
        }
    }
    init {
        guardian=parent.launch(start=CoroutineStart.UNDISPATCHED) {
            try {awaitCancellation()} finally {requestStop()}
        }
        runner.invokeOnCompletion {
            mutableState.update {it.copy(wanted=false,producerPending=false)}
            mutableStopped.value=true
            guardian?.cancel();lifetime.complete()
        }
        runner.start()
    }
    private suspend fun attempt(work: ()->Boolean) {
        currentCoroutineContext().ensureActive()
        if(!mutableState.value.wanted || !root.isActive) throw CancellationException("Nearby maintenance stopped")
        mutableState.update {it.copy(inFlight=it.inFlight+1)}
        try {work()}
        catch(cancelled: CancellationException) {throw cancelled}
        catch(_: Exception) {mutableState.update {it.copy(failures=if(it.failures==Long.MAX_VALUE) it.failures else it.failures+1)}}
        finally {mutableState.update {it.copy(inFlight=it.inFlight-1)}}
    }
    fun requestStop() {mutableState.update {it.copy(wanted=false)};lifetime.cancel()}
    /** A blocking admitted producer remains pending even when its Job is already cancelled. */
    fun close(): Boolean {requestStop();return runner.isCompleted}
    companion object {
        fun start(parent: CoroutineScope,pulses: Flow<Unit>,port: NearbyMaintenancePort,
            dispatcher: CoroutineDispatcher=Dispatchers.IO): NearbyMaintenanceOwner {
            check(checkNotNull(parent.coroutineContext[Job]).isActive) {"Actual application root required"}
            return NearbyMaintenanceOwner(parent,dispatcher,pulses,port)
        }
    }
}
