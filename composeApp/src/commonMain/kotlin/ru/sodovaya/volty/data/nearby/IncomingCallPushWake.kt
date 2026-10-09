package ru.sodovaya.volty.data.nearby

import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import ru.sodovaya.volty.data.social.SocialAuthenticatedAccount

/** One bounded receiver operation, including restored-account registration and verified discovery. */
internal class IncomingCallPushWake(private val accounts: StateFlow<SocialAuthenticatedAccount?>,
    private val owner: PushRegistrationOwner, private val gate: IncomingCallPushGate,
    private val diagnostic: (CallPushDiagnosticEvent) -> Unit = {}) {
    suspend fun receive(data: Map<String, String>): Boolean {
        val hint = IncomingCallPushHint.decode(data) ?: return false
        record(CallPushDiagnosticEvent.ACCOUNT_WAITING)
        val result = withTimeoutOrNull(15_000) {
            val account = accounts.filterNotNull().first()
            record(CallPushDiagnosticEvent.ACCOUNT_RESTORED)
            if (!owner.wake(account)) {
                record(CallPushDiagnosticEvent.REGISTRATION_UNAVAILABLE); false
            } else {
                record(CallPushDiagnosticEvent.REGISTRATION_READY); gate.receive(hint)
            }
        }
        if (result == null) record(CallPushDiagnosticEvent.WAKE_TIMEOUT)
        return result ?: false
    }
    private fun record(event: CallPushDiagnosticEvent) {
        try {diagnostic(event)} catch (_: Exception) { /* Never affect the borrowed wake. */ }
    }
}
