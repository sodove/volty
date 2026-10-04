package ru.sodovaya.volty.data.nearby

import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import ru.sodovaya.volty.data.social.SocialAuthenticatedAccount
import ru.sodovaya.volty.domain.voice.*
import ru.sodovaya.volty.data.voice.VoiceLeaseRequestSequence

/** Admission may retire immediately; original terminal cleanup survives until root-owned drain. */
internal class DirectCallAccountControl private constructor(
    private val account: SocialAuthenticatedAccount, client: HttpClient, baseUrl: String,
    monotonicMillis: () -> Long, initialBearer: String, leaseRequests: VoiceLeaseRequestSequence,
) : DirectCallControlPort {
    private sealed interface Intent {
        val operation: VoiceOperationId
        val generation: VoiceGeneration
        data class Outgoing(val callee: String, val key: String, override val operation: VoiceOperationId,
            override val generation: VoiceGeneration) : Intent
        data class Incoming(val revision: Long, override val operation: VoiceOperationId,
            override val generation: VoiceGeneration) : Intent
    }
    private data class Capability(val retired: Boolean = false, val closed: Boolean = false,
        val bearer: String? = null, val intents: Map<String, Intent> = emptyMap(),
        val observations: Map<String, DirectCallSnapshot> = emptyMap())
    private val capability = MutableStateFlow(Capability(bearer = initialBearer))
    private val positive = DirectCallHttpPort(account.userId.value, client, baseUrl, {
        ensureLive()
        val token = account.bearer()
        ensureLive()
        if (token != null) capability.update {if (it.closed || it.retired) it else it.copy(bearer = token)}
        ensureLive()
        token
    }, monotonicMillis, leaseRequests)
    private val cleanup = DirectCallHttpPort(account.userId.value, client, baseUrl, {
        ensureOpen()
        capability.value.bearer
    }, monotonicMillis, leaseRequests)

    fun rememberOutgoing(call: String, callee: String, key: String, operation: VoiceOperationId, generation: VoiceGeneration): Boolean {
        if (!valid(call, callee, key, operation.value) || callee == account.userId.value || generation.value < 0) return false
        return remember(call, Intent.Outgoing(callee, key, operation, generation))
    }
    fun rememberIncoming(call: DirectCallSnapshot, operation: VoiceOperationId, generation: VoiceGeneration): Boolean {
        if (!valid(call.callId, operation.value) || generation.value < 0 || call.phase != DirectCallPhase.RINGING ||
            call.calleeId != account.userId.value) return false
        return remember(call.callId, Intent.Incoming(call.revision, operation, generation), call)
    }
    private fun remember(call: String, intent: Intent, observation: DirectCallSnapshot? = null): Boolean {
        while (true) {
            val current = capability.value
            if (current.closed || current.retired || !account.job.isActive) return false
            val existing = current.intents[call]
            if (existing != null) return existing == intent
            if (observation != null && current.observations[call] != observation) return false
            if (current.intents.values.any {it.operation == intent.operation}) return false
            if (capability.compareAndSet(current, current.copy(intents = current.intents + (call to intent)))) return true
        }
    }

    override suspend fun create(call: String, callee: String, key: String, operation: VoiceOperationId): DirectCallReply {
        ensureLive()
        if (!outgoingMatches(call, callee, key, operation)) return denied()
        return positiveReply {positive.create(call, callee, key, operation)}
    }
    override suspend fun cancelOutgoing(call: String, callee: String, key: String, operation: VoiceOperationId): DirectCallReply {
        ensureOpen()
        if (!outgoingMatches(call, callee, key, operation)) return denied()
        return cleanup.cancelOutgoing(call, callee, key, operation).also {ensureOpen()}
    }
    override suspend fun accept(call: String, operation: VoiceOperationId, generation: VoiceGeneration, revision: Long): DirectCallReply {
        ensureLive()
        if (capability.value.intents[call] != Intent.Incoming(revision, operation, generation)) return denied()
        return positiveReply {positive.accept(call, operation, generation, revision)}
    }
    override suspend fun resume(call: String, operation: VoiceOperationId, generation: VoiceGeneration): DirectCallReply {
        ensureLive()
        val original = capability.value.intents[call]
        if (original?.operation != operation || original.generation != generation) return denied()
        return positiveReply {positive.resume(call, operation, generation)}
    }
    override suspend fun status(call: String): DirectCallReply {
        ensureLive()
        return positiveReply {positive.status(call)}
    }
    override suspend fun incoming(after: String?, limit: Int): IncomingCallReply {
        ensureLive()
        val reply = positive.incoming(after, limit)
        if (reply is IncomingCallReply.Ready) reply.calls.forEach(::observe)
        ensureLive()
        return reply
    }
    override suspend fun decline(call: String): DirectCallReply {
        ensureOpen()
        if (capability.value.intents[call] !is Intent.Incoming) return denied()
        return cleanup.decline(call).also {ensureOpen()}
    }
    override suspend fun stop(call: String): DirectCallReply {
        ensureOpen()
        if (call !in capability.value.intents) return denied()
        return cleanup.stop(call).also {ensureOpen()}
    }

    fun retireAdmission() = capability.update {it.copy(retired = true, observations = emptyMap())}
    /** Enclosing root drain owns closing; authentication cancellation alone never closes HTTP. */
    fun close() {
        capability.update {Capability(retired = true, closed = true)}
        var failed = false
        try {positive.close()} catch (_: Throwable) {failed = true}
        try {cleanup.close()} catch (_: Throwable) {failed = true}
        if (failed) throw IllegalStateException("Call control HTTP cleanup failed")
    }
    private suspend fun positiveReply(request: suspend () -> DirectCallReply): DirectCallReply {
        val reply = request()
        if (reply is DirectCallReply.Ready) observe(reply.call)
        ensureLive()
        return reply
    }
    private fun observe(call: DirectCallSnapshot) = capability.update {
        val previous = it.observations[call.callId]
        if (it.closed || it.retired || !account.job.isActive || call.calleeId != account.userId.value ||
            (previous != null && previous.revision >= call.revision)) it
        else it.copy(observations = it.observations + (call.callId to call))
    }
    private fun outgoingMatches(call: String, callee: String, key: String, operation: VoiceOperationId): Boolean {
        val original = capability.value.intents[call] as? Intent.Outgoing ?: return false
        return original.callee == callee && original.key == key && original.operation == operation
    }
    private suspend fun ensureLive() {
        ensureOpen()
        if (capability.value.retired || !account.job.isActive) throw CancellationException(RETIRED)
    }
    private suspend fun ensureOpen() {
        currentCoroutineContext().ensureActive()
        if (capability.value.closed) throw CancellationException(RETIRED)
    }
    private fun denied() = DirectCallReply.Failed(DirectCallFailure.ACCESS_DENIED)
    private fun valid(vararg values: String): Boolean = try {values.forEach(::directCallId); true} catch (_: IllegalArgumentException) {false}

    companion object {
        private const val RETIRED = "Authenticated call control retired"
        suspend fun create(account: SocialAuthenticatedAccount, client: HttpClient, baseUrl: String, monotonicMillis: () -> Long,
            leaseRequests: VoiceLeaseRequestSequence): DirectCallAccountControl {
            currentCoroutineContext().ensureActive()
            check(account.job.isActive) {RETIRED}
            directCallId(account.userId.value); nearbyHttpsApiBaseUrl(baseUrl)
            val token = account.bearer()?.takeIf {it.isNotBlank()} ?: throw CancellationException(RETIRED)
            currentCoroutineContext().ensureActive()
            check(account.job.isActive) {RETIRED}
            return DirectCallAccountControl(account, client, baseUrl, monotonicMillis, token, leaseRequests)
        }
        suspend fun create(account: SocialAuthenticatedAccount, client: HttpClient, baseUrl: String,
            monotonicMillis: () -> Long): DirectCallAccountControl =
            create(account, client, baseUrl, monotonicMillis, VoiceLeaseRequestSequence())
    }
}
