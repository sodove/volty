package ru.sodovaya.volty.di.nearby

import io.ktor.client.HttpClient
import ru.sodovaya.volty.data.social.SocialAuthenticatedAccount
import ru.sodovaya.volty.domain.nearby.community.InvitationDraftStore
import ru.sodovaya.volty.domain.nearby.community.InvitationSecretGenerator
import ru.sodovaya.volty.domain.nearby.community.invitationDraftId
import ru.sodovaya.volty.domain.radio.RadioKeyStore
import ru.sodovaya.volty.data.nearby.nearbyHttpsApiBaseUrl
import ru.sodovaya.volty.data.nearby.CommunityCacheStorage

/** Account-bound construction; closing a consumer never closes its authentication. */
class NearbyCommunityAccountFactory(
    private val client: HttpClient,
    baseUrl: String,
    private val storeForActor: (String) -> InvitationDraftStore,
    private val generator: InvitationSecretGenerator,
    private val radioKeyStore: RadioKeyStore? = null,
    private val communityCacheStorage: CommunityCacheStorage? = null,
) {
    private val root = nearbyHttpsApiBaseUrl(baseUrl)

    fun create(account: SocialAuthenticatedAccount): NearbyCommunityAccount {
        check(account.job.isActive) { "Authenticated account retired" }
        val actor = account.userId.value
        invitationDraftId(actor)
        val store = storeForActor(actor)
        check(account.job.isActive) { "Authenticated account retired" }
        return NearbyCommunityAccount(account, store, generator, client, root, radioKeyStore, communityCacheStorage)
    }
}
