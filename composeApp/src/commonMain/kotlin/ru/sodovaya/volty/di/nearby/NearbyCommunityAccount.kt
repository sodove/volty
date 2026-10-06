package ru.sodovaya.volty.di.nearby

import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.sodovaya.volty.data.nearby.NearbyAccountCredentials
import ru.sodovaya.volty.data.nearby.NearbyAccountHttpSession
import ru.sodovaya.volty.data.nearby.nearbyHttpsApiBaseUrl
import ru.sodovaya.volty.data.social.SocialAuthenticatedAccount
import ru.sodovaya.volty.domain.nearby.community.InvitationCreationDraftOwner
import ru.sodovaya.volty.domain.nearby.community.InvitationDraftStore
import ru.sodovaya.volty.domain.nearby.community.InvitationSecretGenerator
import ru.sodovaya.volty.domain.nearby.community.invitationDraftId
import ru.sodovaya.volty.presentation.nearby.CommunityBrowser

/** Composes community commands, browser state, and invitation drafts for one account lifetime. */
class NearbyCommunityAccount private constructor(
    parent: CoroutineScope,
    val actorId: String,
    store: InvitationDraftStore,
    generator: InvitationSecretGenerator,
    baseUrl: String,
    apiFactory: (Job) -> NearbyAccountHttpSession,
) {
    constructor(
        parent: CoroutineScope,
        credentials: NearbyAccountCredentials,
        store: InvitationDraftStore,
        generator: InvitationSecretGenerator,
        client: HttpClient,
        baseUrl: String,
    ) : this(parent, credentials.actorId, store, generator, baseUrl,
        { job -> NearbyAccountHttpSession(credentials, client, baseUrl, job) })

    internal constructor(
        account: SocialAuthenticatedAccount,
        store: InvitationDraftStore,
        generator: InvitationSecretGenerator,
        client: HttpClient,
        baseUrl: String,
    ) : this(account.scope(), account.userId.value, store, generator, baseUrl,
        { NearbyAccountHttpSession(account, client, baseUrl) })

    private val accountJob: Job = requireNotNull(parent.coroutineContext[Job]) {
        "Nearby community account requires a parent Job"
    }

    init {
        invitationDraftId(actorId)
        require(store.actorId == actorId) { "Invitation draft store actor does not match account" }
        nearbyHttpsApiBaseUrl(baseUrl)
    }

    private val workspaceLifetime = SupervisorJob(accountJob)

    val api = apiFactory(accountJob)
    val invitations = InvitationCreationDraftOwner(
        actorId,
        store,
        generator,
        api.invitations,
        accountJob,
    )
    val browser = CommunityBrowser(parent, api.communities)

    private val cleanupMutex = Mutex()
    private val cleanupFailure = MutableStateFlow(false)
    val cleanupFailed: StateFlow<Boolean> = cleanupFailure.asStateFlow()

    private val guardian = parent.launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } finally {
            retireSynchronously()
            try {
                withContext(NonCancellable) { cleanup() }
            } catch (_: Throwable) {
                cleanupFailure.value = true
            }
        }
    }

    fun refresh(credentials: NearbyAccountCredentials) = api.refresh(credentials)

    fun workspace(screen: CoroutineScope, nextId: () -> String) =
        ru.sodovaya.volty.presentation.nearby.CommunityWorkspace(screen, workspaceLifetime, api.communities, api.removals, nextId)

    fun channelCreation(screen: CoroutineScope, workspace: ru.sodovaya.volty.presentation.nearby.CommunityWorkspace,
                        nextId: () -> String) =
        ru.sodovaya.volty.presentation.nearby.ChannelCreationWorkspace(screen, workspaceLifetime, actorId, workspace, api.channels, nextId)

    fun invitationCreation(screen: CoroutineScope, workspace: ru.sodovaya.volty.presentation.nearby.CommunityWorkspace,
                           nextId: () -> String) =
        ru.sodovaya.volty.presentation.nearby.InvitationCreationWorkspace(screen, workspaceLifetime, actorId, workspace, invitations, nextId)

    fun invitationAcceptance(screen: CoroutineScope, workspace: ru.sodovaya.volty.presentation.nearby.CommunityWorkspace,
                             nextId: () -> String) =
        ru.sodovaya.volty.presentation.nearby.InvitationAcceptanceWorkspace(screen, workspaceLifetime, workspace, api.invitations, nextId)

    suspend fun retire() {
        // Fence preparation and HTTP permissions before reaching the first suspension point.
        retireSynchronously()
        guardian.cancel()
        withContext(NonCancellable) { cleanup() }
    }

    private fun retireSynchronously() {
        workspaceLifetime.cancel()
        try {
            invitations.retire()
        } catch (_: Throwable) {
            // cleanup() retries retirement and records whether retrying also failed.
        }
        try {
            api.retire()
        } catch (_: Throwable) {
            // cleanup() retries closure and records whether the retry also failed.
        }
    }

    private suspend fun cleanup() = cleanupMutex.withLock {
        var failed = false
        try {
            invitations.retire()
        } catch (_: Throwable) {
            failed = true
        }
        try {
            api.retire()
        } catch (_: Throwable) {
            failed = true
        }
        try {
            browser.close()
        } catch (_: Throwable) {
            failed = true
        }
        cleanupFailure.value = failed
    }
}
