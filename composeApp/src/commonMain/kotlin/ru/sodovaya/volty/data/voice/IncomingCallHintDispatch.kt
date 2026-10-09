package ru.sodovaya.volty.data.voice

import kotlinx.coroutines.*
import ru.sodovaya.volty.data.social.SocialAuthenticatedAccount
import ru.sodovaya.volty.domain.nearby.community.invitationDraftId

/** Hint submission owns no media; callers only borrow the account-owned result. */
internal class IncomingCallHintDispatch(private val queuedDispatcher: CoroutineDispatcher,
    private val currentAccount: () -> SocialAuthenticatedAccount?,
    private val notifications: IncomingCallAccountCoordinator,
    private val acquireRuntime: suspend (SocialAuthenticatedAccount) -> AuthenticatedVoiceRuntime) {
    /** Without a fence true means queued. With a fence, await verified offer publication; never media admission. */
    suspend fun submit(account: SocialAuthenticatedAccount, callId: String,
        beforePublish: (suspend () -> Boolean)? = null): Deferred<Boolean>? = withContext(queuedDispatcher) {
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
                if (beforePublish == null) attachment.owner.hint(callId)
                else attachment.owner.verifiedHint(callId, beforePublish)
            } catch (cancelled: CancellationException) {
                if (!currentCoroutineContext().isActive) throw cancelled
                false
            } catch (_: Exception) {false}
        }
    }

    private fun live(account: SocialAuthenticatedAccount) = account.job.isActive && currentAccount() === account
}
