package ru.sodovaya.volty.data.voice

import io.ktor.client.HttpClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import ru.sodovaya.volty.data.nearby.nearbyHttpsApiBaseUrl
import ru.sodovaya.volty.data.social.SocialAuthenticatedAccount
import ru.sodovaya.volty.domain.nearby.community.invitationDraftId
import ru.sodovaya.volty.domain.voice.*

/** Auth-bound admission with explicit cleanup on a separate root-owned lifetime. */
internal class AuthenticatedVoiceRuntime private constructor(
    private val account: SocialAuthenticatedAccount,
    private val cleanupScope: CoroutineScope,
    private val control: VoiceSessionAccountControl,
    private val queuedDispatcher: CoroutineDispatcher,
    factory: VoiceSdkSessionFactory,
    pulses: Flow<Unit>,
    monotonicMillis: () -> Long,
    sharingDispatch: (VoiceSharingEffect) -> Unit,
    private val mayAllocateAudio: () -> Boolean,
) {
    private val admissionRetired = MutableStateFlow(false)
    val retired = admissionRetired.asStateFlow()
    private val retirementFailure = MutableStateFlow(false)
    val retirementFailed = retirementFailure.asStateFlow()
    private val runtime = VoiceRuntime(cleanupScope, queuedDispatcher, control, factory, pulses,
        monotonicMillis, sharingDispatch, ::allowsPositiveWork)
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

    suspend fun join(channel: String, operation: VoiceOperationId, replace: Boolean = false): Boolean = withContext(queuedDispatcher) {
        if (!allowsPositiveWork()) return@withContext false
        invitationDraftId(channel)
        invitationDraftId(operation.value)
        val accepted = runtime.join(channel, operation, replace)
        accepted && !admissionRetired.value && account.job.isActive
    }

    /** Disconnect preserves membership and authentication; submission survives caller cancellation. */
    suspend fun disconnect() = withContext(NonCancellable) { runtime.disconnect() }

    suspend fun setMuted(value: Boolean): Boolean = positiveCommand { runtime.setMuted(value) }
    suspend fun setDeafened(value: Boolean): Boolean = positiveCommand { runtime.setDeafened(value) }
    suspend fun enableSharing(): Boolean = positiveCommand { runtime.enableSharing() }
    suspend fun hidePosition() = withContext(NonCancellable) { runtime.hidePosition() }

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

    companion object {
        suspend fun create(account: SocialAuthenticatedAccount, client: HttpClient, baseUrl: String,
                           queuedDispatcher: CoroutineDispatcher, factory: VoiceSdkSessionFactory,
                           pulses: Flow<Unit>, monotonicMillis: () -> Long,
                           sharingDispatch: (VoiceSharingEffect) -> Unit,
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
            val control = VoiceSessionAccountControl.create(account, client, baseUrl, monotonicMillis)
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
