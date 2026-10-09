package ru.sodovaya.volty.data.nearby

import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import ru.sodovaya.volty.data.social.SocialAuthenticatedAccount

/** One bounded receiver operation, including restored-account registration and verified discovery. */
internal class IncomingCallPushWake(private val accounts: StateFlow<SocialAuthenticatedAccount?>,
    private val owner: PushRegistrationOwner, private val gate: IncomingCallPushGate) {
    suspend fun receive(data: Map<String, String>): Boolean {
        val hint = IncomingCallPushHint.decode(data) ?: return false
        return withTimeoutOrNull(15_000) {
            val account = accounts.filterNotNull().first()
            owner.wake(account) && gate.receive(hint)
        } ?: false
    }
}
