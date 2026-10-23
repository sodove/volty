package ru.sodovaya.volty.di.nearby

import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import ru.sodovaya.volty.data.social.SocialAuthenticatedAccount
import ru.sodovaya.volty.data.voice.VoiceRosterAccountSession
import ru.sodovaya.volty.domain.voice.VoiceIdentityDirectory
import ru.sodovaya.volty.domain.voice.VoiceRosterTag

/** Supplies platform clock/scheduling; every create borrows one exact authenticated lifetime. */
internal class VoiceRosterAccountFactory(
    private val client: HttpClient,
    private val baseUrl: String,
    private val queuedDispatcher: CoroutineDispatcher,
    private val monotonicMillis: () -> Long,
    private val pulses: Flow<Unit>,
    private val identities: VoiceIdentityDirectory? = null,
) {
    fun create(account: SocialAuthenticatedAccount, tag: VoiceRosterTag) = VoiceRosterAccountSession(
        account, client, baseUrl, tag, queuedDispatcher, pulses, monotonicMillis, identities,
    )
}
