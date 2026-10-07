package ru.sodovaya.volty.data.voice

import io.ktor.client.HttpClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import ru.sodovaya.volty.data.nearby.nearbyHttpsApiBaseUrl
import ru.sodovaya.volty.data.nearby.DirectCallSnapshot
import ru.sodovaya.volty.data.nearby.DirectCallReply
import ru.sodovaya.volty.data.nearby.DirectCallFailure
import ru.sodovaya.volty.data.social.SocialAuthenticatedAccount
import ru.sodovaya.volty.domain.nearby.community.invitationDraftId
import ru.sodovaya.volty.domain.voice.*

/** Auth-bound admission with explicit cleanup on a separate root-owned lifetime. */
internal class AuthenticatedVoiceRuntime private constructor(
    private val account: SocialAuthenticatedAccount,
    private val cleanupScope: CoroutineScope,
    private val control: AccountVoiceSessionControl,
    private val queuedDispatcher: CoroutineDispatcher,
    factory: VoiceSdkSessionFactory,
    pulses: Flow<Unit>,
    private val monotonicMillis: () -> Long,
    sharingDispatch: (VoiceSharingDispatch) -> Unit,
    private val mayAllocateAudio: () -> Boolean,
) {
    /** Exact authentication generation, including replacement login of the same actor. */
    internal fun belongsTo(candidate: SocialAuthenticatedAccount): Boolean = account === candidate
    private val admissionRetired = MutableStateFlow(false)
    private val audioFactory = factory as? VoiceSessionAudioFactory
    private var audioPreparation: Any? = null
    val retired = admissionRetired.asStateFlow()
    private val retirementFailure = MutableStateFlow(false)
    val retirementFailed = retirementFailure.asStateFlow()
    private val disconnectSubmissionFailure = MutableStateFlow(false)
    val disconnectFailed = disconnectSubmissionFailure.asStateFlow()
    /** Platform loss uses this original cleanup root, never the service/Activity caller Job. */
    fun requestDisconnect() {
        cleanupScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try { disconnect() }
            catch (_: Exception) { disconnectSubmissionFailure.value = true }
        }
    }
    private val runtime = VoiceRuntime(cleanupScope, queuedDispatcher, control, factory, pulses,
        monotonicMillis, sharingDispatch, ::allowsAudioWork)
    val state = runtime.state
    val failedLocalCleanup = runtime.failedLocalCleanup
    val nativeInFlight = runtime.nativeInFlight
    val controlInFlight = runtime.controlInFlight
    val pendingRemoteStops = runtime.pendingRemoteStops
    val unconfirmedStops = runtime.unconfirmedStops

    private val guardian = account.scope().launch(start = CoroutineStart.UNDISPATCHED) {
        try { awaitCancellation() }
        finally {
            fenceAdmission()
            withContext(NonCancellable) { submitRetirement() }
        }
    }

    /** Metadata only. Audio still requires the current foreground and explicit join. */
    suspend fun registerOutgoingCall(call: String, callee: String, key: String, operation: VoiceOperationId): Boolean =
        withContext(queuedDispatcher) {
            if (admissionRetired.value || !account.job.isActive) false
            else control.registerOutgoing(call, callee, key, operation)
        }
    suspend fun registerIncomingCall(call: DirectCallSnapshot, operation: VoiceOperationId): Boolean =
        withContext(queuedDispatcher) {
            if (admissionRetired.value || !account.job.isActive) false else control.registerIncoming(call, operation)
        }
    suspend fun incomingCalls(after: String? = null, limit: Int = 20) = control.incoming(after, limit)
    suspend fun callStatus(call: String) = control.callStatus(call)
    internal suspend fun verifyIncomingCall(call: String)=IncomingCallHintVerifier(account,this,monotonicMillis).verify(call)
    /** The UI owns only its waiter. Rejection runs on the original cleanup root without audio. */
    fun requestDeclineIncomingCall(call: DirectCallSnapshot, operation: VoiceOperationId): Deferred<DirectCallReply> =
        cleanupScope.async {
            if (admissionRetired.value || !account.job.isActive || !control.registerIncoming(call, operation))
                DirectCallReply.Failed(DirectCallFailure.ACCESS_DENIED)
            else control.declineIncoming(operation)
        }

    suspend fun join(channel: String, operation: VoiceOperationId, replace: Boolean = false): Boolean = withContext(queuedDispatcher) {
        audioPreparation = null
        if (!allowsAudioWork()) return@withContext false
        invitationDraftId(channel)
        invitationDraftId(operation.value)
        val accepted = runtime.join(channel, operation, replace)
        accepted && !admissionRetired.value && account.job.isActive
    }

    suspend fun joinWithAudio(channel: String, operation: VoiceOperationId, replace: Boolean = false,
                              readPreferences: suspend () -> VoiceAudioPreferences): Boolean {
        val audio = audioFactory?.takeIf { it.requiresPreparation } ?: return false
        val ticket = withContext(queuedDispatcher) {
            if (!allowsPositiveWork()) return@withContext null
            invitationDraftId(channel)
            invitationDraftId(operation.value)
            Any().also { audioPreparation = it }
        } ?: return false
        val snapshot = readPreferences()
        return withContext(queuedDispatcher) {
            if (audioPreparation !== ticket || !allowsPositiveWork()) return@withContext false
            audio.prepare(snapshot)
            val accepted = runtime.join(channel, operation, replace)
            accepted && audioPreparation === ticket && !admissionRetired.value && account.job.isActive
        }
    }

    /** Disconnect preserves membership and authentication; submission survives caller cancellation. */
    suspend fun disconnect() = withContext(NonCancellable) {
        // Combining NonCancellable with a dispatcher change throws on return to an already
        // cancelled caller. Keep its outer boundary and move only the owned command inside.
        withContext(queuedDispatcher) {
            audioPreparation = null
            try {
                runtime.disconnect()
                disconnectSubmissionFailure.value = false
            } catch (failure: Exception) {
                disconnectSubmissionFailure.value = true
                throw failure
            }
        }
    }

    suspend fun configureAudio(operation: VoiceOperationId, preferences: VoiceAudioPreferences): VoiceAudioChangeResult = withContext(queuedDispatcher) {
        if (!allowsAudioWork()) return@withContext VoiceAudioChangeResult.STALE
        val result = runtime.configureAudio(operation, preferences)
        if (allowsAudioWork()) result else VoiceAudioChangeResult.STALE
    }

    suspend fun setMuted(value: Boolean): Boolean = positiveCommand { runtime.setMuted(value) }
    suspend fun setDeafened(value: Boolean): Boolean = positiveCommand { runtime.setDeafened(value) }
    suspend fun setMuted(operation: VoiceOperationId, value: Boolean): Boolean = positiveCommand { runtime.setMuted(operation, value) }
    suspend fun setDeafened(operation: VoiceOperationId, value: Boolean): Boolean = positiveCommand { runtime.setDeafened(operation, value) }
    suspend fun enableSharing(): Boolean = withContext(queuedDispatcher) {
        val operation = runtime.state.value.operationId
        if (!allowsPositiveWork() || operation == null || !control.allowsSharing(operation)) false
        else {runtime.enableSharing(); true}
    }
    /** Exact earned channel session, not the destination UUID or a similarly named call. */
    internal suspend fun canShare(original: VoiceSessionTag): Boolean = withContext(queuedDispatcher) {
        val current = runtime.state.value
        val operation = current.operationId ?: return@withContext false
        val now = try {monotonicMillis()} catch (_: Exception) {return@withContext false}
        allowsPositiveWork() && current.wanted && !current.cleanupPending &&
            current.sessionId == original.sessionId && current.generation == original.generation &&
            now >= 0 && current.leaseDeadlineMillis?.let {now < it} == true && control.allowsSharing(operation)
    }
    suspend fun hidePosition() = withContext(NonCancellable) { runtime.hidePosition() }
    internal suspend fun sharingChannel(original: VoiceSessionTag): String? = withContext(queuedDispatcher) {
        if (!canShare(original)) null else runtime.state.value.operationId?.let(control::sharingChannel)
    }

    /** Fence admission first, then request actual cleanup; cancelling auth is never a stop ACK. */
    suspend fun retire() {
        fenceAdmission()
        guardian.cancel()
        withContext(NonCancellable) { submitRetirement() }
    }

    private fun fenceAdmission() {
        admissionRetired.value = true
        control.retireAdmission()
    }

    private suspend fun submitRetirement() {
        try {
            runtime.beginRetirement()
            retirementFailure.value = false
        } catch (_: Exception) {
            // Records submission failure only. Local/remote resource evidence stays separate.
            retirementFailure.value = true
        }
    }

    private suspend fun positiveCommand(command: suspend () -> Unit): Boolean = withContext(queuedDispatcher) {
        if (!allowsPositiveWork()) false else { command(); true }
    }

    private fun allowsPositiveWork(): Boolean = !admissionRetired.value && account.job.isActive &&
        try { mayAllocateAudio() } catch (_: Exception) { false }

    private fun allowsAudioWork(): Boolean = allowsPositiveWork() && audioFactory?.ready != false

    companion object {
        suspend fun create(account: SocialAuthenticatedAccount, client: HttpClient, baseUrl: String,
                           queuedDispatcher: CoroutineDispatcher, factory: VoiceSdkSessionFactory,
                           pulses: Flow<Unit>, monotonicMillis: () -> Long,
                           sharingDispatch: (VoiceSharingDispatch) -> Unit,
                           mayAllocateAudio: () -> Boolean): AuthenticatedVoiceRuntime {
            currentCoroutineContext().ensureActive()
            check(account.job.isActive) { "Authenticated account retired" }
            invitationDraftId(account.userId.value)
            nearbyHttpsApiBaseUrl(baseUrl)
            val parent = account.retirementParentScope()
            val parentJob = requireNotNull(parent.coroutineContext[Job]) { "Voice retirement requires application Job" }
            check(parentJob.isActive) { "Voice retirement parent stopped" }
            require(parentJob !== account.job) { "Voice cleanup must outlive account cancellation" }
            require(queuedDispatcher.isDispatchNeeded(parent.coroutineContext)) { "Queued serial dispatcher required" }
            val control = AccountVoiceSessionControl.create(account, client, baseUrl, monotonicMillis)
            val lifetime = SupervisorJob(parentJob)
            val scope = CoroutineScope(parent.coroutineContext + lifetime + queuedDispatcher)
            return try {
                AuthenticatedVoiceRuntime(account, scope, control, queuedDispatcher, factory, pulses,
                    monotonicMillis, sharingDispatch, mayAllocateAudio)
            } catch (failure: Throwable) {
                lifetime.cancel()
                try { control.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                throw failure
            }
        }
    }
}
