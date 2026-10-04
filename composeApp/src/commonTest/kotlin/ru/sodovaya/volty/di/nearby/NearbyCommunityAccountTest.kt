package ru.sodovaya.volty.di.nearby

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.request.get
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import ru.sodovaya.volty.data.nearby.NearbyAccountCredentials
import ru.sodovaya.volty.domain.nearby.community.*
import ru.sodovaya.volty.presentation.nearby.CommunityBrowserState
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class NearbyCommunityAccountTest {
    private val actor = "10000000-0000-0000-0000-000000000001"
    private val community = "20000000-0000-0000-0000-000000000002"
    private val invitation = "30000000-0000-0000-0000-000000000003"
    private val root = "https://example.test/v2/nearby"
    private class Store(override val actorId: String) : InvitationDraftStore {
        var draft: InvitationCreationDraft? = null
        var reads = 0
        override suspend fun read(invitationId: String) = draft.also { reads++ }
        override suspend fun retain(draft: InvitationCreationDraft) = (this.draft ?: draft).also { this.draft = it }
    }
    private fun privatePage() = """{"items":[{"id":"$community","name":"Private","description":"","visibility":"PRIVATE","createdAtEpochMillis":100}]}"""

    @Test fun parentCancellationClearsPrivateCacheAndFencesHeldReadWithoutWaitingForCompletion() = runTest {
        val parent = Job()
        val release = CompletableDeferred<Unit>()
        CoroutineScope(coroutineContext + parent).launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) { release.await() }
        }
        var issued = 0
        var returned = false
        val client = HttpClient(MockEngine) { engine {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler {
                issued++
                if (it.url.encodedPath.endsWith("/catalog")) withContext(NonCancellable) {
                    release.await(); respond("""{"items":[]}""").also { returned = true }
                } else respond(privatePage())
            }
        } }
        val store = Store(actor)
        val account = NearbyCommunityAccount(CoroutineScope(coroutineContext + parent),
            NearbyAccountCredentials(actor, "private"), store,
            InvitationSecretGenerator { error("No entropy after cancellation") }, client, root)
        try {
            account.browser.refreshMemberships(); runCurrent()
            assertEquals(CommunityVisibility.PRIVATE, account.browser.state.value.memberships.items?.single()?.visibility)
            account.browser.search("ride"); runCurrent()
            assertEquals(2, issued)
            parent.cancel(); runCurrent()
            assertFalse(parent.isCompleted, "The deliberately held account child must prevent parent completion")
            assertEquals(CommunityBrowserState(), account.browser.state.value)
            assertFailsWith<CancellationException> { account.api.communities.memberships() }
            assertFailsWith<CancellationException> { account.invitations.prepare(invitation, community, 900_000, 4) }
            assertEquals(0, store.reads)
            release.complete(Unit); runCurrent()
            assertTrue(returned)
            assertEquals(CommunityBrowserState(), account.browser.state.value)
            assertEquals(2, issued)
        } finally { release.complete(Unit); account.retire(); parent.cancel(); client.close(); runCurrent() }
    }

    @Test fun explicitRetirementFromCancelledCallerClosesAccountButLeavesParentAndDraftUsable() = runTest {
        val parent = Job()
        val client = HttpClient(MockEngine) { engine { addHandler { respond("") } } }
        val store = Store(actor)
        var generated = 0
        val generator = InvitationSecretGenerator { generated++; InvitationSecret.fromToken("A".repeat(43)) }
        fun account() = NearbyCommunityAccount(CoroutineScope(coroutineContext + parent),
            NearbyAccountCredentials(actor, "private"), store, generator, client, root)
        val old = account()
        var fresh: NearbyCommunityAccount? = null
        try {
            val prepared = assertIs<InvitationDraftPreparation.Prepared>(old.invitations.prepare(invitation, community, 900_000, 4))
            old.browser.search("cached query")
            val caller = Job().also { it.cancel() }
            var retireReturned = false
            CoroutineScope(caller + StandardTestDispatcher(testScheduler)).launch(start = CoroutineStart.UNDISPATCHED) {
                assertFalse(currentCoroutineContext().isActive)
                old.retire()
                retireReturned = true
            }
            runCurrent()
            assertTrue(retireReturned, "Retirement must execute in the already cancelled caller")
            assertEquals(CommunityBrowserState(), old.browser.state.value)
            assertTrue(parent.isActive)
            assertFailsWith<CancellationException> { old.api.communities.memberships() }
            assertFailsWith<IllegalStateException> { old.refresh(NearbyAccountCredentials(actor, "revive")) }
            val newAccount = account().also { fresh = it }
            val retried = assertIs<InvitationDraftPreparation.Prepared>(newAccount.invitations.prepare(invitation, community, 900_000, 4))
            assertSame(prepared.draft, retried.draft)
            assertEquals(1, generated)
            assertFalse(old.cleanupFailed.value)
            client.get("https://example.test/parent")
        } finally { fresh?.retire(); old.retire(); parent.cancel(); client.close(); runCurrent() }
    }

    @Test fun cancellationBeforeFirstQueuedDispatchStillClearsStateAndClosesCommands() = runTest {
        val parent = Job()
        var issued = 0
        val client = HttpClient(MockEngine) { engine { addHandler { issued++; respond("[]") } } }
        val account = NearbyCommunityAccount(CoroutineScope(coroutineContext + parent),
            NearbyAccountCredentials(actor, "private"), Store(actor),
            InvitationSecretGenerator { error("No entropy") }, client, root)
        try {
            account.browser.search("pending")
            assertEquals("pending", account.browser.state.value.query)
            parent.cancel(); runCurrent()
            assertEquals(CommunityBrowserState(), account.browser.state.value)
            assertFailsWith<CancellationException> { account.api.communities.memberships() }
            assertEquals(0, issued)
        } finally { account.retire(); parent.cancel(); client.close(); runCurrent() }
    }

    @Test fun rejectedConstructionLeavesNoAccountChildren() = runTest {
        val parent = Job()
        val client = HttpClient(MockEngine) { engine { addHandler { error("No HTTP") } } }
        val generator = InvitationSecretGenerator { error("No entropy") }
        try {
            val scope = CoroutineScope(coroutineContext + parent)
            assertFailsWith<IllegalArgumentException> {
                NearbyCommunityAccount(scope, NearbyAccountCredentials(actor, "private"), Store(community), generator, client, root)
            }
            assertFailsWith<IllegalArgumentException> {
                NearbyCommunityAccount(scope, NearbyAccountCredentials(actor, "private"), Store(actor), generator, client, "https:///v2")
            }
            assertTrue(parent.children.none())
            assertFailsWith<IllegalArgumentException> {
                NearbyCommunityAccount(object : CoroutineScope {
                    override val coroutineContext = StandardTestDispatcher(testScheduler)
                },
                    NearbyAccountCredentials(actor, "private"), Store(actor), generator, client, root)
            }
        } finally { parent.cancel(); client.close() }
    }
}
