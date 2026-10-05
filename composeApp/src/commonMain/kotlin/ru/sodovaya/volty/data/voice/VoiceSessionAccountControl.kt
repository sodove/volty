package ru.sodovaya.volty.data.voice

import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import ru.sodovaya.volty.data.nearby.nearbyHttpsApiBaseUrl
import ru.sodovaya.volty.data.social.SocialAuthenticatedAccount
import ru.sodovaya.volty.domain.nearby.community.invitationDraftId
import ru.sodovaya.volty.domain.voice.*

/** Retirement removes positive permissions; original terminal cleanup survives separately. */
internal class VoiceSessionAccountControl private constructor(
    private val account: SocialAuthenticatedAccount,
    client: HttpClient,
    baseUrl: String,
    monotonicMillis: () -> Long,
    initialBearer: String,
    leaseRequests: VoiceLeaseRequestSequence,
) : VoiceSessionControlPort {
    private enum class IntentKind { CHANNEL, DIRECT_CALL }
    private data class Intent(val generation: VoiceGeneration, val channel: String, val replace: Boolean,
        val kind: IntentKind = IntentKind.CHANNEL)
    private data class Capability(
        val retired: Boolean = false,
        val closed: Boolean = false,
        val bearer: String? = null,
        val intents: Map<VoiceOperationId, Intent> = emptyMap(),
        val sessions: Set<VoiceSessionTag> = emptySet(),
    )
    // One atomic capability prevents late token/identity writers resurrecting a closed owner.
    private val capability = MutableStateFlow(Capability(bearer = initialBearer))
    override fun allowsSharing(operation: VoiceOperationId): Boolean = capability.value.let {
        !it.closed && !it.retired && account.job.isActive && it.intents[operation]?.kind == IntentKind.CHANNEL
    }
    private val positive = VoiceSessionHttpPort(client, baseUrl, {
        ensureLive()
        val token = account.bearer()
        ensureLive()
        if (token != null) capability.update { if (it.closed || it.retired) it else it.copy(bearer = token) }
        ensureLive()
        token
    }, monotonicMillis, leaseRequests)
    private val cleanup = VoiceSessionHttpPort(client, baseUrl, {
        ensureOpen()
        // No refresh wait, new-account token or auth Job dependency in terminal cleanup.
        capability.value.bearer
    }, monotonicMillis, leaseRequests)

    override fun rememberJoinIntent(operation: VoiceOperationId, generation: VoiceGeneration, channel: String, replace: Boolean) {
        // Pure owner metadata, not permission to join. Malformed intent never earns cleanup.
        if (!ID.matches(operation.value) || !ID.matches(channel) || generation.value < 0) return
        capability.update {
            if (it.closed || operation in it.intents) it
            else it.copy(intents = it.intents + (operation to Intent(generation, channel, replace)))
        }
    }

    internal fun canRememberPreparedCallOperation(call: String, operation: VoiceOperationId): Boolean {
        val current = capability.value
        val original = current.intents[operation]
        return !current.closed && ID.matches(call) && ID.matches(operation.value) &&
            (original == null || (original.kind == IntentKind.DIRECT_CALL && original.channel == call))
    }

    internal fun rememberPreparedCallIntent(call: String, operation: VoiceOperationId, generation: VoiceGeneration): Boolean {
        if (!ID.matches(call) || !ID.matches(operation.value) || generation.value < 0) return false
        val intent = Intent(generation, call, false, IntentKind.DIRECT_CALL)
        while (true) {
            val current = capability.value
            if (current.closed) return false
            val original = current.intents[operation]
            if (original != null) return original == intent
            if (capability.compareAndSet(current, current.copy(intents = current.intents + (operation to intent)))) return true
        }
    }

    /** Validated call transport supplied this identity; recording it never authorizes audio.
     * Retired original owners can still learn what to stop, while closed owners stay empty. */
    internal fun rememberPreparedCallSession(call: String, operation: VoiceOperationId,
        generation: VoiceGeneration, reply: VoiceHttpReply): Boolean {
        val tag = when (reply) {
            is VoiceHttpReply.Credentials -> reply.tag
            is VoiceHttpReply.Acknowledged -> reply.tag
            is VoiceHttpReply.Expired -> reply.tag
            is VoiceHttpReply.Pending -> {
                if (reply.operationId != null && reply.operationId != operation) return false
                reply.sessionId?.let {VoiceSessionTag(it, reply.generation)}
            }
            else -> null
        } ?: return false
        if (tag.generation != generation || !ID.matches(tag.sessionId.value)) return false
        while (true) {
            val current = capability.value
            // Earlier receipt matching used bare destination UUID; a channel intent with
            // identical UUID/operation could then acquire an unrelated call session identity.
            if (current.closed || current.intents[operation] != Intent(generation, call, false, IntentKind.DIRECT_CALL)) return false
            if (tag in current.sessions) return true
            if (capability.compareAndSet(current, current.copy(sessions = current.sessions + tag))) return true
        }
    }

    override suspend fun join(operation: VoiceOperationId, generation: VoiceGeneration, channel: String, replace: Boolean): VoiceHttpReply {
        ensureLive()
        rememberJoinIntent(operation, generation, channel, replace)
        if (capability.value.intents[operation] != Intent(generation, channel, replace)) return denied()
        return positiveReply { positive.join(operation, generation, channel, replace) }
    }

    override suspend fun status(operation: VoiceOperationId, generation: VoiceGeneration): VoiceHttpReply {
        ensureLive()
        if (capability.value.intents[operation]?.generation != generation) return denied()
        return positiveReply { positive.status(operation, generation) }
    }

    override suspend fun heartbeat(tag: VoiceSessionTag): VoiceHttpReply {
        ensureLive()
        if (tag !in capability.value.sessions) return denied()
        return positiveReply { positive.heartbeat(tag) }
    }

    override suspend fun cancel(operation: VoiceOperationId, generation: VoiceGeneration): VoiceHttpReply {
        ensureOpen()
        if (capability.value.intents[operation]?.generation != generation) return denied()
        val reply = cleanup.cancel(operation, generation)
        ensureOpen()
        return reply
    }

    override suspend fun disconnect(tag: VoiceSessionTag): VoiceHttpReply {
        ensureOpen()
        if (tag !in capability.value.sessions) return denied()
        val reply = cleanup.disconnect(tag)
        ensureOpen()
        return reply
    }

    fun retireAdmission() = capability.update { it.copy(retired = true) }

    /** Enclosing voice drain owns this call; auth cancellation alone must not call it. */
    fun close() {
        capability.update { Capability(retired = true, closed = true) }
        var failed = false
        try { positive.close() } catch (_: Throwable) { failed = true }
        try { cleanup.close() } catch (_: Throwable) { failed = true }
        if (failed) throw IllegalStateException("Voice control HTTP cleanup failed")
    }

    private suspend fun positiveReply(call: suspend () -> VoiceHttpReply): VoiceHttpReply {
        val reply = call()
        // A late grant cannot start media, but its validated identity remains stoppable.
        val tag = when (reply) {
            is VoiceHttpReply.Credentials -> reply.tag
            is VoiceHttpReply.Acknowledged -> reply.tag
            is VoiceHttpReply.Expired -> reply.tag
            is VoiceHttpReply.Pending -> reply.sessionId?.let { VoiceSessionTag(it, reply.generation) }
            else -> null
        }
        if (tag != null) capability.update { if (it.closed) it else it.copy(sessions = it.sessions + tag) }
        ensureLive()
        return reply
    }

    private suspend fun ensureLive() {
        ensureOpen()
        if (capability.value.retired || !account.job.isActive) throw CancellationException(RETIRED)
    }

    private suspend fun ensureOpen() {
        currentCoroutineContext().ensureActive()
        if (capability.value.closed) throw CancellationException(RETIRED)
    }

    private fun denied() = VoiceHttpReply.Failed(VoiceHttpFailure.ACCESS_DENIED)

    companion object {
        private const val RETIRED = "Authenticated voice control retired"
        private val ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

        /** Capture original cleanup credentials before allocating adapters or accepting intent. */
        suspend fun create(account: SocialAuthenticatedAccount, client: HttpClient, baseUrl: String,
                           monotonicMillis: () -> Long): VoiceSessionAccountControl =
            create(account, client, baseUrl, monotonicMillis, VoiceLeaseRequestSequence())

        suspend fun create(account: SocialAuthenticatedAccount, client: HttpClient, baseUrl: String,
                           monotonicMillis: () -> Long, leaseRequests: VoiceLeaseRequestSequence): VoiceSessionAccountControl {
            currentCoroutineContext().ensureActive()
            check(account.job.isActive) { RETIRED }
            invitationDraftId(account.userId.value)
            nearbyHttpsApiBaseUrl(baseUrl)
            val token = account.bearer()?.takeIf { it.isNotBlank() } ?: throw CancellationException(RETIRED)
            currentCoroutineContext().ensureActive()
            check(account.job.isActive) { RETIRED }
            return VoiceSessionAccountControl(account, client, baseUrl, monotonicMillis, token, leaseRequests)
        }
    }
}
