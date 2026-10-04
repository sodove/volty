package ru.sodovaya.volty.data.voice

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import ru.sodovaya.volty.domain.voice.*

/**
 * Engine-only bridge. Dispatch and queued SDK jobs require the SAME serial owner
 * dispatcher (Android Main, not Main.immediate). Parent cancellation is not a
 * resource-release acknowledgement: application retirement must explicitly stop
 * and retain failedCleanup for retry. No timers/DI/sharing ownership here.
 */
class VoiceEngineEffectRunner(
    parent: CoroutineScope,
    queuedDispatcher: CoroutineDispatcher,
    factory: VoiceSdkSessionFactory,
    private val currentState: () -> VoiceSessionState,
    private val monotonicMillis: () -> Long,
    private val emit: suspend (VoiceSessionEvent) -> Unit,
    /** Additional nonblocking account fence; independent cleanup must bypass it. */
    private val accountPermission: () -> Boolean = { true },
) {
    private val scope = CoroutineScope(parent.coroutineContext + queuedDispatcher)
    private val driver = VoiceEngineDriver(scope, factory, ::authorizes)
    private val starts = mutableMapOf<VoiceGeneration, Start>()
    private val stops = mutableMapOf<VoiceGeneration, Job>()
    private var mute: Control? = null
    private var deafen: Control? = null
    private val cleanup = MutableStateFlow<Set<VoiceGeneration>>(emptySet())
    val failedCleanup = cleanup.asStateFlow()
    private val workCount = MutableStateFlow(0)
    /** Cancelled-but-incomplete producers still hold this count, even after Stopped. */
    val inFlight = workCount.asStateFlow()

    init {
        require(queuedDispatcher.isDispatchNeeded(parent.coroutineContext)) { "Queued serial dispatcher required" }
        scope.launch(start = CoroutineStart.UNDISPATCHED) { driver.events.collect { emit(it) } }
    }

    /** No native allocation or suspended SDK work runs inline in this call. */
    fun dispatch(command: VoiceControlDispatch) {
        when (val effect = command.effect) {
            is VoiceSessionEffect.StartEngine -> start(effect, requireNotNull(command.credentials))
            is VoiceSessionEffect.StopEngine -> stop(effect.generation)
            is VoiceSessionEffect.SetMuted -> control(effect.tag, effect.value, microphone = true)
            is VoiceSessionEffect.SetDeafened -> control(effect.tag, effect.value, microphone = false)
            else -> throw IllegalArgumentException("Engine effect required")
        }
    }

    private fun start(effect: VoiceSessionEffect.StartEngine, credentials: VoiceConnectionCredentials) {
        val previous = starts[effect.tag.generation]
        if (previous != null) {
            if (previous.tag != effect.tag) scope.launch { emit(VoiceSessionEvent.EngineFailed(effect.tag)) }
            return
        }
        val record = Start(effect.tag)
        starts[effect.tag.generation] = record
        record.job = launchWork {
            val state = currentState()
            val intent = if (state.sessionId == effect.tag.sessionId && state.generation == effect.tag.generation)
                effect.copy(muted = state.muted, deafened = state.deafened) else effect
            // Driver samples authorization again after any resource/connect wait.
            // Start failures are emitted by the driver with exact tag.
            driver.start(intent, credentials)
        }
    }

    private fun stop(generation: VoiceGeneration) {
        starts[generation]?.job?.cancel()
        mute?.takeIf { it.tag.generation == generation }?.job?.cancel()
        deafen?.takeIf { it.tag.generation == generation }?.job?.cancel()
        if (stops[generation]?.isActive == true) return
        stops[generation] = launchWork {
            try {
                val result = driver.stop(generation)
                if (result.isSuccess) {
                    starts.remove(generation)
                    cleanup.value -= generation
                } else cleanup.value += generation
            } finally { stops.remove(generation) }
        }
    }

    private fun control(tag: VoiceSessionTag, value: Boolean, microphone: Boolean) {
        // A stale preference must not even cancel the current tag's queued work.
        if (!authorizes(tag)) return
        val previous = if (microphone) mute else deafen
        previous?.job?.cancel()
        val pending = Control(tag)
        if (microphone) mute = pending else deafen = pending
        pending.job = launchWork {
            if ((if (microphone) mute else deafen) !== pending) return@launchWork
            val result = if (microphone) driver.setMuted(tag, value) else driver.setDeafened(tag, value)
            if (result.isFailure) emit(VoiceSessionEvent.EngineFailed(tag))
        }
    }

    private fun authorizes(tag: VoiceSessionTag): Boolean = try {
        accountPermission() && currentState().authorizesEngine(tag, monotonicMillis())
    } catch (_: Exception) { false }

    private fun launchWork(block: suspend CoroutineScope.() -> Unit): Job {
        workCount.update { check(it < Int.MAX_VALUE); it + 1 }
        val job = try { scope.launch(block = block) }
        catch (failure: Throwable) { workCount.update { it - 1 }; throw failure }
        // A cancelled queued job may never enter its body/finally. Completion is the fence.
        job.invokeOnCompletion { workCount.update { it - 1 } }
        return job
    }

    private class Start(val tag: VoiceSessionTag) { var job: Job? = null }
    private class Control(val tag: VoiceSessionTag) { var job: Job? = null }
}
