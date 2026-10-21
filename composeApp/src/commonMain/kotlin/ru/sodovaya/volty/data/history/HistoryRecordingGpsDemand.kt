package ru.sodovaya.volty.data.history

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Native session owns its own asynchronous launch and original cleanup. */
internal interface HistoryRecordingLocationSession {
    val state: StateFlow<HistoryLocationCaptureState>
    fun start(): Deferred<Boolean>
    suspend fun close(): Boolean
}

internal data class HistoryRecordingLocationSnapshot(
    val recording: CapturedHistoryGpsRecording,
    val state: HistoryLocationCaptureState,
)

/** Runtime-owned adapter. The runtime must release it with setEnabled(false) on shutdown. */
internal class HistoryRecordingGpsDemand(
    private val currentRecording: () -> CapturedHistoryGpsRecording?,
    private val setServiceEnabled: (Boolean) -> Unit,
    private val createSession: (CapturedHistoryGpsRecording) -> HistoryRecordingLocationSession,
) : HistoryRecordingDemand {
    private data class Owned(val recording: CapturedHistoryGpsRecording, val session: HistoryRecordingLocationSession)
    private val controls = Mutex()
    private var enabled = false
    private var owned: Owned? = null
    private val observed = MutableStateFlow<Owned?>(null)
    // A cold observer does not acquire/release native GPS. Preserve the exact
    // original owner while failed cleanup still owns its provider obligation.
    @OptIn(ExperimentalCoroutinesApi::class)
    val locationStates: Flow<HistoryRecordingLocationSnapshot?> = observed.flatMapLatest { current ->
        current?.session?.state?.map { HistoryRecordingLocationSnapshot(current.recording, it) } ?: flowOf(null)
    }

    override suspend fun setEnabled(enabled: Boolean) = control {
        this.enabled = enabled
        reconcile()
    }

    /** A resumed host/permission opportunity retries desired state, never invents a recording. */
    suspend fun retry() = control { reconcile() }

    private suspend fun reconcile() {
        if (!enabled) {
            // Policy can release while failed native removal retains the actual foreground
            // service independently. Keep the original session until its real cleanup ACK.
            try { retire() } finally { setServiceEnabled(false) }
            return
        }
        val target = checkNotNull(currentRecording()) { "GPS demand needs an owned recording" }
        owned?.let { previous ->
            if (!sameRecording(previous.recording, target) || !previous.session.state.value.wanted) retire()
        }
        // Retirement can suspend, and service/factory publication can reenter the runtime.
        // Abort this positive rather than redirecting an expired retry to a different ride.
        if (!isCurrent(target)) return
        // Retain service even when optional GPS startup subsequently fails.
        setServiceEnabled(true)
        if (!isCurrent(target)) return
        if (owned == null) {
            val session = createSession(target)
            owned = Owned(target, session) // Reserve before start can throw or call back.
            observed.value = owned
            if (!isCurrent(target)) { retire(); return }
            try {
                session.start() // Native foreground/provider ACK is asynchronous; telemetry proceeds.
            } catch (error: Exception) {
                // A synchronous launch failure must not masquerade as an active session on retry.
                // If cleanup also fails, keep its original object and preserve the launch error.
                try { retire() } catch (cleanup: Exception) { if (cleanup !== error) error.addSuppressed(cleanup) }
                throw error
            }
        }
    }

    private fun sameRecording(a: CapturedHistoryGpsRecording, b: CapturedHistoryGpsRecording): Boolean =
        a.identity === b.identity && a.generation == b.generation && a.rideId == b.rideId

    private fun isCurrent(target: CapturedHistoryGpsRecording): Boolean =
        currentRecording()?.let { sameRecording(it, target) } == true

    private suspend fun retire() {
        val previous = owned ?: return
        check(previous.session.close()) { "History location cleanup pending" }
        owned = null
        observed.value = null
    }

    private suspend fun control(action: suspend () -> Unit) {
        currentCoroutineContext().ensureActive()
        controls.withLock {
            currentCoroutineContext().ensureActive()
            // An accepted policy change cannot abandon the original native/service obligation.
            withContext(NonCancellable) { action() }
        }
        currentCoroutineContext().ensureActive()
    }
}
