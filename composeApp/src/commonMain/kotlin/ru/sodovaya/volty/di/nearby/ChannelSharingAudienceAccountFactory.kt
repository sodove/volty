package ru.sodovaya.volty.di.nearby

import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineDispatcher
import ru.sodovaya.volty.data.social.SocialAuthenticatedAccount
import ru.sodovaya.volty.data.voice.AuthenticatedVoiceRuntime
import ru.sodovaya.volty.data.voice.ChannelSharingAudienceSession
import ru.sodovaya.volty.data.voice.VoiceConversationChannelAudienceFactory
import ru.sodovaya.volty.domain.voice.VoiceSessionTag

/** Uses the already-earned runtime; reading the map never prepares another audio session. */
internal class ChannelSharingAudienceAccountFactory(
    private val client: HttpClient, private val baseUrl: String,
    private val queued: CoroutineDispatcher, private val clock: () -> Long,
) : VoiceConversationChannelAudienceFactory {
    override suspend fun create(account: SocialAuthenticatedAccount, runtime: AuthenticatedVoiceRuntime,
        original: VoiceSessionTag): ChannelSharingAudienceSession = ChannelSharingAudienceSession.create(
        account, runtime, original, client, baseUrl, queued, clock,
    )
}
