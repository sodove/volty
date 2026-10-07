package ru.sodovaya.volty.data.voice

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import ru.sodovaya.volty.domain.voice.*

/**
 * One account-bound roster subscription. All calls require the same queued SERIAL dispatcher.
 * Clock includes sleep. Port principal must not change within this lifetime. This owner
 * neither joins voice nor mutates membership/sharing, and does not close a shared HTTP port.
 */
class VoiceRosterRuntime(
    parent: CoroutineScope,
    private val queuedDispatcher: CoroutineDispatcher,
    private val tag: VoiceRosterTag,
    private val port: VoiceRosterSnapshotPort,
    pulses: Flow<Unit>,
    private val monotonicMillis: () -> Long,
) {
    private val lifetime = run {
        require(queuedDispatcher.isDispatchNeeded(parent.coroutineContext)) { "Queued serial dispatcher required" }
        SupervisorJob(parent.coroutineContext[Job])
    }
    private val scope = CoroutineScope(parent.coroutineContext + lifetime + queuedDispatcher)
    private val presence = VoiceRosterPresence(tag)
    private val output = MutableStateFlow(presence.view)
    val state = output.asStateFlow()
    private var readJob: Job? = null
    private var lastReadAt: Long? = null

    init {
        // Clear published rows on cancellation without waiting for a network child to finish.
        scope.launch {
            try { awaitCancellation() }
            finally { clearPublishedState() }
        }
        if (!lifetime.isActive) clearPublishedState()
        scope.launch { refreshOwned() }
        scope.launch {
            try {
                pulses.collect {
                    val now = now()
                    presence.tick(now)
                    publish()
                    if (lifetime.isActive && (lastReadAt == null || now - lastReadAt!! >= POLL_MILLIS)) startRead(now)
                }
            } catch (cancelled: CancellationException) {
                if (!currentCoroutineContext().isActive) throw cancelled
                // A stopped pulse source must not leave lease freshness frozen indefinitely.
                presence.close(); publish()
            }
            catch (_: Exception) { presence.close(); publish() }
        }
    }

    /** Coalesces an active read; true means a new actual request was scheduled. */
    suspend fun refresh(): Boolean = withContext(queuedDispatcher) { refreshOwned() }

    suspend fun close() = withContext(queuedDispatcher) {
        presence.close()
        output.value = presence.view
        lifetime.cancel()
    }

    private fun refreshOwned(): Boolean {
        if (!lifetime.isActive) { clearPublishedState(); return false }
        val now = now()
        presence.tick(now)
        publish()
        return lifetime.isActive && startRead(now)
    }

    private fun startRead(now: Long): Boolean {
        if (!lifetime.isActive || readJob != null) return false
        val request = presence.beginRead() ?: return false
        lastReadAt = now
        readJob = scope.launch {
            val reply = try {
                withTimeoutOrNull(REQUEST_TIMEOUT_MILLIS) { port.snapshot(tag) }
                    ?: VoiceRosterSnapshotReply.Failed(VoiceRosterFailure.UNAVAILABLE)
            } catch (cancelled: CancellationException) {
                if (!currentCoroutineContext().isActive) throw cancelled
                VoiceRosterSnapshotReply.Failed(VoiceRosterFailure.UNAVAILABLE)
            } catch (_: Exception) { VoiceRosterSnapshotReply.Failed(VoiceRosterFailure.UNAVAILABLE) }
            if (!lifetime.isActive) return@launch
            readJob = null
            presence.complete(request, reply, now())
            publish()
        }
        return true
    }

    private fun now(): Long = try { monotonicMillis() }
    catch (cancelled: CancellationException) {
        if (!lifetime.isActive) throw cancelled
        -1 // The active owner's projection converts synchronous clock failure to CLOCK_RESET.
    }
    catch (_: Exception) { -1 }

    private fun publish() {
        if (!lifetime.isActive) { clearPublishedState(); return }
        output.value = presence.view
        if (output.value.retired) lifetime.cancel()
    }

    private fun clearPublishedState() = output.update {
        if (it.retired) it else it.copy(peers = null, connectedCount = null, unknown = VoiceRosterUnknown.CLOSED, retired = true, profileNames = emptyMap())
    }

    private companion object {
        const val POLL_MILLIS = 5_000L
        const val REQUEST_TIMEOUT_MILLIS = 10_000L
    }
}
