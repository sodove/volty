package ru.sodovaya.volty.data.nearby

import ru.sodovaya.volty.data.social.SocialAuthenticatedAccount
import kotlinx.coroutines.*

/** Push contains no authority: only a current local binding may request fresh backend verification. */
internal class IncomingCallPushGate(private val currentAccount: () -> SocialAuthenticatedAccount?,
    private val ledger: PushInstallationLedger,
    private val diagnostic: (CallPushDiagnosticEvent) -> Unit = {},
    private val verify: suspend (SocialAuthenticatedAccount, String, suspend () -> Boolean) -> Boolean) {
    suspend fun receive(hint: IncomingCallPushHint): Boolean = try {
        currentCoroutineContext().ensureActive()
        val account = currentAccount()
        if (account == null || !live(account)) rejected(CallPushDiagnosticEvent.ACCOUNT_REJECTED)
        else {
            val binding = ledger.current()
            currentCoroutineContext().ensureActive()
            if (!live(account)) rejected(CallPushDiagnosticEvent.ACCOUNT_REJECTED)
            else if (!matches(binding, account, hint)) rejected(CallPushDiagnosticEvent.BINDING_REJECTED)
            else {
                record(CallPushDiagnosticEvent.VERIFICATION_STARTED)
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
                if (!live(account)) rejected(CallPushDiagnosticEvent.ACCOUNT_REJECTED)
                else if (!accepted) rejected(CallPushDiagnosticEvent.VERIFICATION_REJECTED)
                else {
                    val latest = ledger.current()
                    currentCoroutineContext().ensureActive()
                    if (!live(account)) rejected(CallPushDiagnosticEvent.ACCOUNT_REJECTED)
                    else if (!matches(latest, account, hint)) rejected(CallPushDiagnosticEvent.BINDING_REJECTED)
                    else { record(CallPushDiagnosticEvent.VERIFICATION_ACCEPTED); true }
                }
            }
        }
    } catch (_: CancellationException) {
        record(CallPushDiagnosticEvent.CANCELLED)
        currentCoroutineContext().ensureActive(); false
    } catch (_: Exception) {rejected(CallPushDiagnosticEvent.VERIFICATION_ERROR)}
    private fun record(event: CallPushDiagnosticEvent) {
        try {diagnostic(event)} catch (_: Exception) { /* Diagnostics cannot change call authority. */ }
    }
    private fun rejected(event: CallPushDiagnosticEvent): Boolean {record(event); return false}
    private fun live(account: SocialAuthenticatedAccount) = account.job.isActive && currentAccount() === account
    private fun matches(state: PushInstallationSnapshot?, account: SocialAuthenticatedAccount, hint: IncomingCallPushHint) =
        state != null && state.acknowledged && state.intent.token != null && state.actorId == account.userId.value &&
            state.intent.bindingId == hint.bindingId && state.intent.generation == hint.generation
}
