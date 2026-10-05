package ru.sodovaya.volty.data.voice

import ru.sodovaya.volty.data.social.SocialAuthenticatedAccount

internal data class IncomingCallNotificationAttachment(val owner: IncomingCallNotificationOwner,
    val binding: IncomingCallActionBinding)

/** Each creation returns a unique owned notification child; the voice runtime remains borrowed. */
internal fun interface IncomingCallNotificationFactory {
    suspend fun create(account: SocialAuthenticatedAccount, runtime: AuthenticatedVoiceRuntime): IncomingCallNotificationAttachment
}
