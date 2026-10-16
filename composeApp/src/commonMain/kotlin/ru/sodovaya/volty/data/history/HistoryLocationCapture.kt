package ru.sodovaya.volty.data.history

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.sodovaya.volty.data.location.LocationDemandCoordinator
import ru.sodovaya.volty.domain.location.LocationConsumer

internal enum class HistoryLocationCaptureFailure { LAUNCH, FOREGROUND, PROVIDER, CLEANUP }
internal data class HistoryLocationCaptureState(
    val wanted: Boolean = true,
    val capturing: Boolean = false,
    val failure: HistoryLocationCaptureFailure? = null,
)

/**
 * One recording's asynchronous foreground/provider lifetime, independent of BLE controls.
 * queued must enqueue work (native Dispatchers.Main, NOT Main.immediate/Unconfined):
 * authority callbacks may only fence/schedule, never run native IO under its lock.
 */
internal class HistoryLocationCapture(
    parentScope: CoroutineScope,
    private val queued: CoroutineDispatcher,
    private val recording: CapturedHistoryGpsRecording,
    private val foreground: HistoryLocationForegroundAuthority,
    private val demand: LocationDemandCoordinator,
    private val launch: suspend (HistoryLocationForegroundTicket) -> Boolean,
    private val startupTimeoutMillis: Long = 10_000,
) : HistoryRecordingLocationSession {
    init {
        require(startupTimeoutMillis > 0)
        require(demand.isFor(LocationConsumer.HISTORY_RECORDING)) { "History requires its own location consumer" }
    }
    private val lifetime = SupervisorJob(checkNotNull(parentScope.coroutineContext[Job]))
    private val scope = CoroutineScope(parentScope.coroutineContext + lifetime + queued)
    private val positives = SupervisorJob(lifetime)
    private val positiveScope = CoroutineScope(scope.coroutineContext + positives)
    private val mutableState = MutableStateFlow(HistoryLocationCaptureState())
    override val state = mutableState.asStateFlow()
    private val nativeTicket = demand.newTicket()
    private val cleanup = Mutex()
    private val startLock = Any()
    private var started: CompletableDeferred<Boolean>? = null
    val ticket: HistoryLocationForegroundTicket

    init {
        // Callback uses its supplied ticket: synchronous replacement can retire this
        // request before assignment below. All cleanup resources already exist.
        ticket = foreground.request(recording) { lost -> requestClose(lost, HistoryLocationCaptureFailure.FOREGROUND) }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() }
            finally { withContext(NonCancellable + queued) { close() } }
        }
    }

    override fun start(): Deferred<Boolean> {
        // A completed receipt describes the old activation; a NEW call after retirement
        // must not reuse that historical success or launch another native request.
        if (!state.value.wanted) return CompletableDeferred(false)
        // Reserve before launching: an immediate observer/reentrant caller gets the same result.
        // No native/authority calls or completion callbacks run under this small cache lock.
        val (result, fresh) = synchronized(startLock) {
            started?.let { it to false } ?: (CompletableDeferred<Boolean>().also { started = it } to true)
        }
        if (!fresh) return result
        if (!state.value.wanted) { result.complete(false); return result }
        result.invokeOnCompletion { if (result.isCancelled) requestClose(ticket, null) }
        val job = positiveScope.launch {
            var installed = false
            var phase = HistoryLocationCaptureFailure.LAUNCH
            try {
                if (!state.value.wanted) return@launch
                if (!launch(ticket)) { failure(phase); return@launch }
                phase = HistoryLocationCaptureFailure.FOREGROUND
                if (!foreground.awaitReady(ticket, startupTimeoutMillis)) { failure(phase); return@launch }
                if (!state.value.wanted) return@launch
                phase = HistoryLocationCaptureFailure.PROVIDER
                if (!demand.acquire(nativeTicket)) { failure(phase); return@launch }
                currentCoroutineContext().ensureActive()
                phase = HistoryLocationCaptureFailure.FOREGROUND
                if (!state.value.wanted || !foreground.admitCapture(ticket)) { failure(phase); return@launch }
                mutableState.update { if (it.wanted) it.copy(capturing = true) else it }
                currentCoroutineContext().ensureActive()
                installed = state.value.capturing && foreground.canCapture(recording)
            } catch (_: CancellationException) {
                // User cancellation already cancels the result. Native loss/timeout instead
                // completes false after original cleanup, leaving ordinary telemetry alone.
            } catch (_: Exception) { failure(phase) }
            finally {
                withContext(NonCancellable + queued) {
                    if (!state.value.wanted || !foreground.canCapture(recording)) installed = false
                    var released = false
                    if (!installed) { fence(ticket, null); released = releaseNative() }
                    result.complete(installed)
                    if (!installed && released) lifetime.cancel()
                }
            }
        }
        // A parent can cancel a queued launch before its body/finally ever starts.
        // The lifetime guardian owns cleanup; the caller must still get a terminal result.
        job.invokeOnCompletion { if (!result.isCompleted) result.complete(false) }
        return result
    }

    private fun failure(reason: HistoryLocationCaptureFailure) {
        mutableState.update { if (it.wanted) it.copy(failure = reason) else it }
    }

    private fun fence(lost: HistoryLocationForegroundTicket, reason: HistoryLocationCaptureFailure?): Boolean {
        val previous = mutableState.getAndUpdate {
            it.copy(wanted = false, capturing = false,
                failure = if (it.wanted && reason != null) reason else it.failure)
        }
        foreground.fenceCapture(lost)
        foreground.stop(lost)
        positives.cancel()
        return previous.wanted
    }

    private fun requestClose(lost: HistoryLocationForegroundTicket, reason: HistoryLocationCaptureFailure?) {
        if (!fence(lost, reason)) return
        // Always queued. The callback can be inside foreground's admission lock.
        scope.launch { if (releaseNative()) lifetime.cancel() }
    }

    private suspend fun releaseNative(): Boolean = withContext(NonCancellable + queued) {
        cleanup.withLock {
            val released = demand.release(nativeTicket)
            mutableState.update {
                when {
                    !released -> it.copy(failure = HistoryLocationCaptureFailure.CLEANUP)
                    it.failure == HistoryLocationCaptureFailure.CLEANUP -> it.copy(failure = null)
                    else -> it
                }
            }
            released
        }
    }

    override suspend fun close(): Boolean {
        fence(ticket, null)
        return withContext(NonCancellable + queued) {
            val released = releaseNative()
            if (released) lifetime.cancel()
            released
        }
    }
}
