package ru.sodovaya.volty.data.voice

import io.ktor.client.HttpClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import ru.sodovaya.volty.data.nearby.ChannelSharingHttpPort
import ru.sodovaya.volty.data.nearby.nearbyHttpsApiBaseUrl
import ru.sodovaya.volty.data.social.SocialAuthenticatedAccount
import ru.sodovaya.volty.domain.nearby.*
import ru.sodovaya.volty.domain.voice.VoiceSessionTag
import ru.sodovaya.volty.domain.nearby.community.invitationDraftId

/** Owns consent HTTP children and original negative work, never borrowed authentication/audio. */
internal class ChannelSharingAccountControl private constructor(private val account: SocialAuthenticatedAccount,
    private val runtime: AuthenticatedVoiceRuntime, private val original: VoiceSessionTag, client: HttpClient,
    baseUrl: String, private val queuedDispatcher: CoroutineDispatcher, clock: () -> Long, initialBearer: String) {
    private data class Capability(val retired: Boolean = false, val closed: Boolean = false,
        val bearer: String? = null, val generations: Set<String> = emptySet())
    private val capability = MutableStateFlow(Capability(bearer = initialBearer))
    private val lifetime = SupervisorJob(checkNotNull(account.retirementParentScope().coroutineContext[Job]))
    private val cleanupScope = CoroutineScope(account.retirementParentScope().coroutineContext + lifetime + queuedDispatcher)
    private val positive = try {
        ChannelSharingHttpPort(client, baseUrl, {
            ensurePositive()
            val token = account.bearer()
            ensurePositive()
            if (token != null) capability.update {if (it.closed || it.retired) it else it.copy(bearer = token)}
            ensurePositive(); token
        }, clock)
    } catch (failure: Throwable) {lifetime.cancel(); throw failure}
    private val negative = try {ChannelSharingHttpPort(client, baseUrl, {
        currentCoroutineContext().ensureActive()
        if (capability.value.closed) throw CancellationException("Sharing control closed")
        capability.value.bearer // Original captured token; no refresh/new-account lookup.
    }, clock)} catch (failure: Throwable) {
        try {positive.close()} catch (cleanup: Throwable) {failure.addSuppressed(cleanup)}
        lifetime.cancel(); throw failure
    }
    private var closeRequested = false
    private val cleanupFailure = MutableStateFlow(false)
    val cleanupFailed = cleanupFailure.asStateFlow()
    private var pendingRevokes = 0
    private val revokes = mutableMapOf<String, Deferred<ChannelSharingReply>>()
    private val accountGuardian = account.scope().launch(start = CoroutineStart.UNDISPATCHED) {
        try {awaitCancellation()} finally {retireAdmission()}
    }
    private var rootGuardian: Job? = null
    init {
        rootGuardian = cleanupScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {awaitCancellation()} finally {withContext(NonCancellable) {withContext(queuedDispatcher) {closeOwned()}}}
        }
    }

    suspend fun start(generation: String, mode: ChannelSharingMode, ttl: ChannelSharingTtl): ChannelSharingReply = withContext(queuedDispatcher) {
        invitationDraftId(generation); ensurePositive()
        // Record before issue: even an unknown/cancelled response may have committed remotely.
        capability.update {if (it.closed || it.retired) it else it.copy(generations = it.generations + generation)}
        ensurePositive()
        positive.start(tag(generation), mode, ttl).also {ensurePositive()}
    }
    suspend fun status(generation: String): ChannelSharingReply = withContext(queuedDispatcher) {
        invitationDraftId(generation); ensurePositive()
        if (generation !in capability.value.generations) denied()
        else positive.status(tag(generation)).also {ensurePositive()}
    }
    suspend fun requestRevoke(generation: String): Deferred<ChannelSharingReply> = withContext(queuedDispatcher) {
        currentCoroutineContext().ensureActive(); invitationDraftId(generation)
        if (generation !in capability.value.generations) return@withContext CompletableDeferred(denied())
        if (closeRequested || capability.value.closed || !lifetime.isActive)
            return@withContext CompletableDeferred(ChannelSharingReply.Failed(ChannelSharingFailure.RETIRED))
        revokes[generation]?.takeIf {!it.isCompleted}?.let {return@withContext it}
        pendingRevokes++
        val task = cleanupScope.async(start = CoroutineStart.LAZY) {negative.revoke(tag(generation))}
        revokes[generation] = task
        // Count admitted queued work, including producers cancelled before their body starts.
        task.invokeOnCompletion {
            CoroutineScope(NonCancellable + queuedDispatcher).launch {
                pendingRevokes--
                if (closeRequested && pendingRevokes == 0) closeOwned()
            }
        }
        task.start(); task
    }
    fun retireAdmission() = capability.update {it.copy(retired = true)}

    /** False means pending original work or failed child close; never a borrowed runtime stop. */
    suspend fun close(): Boolean = withContext(NonCancellable) {withContext(queuedDispatcher) {
        retireAdmission(); closeRequested = true
        if (pendingRevokes > 0) false else {closeOwned(); !cleanupFailure.value}
    }}
    private fun closeOwned() {
        if (capability.value.closed) return
        capability.update {Capability(retired = true, closed = true)}
        try {positive.close()} catch (_: Throwable) {cleanupFailure.value = true}
        try {negative.close()} catch (_: Throwable) {cleanupFailure.value = true}
        accountGuardian.cancel(); rootGuardian?.cancel(); lifetime.complete()
    }
    private fun tag(generation: String) = ChannelSharingTag(original.sessionId.value, generation)
    private fun denied() = ChannelSharingReply.Failed(ChannelSharingFailure.ACCESS_DENIED)
    private suspend fun ensurePositive() {
        currentCoroutineContext().ensureActive()
        val current = capability.value
        if (current.closed || current.retired || !lifetime.isActive || !account.job.isActive || !runtime.canShare(original))
            throw CancellationException("Original sharing session retired")
    }
    companion object {
        suspend fun create(account: SocialAuthenticatedAccount, runtime: AuthenticatedVoiceRuntime,
            original: VoiceSessionTag, client: HttpClient, baseUrl: String, queuedDispatcher: CoroutineDispatcher,
            clock: () -> Long): ChannelSharingAccountControl {
            currentCoroutineContext().ensureActive()
            require(runtime.belongsTo(account)) {"Sharing runtime belongs to another account"}
            check(account.job.isActive && runtime.canShare(original)) {"Actual active channel session required"}
            invitationDraftId(account.userId.value); invitationDraftId(original.sessionId.value); nearbyHttpsApiBaseUrl(baseUrl)
            val parent = account.retirementParentScope()
            val root = requireNotNull(parent.coroutineContext[Job]) {"Sharing requires original application Job"}
            require(root !== account.job && root.isActive && queuedDispatcher.isDispatchNeeded(parent.coroutineContext))
            val token = account.bearer()?.takeIf {it.isNotBlank()} ?: throw CancellationException("Sharing account retired")
            currentCoroutineContext().ensureActive()
            check(account.job.isActive && root.isActive && runtime.canShare(original)) {"Original channel session retired"}
            return ChannelSharingAccountControl(account, runtime, original, client, baseUrl, queuedDispatcher, clock, token)
        }
    }
}
