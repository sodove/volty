package ru.sodovaya.volty.di.nearby

import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import ru.sodovaya.volty.data.social.SocialAuthenticatedAccount
import ru.sodovaya.volty.data.voice.AuthenticatedCallRosterOwner
import ru.sodovaya.volty.data.voice.VoiceConversationCallRosterFactory
import ru.sodovaya.volty.domain.voice.DirectCallRosterTag
import ru.sodovaya.volty.domain.voice.VoiceSessionTag

/** Configuration only. Authority and read lifetime are acquired for each earned session. */
internal class DirectCallRosterAccountFactory(
    private val client: HttpClient,
    private val baseUrl: String,
    private val queuedDispatcher: CoroutineDispatcher,
    private val monotonicMillis: () -> Long,
    private val pulses: Flow<Unit>,
) : VoiceConversationCallRosterFactory {
    override suspend fun create(account: SocialAuthenticatedAccount, tag: DirectCallRosterTag,
        own: VoiceSessionTag): AuthenticatedCallRosterOwner = AuthenticatedCallRosterOwner.create(
        account, client, baseUrl, tag, own, queuedDispatcher, pulses, monotonicMillis,
    )
}
