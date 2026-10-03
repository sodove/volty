package ru.sodovaya.volty.data.voice

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import ru.sodovaya.volty.domain.voice.*

/** Injected pulses are scheduling only; they cannot acknowledge media or mint lease credit. */
class VoiceControlScheduler(
    scope: CoroutineScope,
    private val owner: VoiceControlOwner,
    pulses: Flow<Unit>,
    private val monotonicMillis: () -> Long,
    private val localCleanup: () -> Set<VoiceGeneration>,
    private val retryLocalCleanup: (VoiceGeneration) -> Unit,
) {
    private var previousTime: Long? = null
    private var heartbeatTag: VoiceSessionTag? = null
    private var heartbeatAt = 0L
    private var joining: Pair<VoiceGeneration, VoiceOperationId>? = null
    private var joinAt = 0L
    private var cleanupActive = false
    private var cleanupAt = 0L
    private val job = scope.launch { pulses.collect { pulse() } }

    /** Cancels scheduling only. Caller still owns explicit voice/engine/server retirement. */
    fun cancel() = job.cancel()

    private suspend fun pulse() {
        val now = try { monotonicMillis() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { owner.stop(); return }
        if (now < 0 || previousTime?.let { now < it } == true) {
            owner.stop() // Broken clock is not a reason to fabricate fresh credit.
            return
        }
        previousTime = now
        val state = owner.state.value
        owner.event(VoiceSessionEvent.Tick(now))

        val tag = state.sessionId?.let { VoiceSessionTag(it, state.generation) }
            ?.takeIf { state.authorizesEngine(it, now) }
        if (tag != heartbeatTag) {
            heartbeatTag = tag; heartbeatAt = now
        } else if (tag != null && now - heartbeatAt >= HEARTBEAT_MILLIS) {
            heartbeatAt = now
            owner.heartbeat(tag)
        }

        val attempt = state.operationId?.takeIf { state.wanted && state.phase == VoicePhase.JOINING && state.sessionId == null }
            ?.let { state.generation to it }
        if (attempt != joining) {
            joining = attempt; joinAt = now
        } else if (attempt != null && now - joinAt >= JOIN_RETRY_MILLIS) {
            joinAt = now
            owner.retryJoin(attempt.second, attempt.first) // Preserve pulse identity across mailbox waits.
        }

        val local = localCleanup()
        val pending = owner.pendingRemoteStops.value.isNotEmpty() || owner.unconfirmedStops.value.isNotEmpty() || local.isNotEmpty()
        if (!pending) cleanupActive = false
        else if (!cleanupActive) {
            cleanupActive = true; cleanupAt = now
        } else if (now - cleanupAt >= CLEANUP_RETRY_MILLIS) {
            cleanupAt = now
            owner.retryStops()
            local.forEach(retryLocalCleanup)
        }
    }
    private companion object {
        const val HEARTBEAT_MILLIS = 5_000L
        const val JOIN_RETRY_MILLIS = 1_000L
        const val CLEANUP_RETRY_MILLIS = 3_000L
    }
}
