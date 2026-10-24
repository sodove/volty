package ru.sodovaya.volty.data.nearby

import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import ru.sodovaya.volty.domain.nearby.community.*
import ru.sodovaya.volty.data.social.SocialAuthenticatedAccount
import ru.sodovaya.volty.domain.radio.RadioKeyStore

/** Owns six bounded HTTP adapters for one authenticated Nearby account lifetime. */
class NearbyAccountHttpSession private constructor(
    val actorId: String,
    private val binding: NearbyAccountBinding?,
    private val authority: SocialAuthenticatedAccount?,
    private val accountJob: Job?,
    client: HttpClient,
    baseUrl: String,
    private val radioKeyStore: RadioKeyStore?,
    private val nowMillis: () -> Long,
    communityCacheStorage: CommunityCacheStorage? = null,
) {
    constructor(
        credentials: NearbyAccountCredentials,
        client: HttpClient,
        baseUrl: String,
        accountJob: Job? = null,
        radioKeyStore: RadioKeyStore? = null,
        // Before nowMillis, so a trailing clock lambda still binds to the clock.
        communityCacheStorage: CommunityCacheStorage? = null,
        nowMillis: () -> Long = ::radioClockMillis,
    ) : this(credentials.actorId, NearbyAccountBinding(credentials, accountJob), null, accountJob, client, baseUrl,
        radioKeyStore, nowMillis, communityCacheStorage)

    internal constructor(
        account: SocialAuthenticatedAccount,
        client: HttpClient,
        baseUrl: String,
        radioKeyStore: RadioKeyStore? = null,
        // Before nowMillis, so a trailing clock lambda still binds to the clock.
        communityCacheStorage: CommunityCacheStorage? = null,
        nowMillis: () -> Long = ::radioClockMillis,
    ) : this(account.userId.value, null, account, account.job, client, baseUrl, radioKeyStore, nowMillis,
        communityCacheStorage)

    private val root = nearbyHttpsApiBaseUrl(baseUrl)
    init { invitationDraftId(actorId) }
    private val retired = MutableStateFlow(false)

    private val communitiesHttp = CommunityHttpPort(client, "$root/communities") { currentBearer() }
    private val removalsHttp = CommunityRemovalHttpPort(client, "$root/communities") { currentBearer() }
    private val channelsHttp = ChannelCommandsHttpPort(client, "$root/communities") { currentBearer() }
    private val invitationsHttp = InvitationHttpPort(client, root) { currentBearer() }
    private val invitationListingHttp = InvitationListingHttpPort(client, root) { currentBearer() }
    private val membersHttp=CommunityMembersHttpPort(client,root,actorId) {currentBearer()}
    private val radioKeyHttp = RadioKeyHttpPort(client, root, actorId) { currentBearer() }
    /** The server list and pages as last answered, for a start with no internet (the radio lives there). */
    private val offline = communityCacheStorage?.let { CommunityOfflineCache(it, actorId) }

    // Radio keys refresh in the background after a membership list loads; failures never reach the list.
    // Deliberately not a child of the account Job (borrowed Jobs must gain no children); tied to it by completion.
    private val radioJob = SupervisorJob()
    private val radioScope = CoroutineScope(radioJob + Dispatchers.Default.limitedParallelism(1))
    init { if (radioKeyStore != null) (accountJob ?: authority?.job)?.invokeOnCompletion { radioJob.cancel() } }
    private val radioKeysRequested = mutableSetOf<String>() // touched only on radioScope's single thread

    private fun refreshRadioKeys(listed: List<CommunitySummary>) {
        val store = radioKeyStore ?: return
        val ids = listed.map { it.id }
        radioScope.launch {
            for (community in ids) {
                if (!radioKeysRequested.add(community)) continue
                val key = try { radioKeyHttp.fetch(community) } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { null }
                val stored = key != null && try { store.put(actorId, key, nowMillis()); true }
                    catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { false }
                if (!stored) radioKeysRequested.remove(community) // a later listing may retry
            }
        }
    }

    val communities: CommunityPort = object : CommunityPort {
        override suspend fun memberships(after: String?, limit: Int) = guarded {
            val live: suspend () -> CommunityResult<List<CommunitySummary>> = {
                communitiesHttp.memberships(after, limit).also { result ->
                    if (result is CommunityResult.Ready) refreshRadioKeys(result.value)
                }
            }
            offline?.memberships(after, live) ?: live()
        }

        override suspend fun catalog(query: String, after: String?, limit: Int) = guarded {
            communitiesHttp.catalog(query, after, limit)
        }

        override suspend fun detail(community: String) = guarded {
            val live: suspend () -> CommunityResult<CommunityDetails> = { communitiesHttp.detail(community) }
            offline?.detail(community, live) ?: live()
        }

        override suspend fun create(
            community: String,
            name: String,
            description: String,
            visibility: CommunityVisibility,
        ) = guarded { communitiesHttp.create(community, name, description, visibility) }

        override suspend fun joinPublic(community: String) = guarded { communitiesHttp.joinPublic(community) }
    }

    val removals: CommunityRemovalPort = object : CommunityRemovalPort {
        override suspend fun leave(community: String, operation: String, successor: String?) = guarded {
            removalsHttp.leave(community, operation, successor)
        }

        override suspend fun delete(community: String, operation: String) = guarded {
            removalsHttp.delete(community, operation)
        }
    }

    val channels: ChannelCommandsPort = object : ChannelCommandsPort {
        override suspend fun create(community: String, channel: String, name: String) = guarded {
            channelsHttp.create(community, channel, name)
        }

        override suspend fun delete(community: String, channel: String, operation: String) = guarded {
            channelsHttp.delete(community, channel, operation)
        }
    }

    val invitations: InvitationPort = object : InvitationPort {
        override suspend fun create(
            community: String,
            invitation: String,
            token: String,
            ttlMillis: Long,
            maxUses: Int,
        ) = guarded { invitationsHttp.create(community, invitation, token, ttlMillis, maxUses) }

        override suspend fun revoke(community: String, invitation: String) = guarded {
            invitationsHttp.revoke(community, invitation)
        }

        override suspend fun accept(operation: String, token: String) = guarded {
            invitationsHttp.accept(operation, token)
        }
    }

    val invitationListing: InvitationListingPort = object : InvitationListingPort {
        override suspend fun list(community: String, after: String?, limit: Int) = guarded {
            invitationListingHttp.list(community, after, limit)
        }
    }

    val members:CommunityMembersPort=object:CommunityMembersPort {
        override suspend fun list(community:String,after:String?,limit:Int)=guarded {membersHttp.list(community,after,limit)}
    }

    fun refresh(credentials: NearbyAccountCredentials) {
        require(credentials.actorId == actorId)
        check(!retired.value && accountJob?.isActive != false) { RETIRED }
        checkNotNull(binding) { "Authenticated account owns token refresh" }.refresh(credentials)
    }

    fun retire() {
        // Consumer retirement never retires authentication or cancels its borrowed Job.
        retired.value = true
        radioJob.cancel()
        binding?.retire()

        var closeFailed = false
        try { communitiesHttp.close() } catch (_: Throwable) { closeFailed = true }
        try { removalsHttp.close() } catch (_: Throwable) { closeFailed = true }
        try { channelsHttp.close() } catch (_: Throwable) { closeFailed = true }
        try { invitationsHttp.close() } catch (_: Throwable) { closeFailed = true }
        try { invitationListingHttp.close() } catch (_: Throwable) { closeFailed = true }
        try { membersHttp.close() } catch (_: Throwable) { closeFailed = true }
        try { radioKeyHttp.close() } catch (_: Throwable) { closeFailed = true }
        if (closeFailed) throw IllegalStateException(CLEANUP_FAILED)
    }

    private suspend fun <T> guarded(call: suspend () -> T): T {
        ensureLive()
        try {
            val result = call()
            ensureLive()
            return result
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            ensureLive()
            throw failure
        }
    }

    private suspend fun ensureLive() {
        currentCoroutineContext().ensureActive()
        if (retired.value || accountJob?.isActive == false || (binding != null && binding.bearer() == null)) {
            throw CancellationException(RETIRED)
        }
    }

    private suspend fun currentBearer(): String? {
        ensureLive()
        val token = if (binding != null) binding.bearer() else authority?.bearer()
        ensureLive()
        return token
    }

    private companion object {
        const val RETIRED = "Nearby account retired"
        const val CLEANUP_FAILED = "Nearby account HTTP cleanup failed"
    }
}

private fun radioClockMillis(): Long = Clock.System.now().toEpochMilliseconds()
