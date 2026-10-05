package ru.sodovaya.volty.data.voice

import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import ru.sodovaya.volty.data.nearby.*
import ru.sodovaya.volty.data.social.SocialAuthenticatedAccount
import ru.sodovaya.volty.domain.voice.*

/** Call admission and original journal maintenance; never submits a channel join. */
internal class DirectCallVoiceSessionControl private constructor(
    private val account: SocialAuthenticatedAccount, private val calls: DirectCallAccountControl,
    private val journal: VoiceSessionAccountControl,
) : VoiceSessionControlPort {
    override fun allowsSharing(operation: VoiceOperationId) = false
    private sealed interface Mode {
        val call: String
        data class Outgoing(override val call: String, val callee: String, val key: String) : Mode
        data class Incoming(override val call: String, val revision: Long) : Mode
    }
    private data class Capability(val retired: Boolean = false, val closed: Boolean = false,
        val modes: Map<VoiceOperationId, Mode> = emptyMap(), val generations: Map<VoiceOperationId, VoiceGeneration> = emptyMap(),
        val sessions: Map<VoiceSessionTag, VoiceOperationId> = emptyMap())
    private val capability = MutableStateFlow(Capability())

    fun registerOutgoing(call: String, callee: String, key: String, operation: VoiceOperationId): Boolean {
        if (!mayRegister(call, operation) || !calls.rememberOutgoing(call, callee, key, operation)) return false
        return remember(operation, Mode.Outgoing(call, callee, key))
    }
    fun registerIncoming(call: DirectCallSnapshot, operation: VoiceOperationId): Boolean {
        if (!mayRegister(call.callId, operation) || !calls.rememberIncoming(call, operation)) return false
        return remember(operation, Mode.Incoming(call.callId, call.revision))
    }
    private fun mayRegister(call: String, operation: VoiceOperationId) = !capability.value.closed && !capability.value.retired &&
        account.job.isActive && journal.canRememberPreparedCallOperation(call, operation)
    private fun remember(operation: VoiceOperationId, mode: Mode): Boolean {
        while (true) {
            val current = capability.value
            if (current.closed || current.retired || !account.job.isActive) return false
            current.modes[operation]?.let {return it == mode}
            if (capability.compareAndSet(current, current.copy(modes = current.modes + (operation to mode)))) return true
        }
    }
    suspend fun incoming(after: String? = null, limit: Int = 20): IncomingCallReply {
        ensurePositive(); return calls.incoming(after, limit).also {ensurePositive()}
    }
    suspend fun callStatus(call: String): DirectCallReply {
        ensurePositive(); return calls.status(call).also {ensurePositive()}
    }
    fun owns(operation: VoiceOperationId) = operation in capability.value.modes
    fun owns(tag: VoiceSessionTag) = tag in capability.value.sessions
    /** Original observed incoming metadata only; no generation, foreground or media grant. */
    suspend fun declineIncoming(operation: VoiceOperationId): DirectCallReply {
        ensureOpen()
        val incoming = capability.value.modes[operation] as? Mode.Incoming
            ?: return DirectCallReply.Failed(DirectCallFailure.ACCESS_DENIED)
        return calls.decline(incoming.call).also {ensureOpen()}
    }

    override fun rememberJoinIntent(operation: VoiceOperationId, generation: VoiceGeneration, channel: String, replace: Boolean) {
        val mode = capability.value.modes[operation] ?: return
        if (channel != mode.call || replace || !calls.bindMediaGeneration(mode.call, operation, generation) ||
            !journal.rememberPreparedCallIntent(mode.call, operation, generation)) return
        capability.update {if (it.closed || it.retired || it.modes[operation] != mode) it
            else it.copy(generations = it.generations + (operation to generation))}
    }
    override suspend fun join(operation: VoiceOperationId, generation: VoiceGeneration, channel: String, replace: Boolean): VoiceHttpReply {
        ensurePositive()
        val mode = original(operation, generation) ?: return denied()
        if (channel != mode.call || replace) return denied()
        val response = when (mode) {
            is Mode.Outgoing -> {
                val created = calls.create(mode.call, mode.callee, mode.key, operation)
                if (created is DirectCallReply.Ready && created.call.phase == DirectCallPhase.ACCEPTED)
                    calls.resume(mode.call, operation, generation) else created
            }
            is Mode.Incoming -> calls.accept(mode.call, operation, generation, mode.revision)
        }
        return admission(mode, operation, generation, response).also {ensurePositive()}
    }
    override suspend fun status(operation: VoiceOperationId, generation: VoiceGeneration): VoiceHttpReply {
        ensurePositive()
        val mode = original(operation, generation) ?: return denied()
        return when (val parent = calls.status(mode.call)) {
            is DirectCallReply.Failed -> failed(parent.reason)
            is DirectCallReply.Ready -> when {
                parent.call.terminal -> journal.cancel(operation, generation)
                parent.call.phase == DirectCallPhase.RINGING -> VoiceHttpReply.Pending(generation, null, operation)
                else -> journal.status(operation, generation)
            }
        }.also {ensurePositive()}
    }
    override suspend fun heartbeat(tag: VoiceSessionTag): VoiceHttpReply {
        ensurePositive()
        if (tag !in capability.value.sessions) return denied()
        return journal.heartbeat(tag).also {ensurePositive()}
    }
    override suspend fun cancel(operation: VoiceOperationId, generation: VoiceGeneration): VoiceHttpReply {
        ensureOpen()
        val mode = original(operation, generation) ?: return denied()
        return terminate(mode, operation) {journal.cancel(operation, generation)}
    }
    override suspend fun disconnect(tag: VoiceSessionTag): VoiceHttpReply {
        ensureOpen()
        val operation = capability.value.sessions[tag] ?: return denied()
        val mode = original(operation, tag.generation) ?: return denied()
        return terminate(mode, operation) {journal.disconnect(tag)}
    }
    private suspend fun admission(mode: Mode, operation: VoiceOperationId, generation: VoiceGeneration, response: DirectCallReply): VoiceHttpReply {
        if (response is DirectCallReply.Failed) return failed(response.reason)
        response as DirectCallReply.Ready
        if (response.retryableFailure != null) return VoiceHttpReply.Failed(VoiceHttpFailure.UNAVAILABLE)
        if (response.call.terminal) return journal.cancel(operation, generation)
        if (response.call.phase == DirectCallPhase.RINGING) return VoiceHttpReply.Pending(generation, null, operation)
        val media = response.session ?: return VoiceHttpReply.Failed(VoiceHttpFailure.UNAVAILABLE)
        val tag = when (media) {
            is VoiceHttpReply.Credentials -> media.tag
            is VoiceHttpReply.Acknowledged -> media.tag
            is VoiceHttpReply.Expired -> media.tag
            is VoiceHttpReply.Pending -> media.sessionId?.let {VoiceSessionTag(it, media.generation)}
            else -> null
        }
        if (tag != null) {
            if (!journal.rememberPreparedCallSession(mode.call, operation, generation, media)) return denied()
            capability.update {if (it.closed) it else it.copy(sessions = it.sessions + (tag to operation))}
        }
        return media
    }
    private suspend fun terminate(mode: Mode, operation: VoiceOperationId, stopJournal: suspend () -> VoiceHttpReply): VoiceHttpReply {
        val parent = try {
            when (mode) {
                is Mode.Outgoing -> calls.cancelOutgoing(mode.call, mode.callee, mode.key, operation)
                is Mode.Incoming -> calls.stop(mode.call)
            }
        } catch (cancelled: CancellationException) {throw cancelled}
          catch (_: Exception) {DirectCallReply.Failed(DirectCallFailure.UNAVAILABLE)}
        // Parent snapshot is not a journal or SFU cleanup ACK; still attempt the exact journal.
        val child = try {stopJournal()} catch (cancelled: CancellationException) {throw cancelled}
            catch (_: Exception) {VoiceHttpReply.Failed(VoiceHttpFailure.UNAVAILABLE)}
        ensureOpen()
        return if (parent is DirectCallReply.Ready && parent.call.terminal && child is VoiceHttpReply.Ended) child
            else VoiceHttpReply.Failed(VoiceHttpFailure.UNAVAILABLE)
    }
    private fun original(operation: VoiceOperationId, generation: VoiceGeneration): Mode? = capability.value.let {
        if (it.generations[operation] == generation) it.modes[operation] else null
    }
    fun retireAdmission() {
        capability.update {it.copy(retired = true)}
        calls.retireAdmission(); journal.retireAdmission()
    }
    fun close() {
        capability.update {Capability(retired = true, closed = true)}
        var failed = false
        try {calls.close()} catch (_: Throwable) {failed = true}
        try {journal.close()} catch (_: Throwable) {failed = true}
        if (failed) throw IllegalStateException("Call voice HTTP cleanup failed")
    }
    private suspend fun ensurePositive() {
        ensureOpen()
        if (capability.value.retired || !account.job.isActive) throw CancellationException(RETIRED)
    }
    private suspend fun ensureOpen() {
        currentCoroutineContext().ensureActive()
        if (capability.value.closed) throw CancellationException(RETIRED)
    }
    private fun denied() = VoiceHttpReply.Failed(VoiceHttpFailure.ACCESS_DENIED)
    private fun failed(reason: DirectCallFailure) = VoiceHttpReply.Failed(when (reason) {
        DirectCallFailure.UNAUTHORIZED -> VoiceHttpFailure.UNAUTHORIZED
        DirectCallFailure.ACCESS_DENIED, DirectCallFailure.FRIENDSHIP_REQUIRED -> VoiceHttpFailure.ACCESS_DENIED
        DirectCallFailure.BUSY -> VoiceHttpFailure.BUSY
        DirectCallFailure.CONFLICT -> VoiceHttpFailure.CONFLICT
        DirectCallFailure.INVALID_RESPONSE -> VoiceHttpFailure.INVALID_RESPONSE
        DirectCallFailure.UNAVAILABLE -> VoiceHttpFailure.UNAVAILABLE
    })
    companion object {
        private const val RETIRED = "Direct call voice admission retired"
        internal suspend fun withJournal(account: SocialAuthenticatedAccount, client: HttpClient, baseUrl: String,
                                         monotonicMillis: () -> Long, sequence: VoiceLeaseRequestSequence,
                                         journal: VoiceSessionAccountControl): DirectCallVoiceSessionControl =
            DirectCallVoiceSessionControl(account, DirectCallAccountControl.create(account, client, baseUrl, monotonicMillis, sequence), journal)
        suspend fun create(account: SocialAuthenticatedAccount, client: HttpClient, baseUrl: String, monotonicMillis: () -> Long): DirectCallVoiceSessionControl {
            val sequence = VoiceLeaseRequestSequence()
            val calls = DirectCallAccountControl.create(account, client, baseUrl, monotonicMillis, sequence)
            return try {
                val journal = VoiceSessionAccountControl.create(account, client, baseUrl, monotonicMillis, sequence)
                DirectCallVoiceSessionControl(account, calls, journal)
            } catch (failure: Throwable) {
                try {calls.close()} catch (cleanup: Throwable) {failure.addSuppressed(cleanup)}
                throw failure
            }
        }
    }
}
