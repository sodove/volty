package ru.sodovaya.volty.data.voice

import kotlinx.coroutines.*
import ru.sodovaya.volty.data.social.SocialAuthenticatedAccount
import ru.sodovaya.volty.domain.nearby.community.invitationDraftId

/** Hint submission owns no media; callers only borrow the account-owned result. */
internal class IncomingCallHintDispatch(private val queuedDispatcher: CoroutineDispatcher,
    private val currentAccount: () -> SocialAuthenticatedAccount?,
    private val notifications: IncomingCallAccountCoordinator,
    private val acquireRuntime: suspend (SocialAuthenticatedAccount) -> AuthenticatedVoiceRuntime) {
    /** True means queued for verification, never a delivery or media acknowledgement. */
    suspend fun submit(account: SocialAuthenticatedAccount, callId: String): Deferred<Boolean>? = withContext(queuedDispatcher) {
        currentCoroutineContext().ensureActive()
        try {invitationDraftId(callId)} catch (_: IllegalArgumentException) {return@withContext null}
        if (!live(account)) return@withContext null
        require(queuedDispatcher.isDispatchNeeded(account.scope().coroutineContext)) {"Queued serial dispatcher required"}
        // Receiver/UI Job is deliberately absent: the exact account owns this submission.
        CoroutineScope(account.scope().coroutineContext + queuedDispatcher).async {
            try {
                if (!live(account)) return@async false
                val runtime = acquireRuntime(account)
                if (!live(account) || !runtime.belongsTo(account) || runtime.retired.value) return@async false
                val attachment = notifications.acquire(account, runtime)
                if (!live(account) || runtime.retired.value) return@async false
                attachment.owner.hint(callId)
            } catch (cancelled: CancellationException) {
                if (!currentCoroutineContext().isActive) throw cancelled
                false
            } catch (_: Exception) {false}
        }
    }

    private fun live(account: SocialAuthenticatedAccount) = account.job.isActive && currentAccount() === account
}
