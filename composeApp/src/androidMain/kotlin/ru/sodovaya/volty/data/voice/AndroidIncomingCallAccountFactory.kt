package ru.sodovaya.volty.data.voice

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import ru.sodovaya.volty.data.social.SocialAuthenticatedAccount

/** Lazy platform configuration; root coordinator creates once per actual authenticated account. */
internal class AndroidIncomingCallAccountFactory(context: Context,
    private val registry: IncomingCallActionRegistry, private val queuedDispatcher: CoroutineDispatcher,
    private val pulses: Flow<Unit>, private val clock: () -> Long, private val nextTicketId: () -> String) : IncomingCallNotificationFactory {
    private val port = AndroidIncomingCallNotificationPort(context, AndroidIncomingCallPendingIntents(context, registry))
    override suspend fun create(account: SocialAuthenticatedAccount, runtime: AuthenticatedVoiceRuntime): IncomingCallNotificationAttachment =
        withContext(queuedDispatcher) {
            currentCoroutineContext().ensureActive()
            val owner = IncomingCallNotificationOwner(account, runtime, queuedDispatcher, pulses, clock, port, nextTicketId)
            try {IncomingCallNotificationAttachment(owner, registry.bind(account, owner))}
            catch (failure: Throwable) {
                withContext(NonCancellable) {owner.close()}
                throw failure
            }
        }
}
