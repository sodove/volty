package ru.sodovaya.volty.di.nearby

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import ru.sodovaya.volty.data.social.*
import ru.sodovaya.volty.domain.social.*
import ru.sodovaya.volty.domain.nearby.community.*
import ru.sodovaya.volty.presentation.nearby.CommunityBrowserState
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class TrustedCommunityAccountFactoryTest {
    private val actor = "10000000-0000-0000-0000-000000000001"
    private val community = "20000000-0000-0000-0000-000000000002"
    private val invitation = "30000000-0000-0000-0000-000000000003"
    private val root = "https://example.test/v2/nearby"
    private fun page() = """{"items":[{"id":"$community","name":"Private","description":"","visibility":"PRIVATE","createdAtEpochMillis":1}]}"""

    @Test
    fun logoutRetiresRealAccountCacheWhileOldProducerWaitsAndReloginReusesDraft() = runTest {
        val repository = DefaultSocialRepository(Transport(actor), Credentials(), backgroundScope)
        runCurrent()
        repository.login(LoginRequest("rider", "password"))
        val authority = assertNotNull(repository.authenticatedAccount.value)
        val release = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        var returned = false
        val headers = mutableListOf<String?>()
        val client = HttpClient(MockEngine) { engine {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler {
                headers += it.headers[HttpHeaders.Authorization]
                if (it.url.encodedPath.endsWith("/catalog")) withContext(NonCancellable) {
                    entered.complete(Unit); release.await()
                    respond("""{"items":[]}""").also { returned = true }
                } else respond(page())
            }
        } }
        val store = Drafts(actor)
        var entropy = 0
        val factory = NearbyCommunityAccountFactory(client, root, { store }, InvitationSecretGenerator {
            entropy++; InvitationSecret.fromToken("A".repeat(43))
        })
        val old = factory.create(authority)
        val heldChild = CoroutineScope(backgroundScope.coroutineContext + authority.job).launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) { release.await() }
        }
        var fresh: NearbyCommunityAccount? = null
        try {
            val prepared = assertIs<InvitationDraftPreparation.Prepared>(old.invitations.prepare(invitation, community, 900_000, 4))
            old.browser.refreshMemberships(); runCurrent()
            assertEquals(CommunityVisibility.PRIVATE, old.browser.state.value.memberships.items?.single()?.visibility)
            old.browser.search("ride"); runCurrent()
            assertTrue(entered.isCompleted)
            repository.logout(); runCurrent()
            assertTrue(authority.job.isCancelled)
            assertFalse(authority.job.isCompleted)
            assertEquals(CommunityBrowserState(), old.browser.state.value)
            assertFailsWith<CancellationException> { old.api.communities.memberships() }
            assertFailsWith<CancellationException> { old.invitations.prepare(invitation, community, 900_000, 4) }
            repository.login(LoginRequest("rider", "password"))
            fresh = factory.create(assertNotNull(repository.authenticatedAccount.value))
            val reused = assertIs<InvitationDraftPreparation.Prepared>(fresh.invitations.prepare(invitation, community, 900_000, 4))
            assertSame(prepared.draft, reused.draft)
            assertEquals(1, entropy)
            assertEquals<CommunityResult<List<CommunitySummary>>>(CommunityResult.Ready(listOf(
                CommunitySummary(community, "Private", "", CommunityVisibility.PRIVATE, 1),
            )), fresh.api.communities.memberships())
            assertEquals(listOf("Bearer token-1", "Bearer token-1", "Bearer token-2"), headers.filterNotNull())
            release.complete(Unit); runCurrent()
            assertTrue(returned)
            assertEquals(CommunityBrowserState(), old.browser.state.value)
            assertFalse(old.cleanupFailed.value)
        } finally { release.complete(Unit); fresh?.retire(); old.retire(); heldChild.join(); client.close(); runCurrent() }
    }

    @Test
    fun retiringConsumerFromCancelledCallerPreservesAuthAndDurableDraft() = runTest {
        val repository = DefaultSocialRepository(Transport(actor), Credentials(), backgroundScope)
        runCurrent()
        repository.login(LoginRequest("rider", "password"))
        val authority = assertNotNull(repository.authenticatedAccount.value)
        val store = Drafts(actor)
        var entropy = 0
        val headers = mutableListOf<String?>()
        val client = HttpClient(MockEngine) { engine {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { headers += it.headers[HttpHeaders.Authorization]; respond(page()) }
        } }
        val factory = NearbyCommunityAccountFactory(client, root, { store }, InvitationSecretGenerator {
            entropy++; InvitationSecret.fromToken("A".repeat(43))
        })
        val old = factory.create(authority)
        var fresh: NearbyCommunityAccount? = null
        try {
            val prepared = assertIs<InvitationDraftPreparation.Prepared>(old.invitations.prepare(invitation, community, 900_000, 4))
            old.browser.refreshMemberships(); runCurrent()
            assertNotNull(old.browser.state.value.memberships.items)
            val caller = Job().apply { cancel() }
            var returned = false
            CoroutineScope(coroutineContext + caller).launch(start = CoroutineStart.UNDISPATCHED) {
                old.retire(); returned = true
            }
            runCurrent()
            assertTrue(returned)
            assertTrue(authority.job.isActive)
            assertSame(authority, repository.authenticatedAccount.value)
            assertEquals(CommunityBrowserState(), old.browser.state.value)
            assertFailsWith<CancellationException> { old.api.communities.memberships() }
            fresh = factory.create(authority)
            val reused = assertIs<InvitationDraftPreparation.Prepared>(fresh.invitations.prepare(invitation, community, 900_000, 4))
            assertSame(prepared.draft, reused.draft)
            assertEquals(1, entropy)
            assertTrue(fresh.api.communities.memberships() is CommunityResult.Ready)
            assertEquals(listOf("Bearer token-1", "Bearer token-1"), headers.filterNotNull())
        } finally { fresh?.retire(); old.retire(); client.close(); runCurrent() }
    }

    @Test
    fun rejectedFactoryInputsCannotCreateAccountChildrenOrTouchStore() = runTest {
        val repository = DefaultSocialRepository(Transport(actor), Credentials(), backgroundScope)
        runCurrent()
        repository.login(LoginRequest("rider", "password"))
        val authority = assertNotNull(repository.authenticatedAccount.value)
        val children = authority.job.children.toList()
        var stores = 0
        val client = HttpClient(MockEngine) { engine { addHandler { error("No HTTP") } } }
        val generator = InvitationSecretGenerator { error("No entropy") }
        try {
            assertFailsWith<IllegalArgumentException> {
                NearbyCommunityAccountFactory(client, "http://example.test", { stores++; Drafts(actor) }, generator)
            }
            val mismatch = NearbyCommunityAccountFactory(client, root, { stores++; Drafts(community) }, generator)
            assertFailsWith<IllegalArgumentException> { mismatch.create(authority) }
            assertEquals(children, authority.job.children.toList())
            assertEquals(1, stores)
            repository.logout(); runCurrent()
            val valid = NearbyCommunityAccountFactory(client, root, { stores++; Drafts(actor) }, generator)
            assertFailsWith<IllegalStateException> { valid.create(authority) }
            assertEquals(1, stores)
            val legacy = DefaultSocialRepository(Transport("u1"), Credentials(), backgroundScope)
            runCurrent(); legacy.login(LoginRequest("legacy", "password"))
            val legacyAuthority = assertNotNull(legacy.authenticatedAccount.value)
            val legacyChildren = legacyAuthority.job.children.toList()
            assertFailsWith<IllegalArgumentException> { valid.create(legacyAuthority) }
            assertEquals(1, stores)
            assertEquals(legacyChildren, legacyAuthority.job.children.toList())
        } finally { client.close() }
    }

    private class Drafts(override val actorId: String) : InvitationDraftStore {
        var draft: InvitationCreationDraft? = null
        override suspend fun read(invitationId: String) = draft
        override suspend fun retain(draft: InvitationCreationDraft) = (this.draft ?: draft).also { this.draft = it }
    }
    private class Credentials : SocialCredentialStore {
        var value: SocialCredentials? = null
        override suspend fun read() = value
        override suspend fun write(credentials: SocialCredentials) { value = credentials }
        override suspend fun clear() { value = null }
    }
    private class Transport(private val actor: String) : SocialTransport by UnavailableSocialTransport() {
        var ordinal = 0
        override suspend fun login(request: LoginRequest) = SocialResult.Success(
            SessionCredentials("token-${++ordinal}", "refresh-$ordinal", Long.MAX_VALUE),
        )
        override suspend fun getProfile(accessToken: String) = SocialResult.Success(
            SocialSession.Authenticated(SocialUserId(actor), "Rider", SessionTokenState.ACTIVE, true),
        )
        override suspend fun logout(accessToken: String) = SocialResult.Success(Unit)
    }
}
