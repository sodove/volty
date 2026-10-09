package ru.sodovaya.volty.data.nearby

import ru.sodovaya.volty.data.social.SocialAuthenticatedAccount
import kotlinx.coroutines.*

/** Push contains no authority: only a current local binding may request fresh backend verification. */
internal class IncomingCallPushGate(private val currentAccount: () -> SocialAuthenticatedAccount?,
    private val ledger: PushInstallationLedger,
    private val verify: suspend (SocialAuthenticatedAccount, String, suspend () -> Boolean) -> Boolean) {
    suspend fun receive(hint: IncomingCallPushHint): Boolean = try {
        currentCoroutineContext().ensureActive()
        val account = currentAccount()
        if (account == null || !live(account)) false
        else {
            val binding = ledger.current()
            currentCoroutineContext().ensureActive()
            if (!live(account) || !matches(binding, account, hint)) false
            else {
                val accepted = verify(account, hint.callId) {
                    currentCoroutineContext().ensureActive()
                    if (!live(account)) false
                    else {
                        val latest = ledger.current()
                        currentCoroutineContext().ensureActive()
                        live(account) && matches(latest, account, hint)
                    }
                }
                currentCoroutineContext().ensureActive()
                if (!accepted || !live(account)) false
                else {
                    val latest = ledger.current()
                    currentCoroutineContext().ensureActive()
                    live(account) && matches(latest, account, hint)
                }
            }
        }
    } catch (_: CancellationException) {
        currentCoroutineContext().ensureActive(); false
    } catch (_: Exception) {false}
    private fun live(account: SocialAuthenticatedAccount) = account.job.isActive && currentAccount() === account
    private fun matches(state: PushInstallationSnapshot?, account: SocialAuthenticatedAccount, hint: IncomingCallPushHint) =
        state != null && state.acknowledged && state.intent.token != null && state.actorId == account.userId.value &&
            state.intent.bindingId == hint.bindingId && state.intent.generation == hint.generation
}
