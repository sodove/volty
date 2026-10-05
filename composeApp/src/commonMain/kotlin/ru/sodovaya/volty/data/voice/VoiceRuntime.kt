package ru.sodovaya.volty.data.voice

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.sodovaya.volty.domain.voice.*

enum class VoiceSharingEffect { ADD_DEMAND, REMOVE_DEMAND, CLEAR_QUEUE }

/**
 * One account-bound composition. Supply the SAME queued serial dispatcher to all
 * calls, plus a port whose authenticated principal cannot change underneath it.
 * Sharing dispatch must be nonblocking. Parent cancellation is not retirement:
 * explicit retirement keeps cleanup work alive until its actual acknowledgements.
 * No membership mutation, durable persistence or Android foreground ownership here.
 */
class VoiceRuntime(
    parent: CoroutineScope,
    private val queuedDispatcher: CoroutineDispatcher,
    port: VoiceSessionControlPort,
    factory: VoiceSdkSessionFactory,
    pulses: Flow<Unit>,
    private val monotonicMillis: () -> Long,
    private val sharingDispatch: (VoiceSharingEffect) -> Unit,
    private val accountPermission: () -> Boolean = { true },
) {
    private val scope = run {
        require(queuedDispatcher.isDispatchNeeded(parent.coroutineContext)) { "Queued serial dispatcher required" }
        CoroutineScope(parent.coroutineContext + queuedDispatcher)
    }
    private val admission = Mutex()
    private var retiring = false
    private val owner = VoiceControlOwner(scope, port, ::dispatch)
    private val engine = VoiceEngineEffectRunner(scope, queuedDispatcher, factory,
        { owner.state.value }, monotonicMillis, owner::event, accountPermission)
    private val scheduler = VoiceControlScheduler(scope, owner, pulses, monotonicMillis,
        { engine.failedCleanup.value }, { engine.dispatch(VoiceControlDispatch(VoiceSessionEffect.StopEngine(it))) })

    val state = owner.state
    val unconfirmedStops = owner.unconfirmedStops
    val pendingRemoteStops = owner.pendingRemoteStops
    val failedLocalCleanup = engine.failedCleanup
    val nativeInFlight = engine.inFlight
    val controlInFlight = owner.inFlight

    /** False means admission has permanently retired; no new operation is sent. */
    suspend fun join(channel: String, operation: VoiceOperationId, replace: Boolean = false): Boolean = command {
        if (retiring) false else { owner.join(channel, operation, replace); true }
    }

    suspend fun disconnect() = command { owner.stop() }
    suspend fun setMuted(value: Boolean) = command {
        if (!retiring) owner.event(VoiceSessionEvent.SetMuted(value))
    }
    suspend fun setDeafened(value: Boolean) = command {
        if (!retiring) owner.event(VoiceSessionEvent.SetDeafened(value))
    }
    /** Production controls carry the rendered operation through the bounded mailbox. */
    suspend fun setMuted(operation: VoiceOperationId, value: Boolean) = command {
        if (!retiring) owner.event(VoiceSessionEvent.SetMuted(value, operation))
    }
    suspend fun setDeafened(operation: VoiceOperationId, value: Boolean) = command {
        if (!retiring) owner.event(VoiceSessionEvent.SetDeafened(value, operation))
    }
    suspend fun enableSharing() = command {
        val state = owner.state.value
        val tag = state.sessionId?.let { VoiceSessionTag(it, state.generation) }
        if (!retiring && tag != null && state.operationId != null && state.authorizesEngine(tag, monotonicMillis()))
            owner.event(VoiceSessionEvent.EnableSharing(state.operationId))
    }
    suspend fun hidePosition() = command { owner.event(VoiceSessionEvent.HidePosition) }

    /** Stop admitting new intent, while original HTTP/native cleanup remains retryable. */
    suspend fun beginRetirement() = command {
        retiring = true
        owner.stop()
    }

    private suspend fun <T> command(block: suspend () -> T): T = withContext(queuedDispatcher) {
        // Preserve command ordering even when the bounded owner mailbox suspends.
        admission.withLock { block() }
    }

    private fun dispatch(command: VoiceControlDispatch) {
        when (command.effect) {
            is VoiceSessionEffect.StartEngine, is VoiceSessionEffect.StopEngine,
            is VoiceSessionEffect.SetMuted, is VoiceSessionEffect.SetDeafened -> engine.dispatch(command)
            VoiceSessionEffect.AddNearbyDemand -> sharingDispatch(VoiceSharingEffect.ADD_DEMAND)
            VoiceSessionEffect.RemoveNearbyDemand -> sharingDispatch(VoiceSharingEffect.REMOVE_DEMAND)
            VoiceSessionEffect.ClearSharingQueue -> sharingDispatch(VoiceSharingEffect.CLEAR_QUEUE)
            else -> error("Control effect escaped owner")
        }
    }
}
