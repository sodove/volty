package ru.sodovaya.volty.data.voice

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import ru.sodovaya.volty.domain.voice.*

/** Queued SERIAL subscription. Pulses must be broadcast/cold; owns neither port nor parent. */
class DirectCallRosterRuntime(parent: CoroutineScope, private val queuedDispatcher: CoroutineDispatcher,
    private val tag: DirectCallRosterTag, actor: String, private val port: DirectCallRosterPort,
    pulses: Flow<Unit>, private val monotonicMillis: () -> Long) {
    private val lifetime = run {
        require(queuedDispatcher.isDispatchNeeded(parent.coroutineContext)) {"Queued serial dispatcher required"}
        SupervisorJob(requireNotNull(parent.coroutineContext[Job]) {"Call roster requires parent Job"})
    }
    private val scope = CoroutineScope(parent.coroutineContext + lifetime + queuedDispatcher)
    private var replica = DirectCallRosterReplica(tag, actor)
    private var unknown: VoiceRosterUnknown? = VoiceRosterUnknown.INITIAL
    private var lastTime: Long? = null
    private var lastReadAt: Long? = null
    private var readJob: Job? = null
    private val output = MutableStateFlow(DirectCallRosterPresenceView())
    val state = output.asStateFlow()
    private val reads = MutableStateFlow(0)
    /** Executing application producers only; zero does not assert socket or queue idleness. */
    val readInFlight = reads.asStateFlow()

    init {
        scope.launch {try {awaitCancellation()} finally {clearPublished()}}
        if (!lifetime.isActive) clearPublished()
        scope.launch {refreshOwned()}
        scope.launch {
            try {
                pulses.collect {
                    val now = tick() ?: return@collect
                    if (lastReadAt == null || now - lastReadAt!! >= POLL_MILLIS) startRead(now)
                }
            } catch (cancelled: CancellationException) {
                if (!currentCoroutineContext().isActive) throw cancelled
            } catch (_: Exception) {
                // A failed scheduling source cannot keep fresh presence frozen on screen.
            } finally {
                if (lifetime.isActive) closeOwned(VoiceRosterUnknown.CLOSED)
            }
        }
    }
    suspend fun refresh(): Boolean = withContext(queuedDispatcher) {refreshOwned()}
    suspend fun close() = withContext(NonCancellable) {
        withContext(queuedDispatcher) {closeOwned(VoiceRosterUnknown.CLOSED)}
    }
    private fun refreshOwned(): Boolean {
        val now = tick() ?: return false
        return startRead(now)
    }
    private fun tick(): Long? {
        if (!lifetime.isActive) {clearPublished(); return null}
        val now = try {monotonicMillis()} catch (_: Exception) {-1}
        if (now < 0 || lastTime?.let {now < it} == true) {
            closeOwned(VoiceRosterUnknown.CLOCK_RESET); return null
        }
        lastTime = now
        publish(now)
        return now.takeIf {lifetime.isActive}
    }
    private fun startRead(now: Long): Boolean {
        if (!lifetime.isActive || readJob != null) return false
        lastReadAt = now
        readJob = scope.launch {
            reads.update {it + 1}
            try {
                val reply = try {
                    withTimeoutOrNull(REQUEST_TIMEOUT_MILLIS) {port.snapshot(tag)}
                        ?: DirectCallRosterReply.Failed(VoiceRosterFailure.UNAVAILABLE)
                } catch (cancelled: CancellationException) {
                    if (!currentCoroutineContext().isActive) throw cancelled
                    DirectCallRosterReply.Failed(VoiceRosterFailure.UNAVAILABLE)
                } catch (_: Exception) {DirectCallRosterReply.Failed(VoiceRosterFailure.UNAVAILABLE)}
                if (!lifetime.isActive) return@launch
                val receivedAt = tick() ?: return@launch
                val actual = if (reply is DirectCallRosterReply.Received && reply.observation.tag != tag)
                    DirectCallRosterReply.Failed(VoiceRosterFailure.INVALID_RESPONSE) else reply
                replica = replica.receive(actual)
                unknown = when (actual) {
                    is DirectCallRosterReply.Failed -> when (actual.reason) {
                        VoiceRosterFailure.UNAUTHORIZED, VoiceRosterFailure.ACCESS_DENIED -> VoiceRosterUnknown.ACCESS_DENIED
                        VoiceRosterFailure.UNAVAILABLE -> VoiceRosterUnknown.UNAVAILABLE
                        VoiceRosterFailure.INVALID_RESPONSE -> VoiceRosterUnknown.INVALID_RESPONSE
                    }
                    is DirectCallRosterReply.Received -> if (replica.resyncRequired) VoiceRosterUnknown.INVALID_RESPONSE else null
                }
                publish(receivedAt)
            } finally {
                readJob = null
                reads.update {it - 1}
            }
        }
        return true
    }
    private fun publish(now: Long) {
        if (!lifetime.isActive) {clearPublished(); return}
        val peers = replica.currentPeers(now)
        output.value = DirectCallRosterPresenceView(replica.revision, peers, peers?.count {it.transportConfirmed},
            if (peers == null) unknown ?: VoiceRosterUnknown.DISCONNECTED else null, replica.retired)
        if (replica.retired) lifetime.cancel()
    }
    private fun closeOwned(reason: VoiceRosterUnknown) {
        if (!output.value.retired) {
            replica = replica.retire(); unknown = reason
            output.value = DirectCallRosterPresenceView(unknown = reason, retired = true)
        }
        lifetime.cancel()
    }
    private fun clearPublished() = output.update {
        if (it.retired) it else it.copy(peers = null, connectedCount = null, unknown = VoiceRosterUnknown.CLOSED, retired = true)
    }
    private companion object {
        const val POLL_MILLIS = 5_000L
        const val REQUEST_TIMEOUT_MILLIS = 10_000L
    }
}
