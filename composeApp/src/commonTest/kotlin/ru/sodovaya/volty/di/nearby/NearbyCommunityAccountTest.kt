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

    @Test fun managementFactoryBorrowsOriginalAccountAndRefreshesMetadataAfterExactRevoke()=runTest {
        val parent=Job();val screen=SupervisorJob(parent);val scope=CoroutineScope(coroutineContext+screen)
        val paths=mutableListOf<String>();val headers=mutableListOf<String?>()
        val detail="""{"revision":1,"community":{"id":"$community","name":"Private","description":"","visibility":"PRIVATE","createdAtEpochMillis":100},"ownerId":"$actor","channels":[]}"""
        val metadata="""{"items":[{"invitationId":"$invitation","communityId":"$community","createdAtMillis":100,"expiresAtMillis":900100,"maxUses":10,"uses":0,"revokedAtMillis":null,"creationRevision":1,"revocationRevision":null}]}"""
        val client=HttpClient(MockEngine) {engine {
            dispatcher=StandardTestDispatcher(testScheduler)
            addHandler {request ->
                paths+="${request.method.value} ${request.url.encodedPath}";headers+=request.headers[HttpHeaders.Authorization]
                when {
                    request.method==HttpMethod.Post -> respond("""{"invitationId":"$invitation","communityId":"$community","revision":2}""")
                    request.url.encodedPath.endsWith("/invitations") -> {assertEquals("20",request.url.parameters["limit"]);respond(metadata)}
                    else -> respond(detail)
                }
            }
        }}
        fun consumer(bearer:String)=NearbyCommunityAccount(CoroutineScope(coroutineContext+parent),NearbyAccountCredentials(actor,bearer),
            Store(actor),InvitationSecretGenerator {error("Metadata cannot recover secrets")},client,root)
        val account=consumer("original");val foreign=consumer("other")
        val workspace=account.workspace(scope) {error("No entropy")}
        val management=account.invitationManagement(scope,workspace)
        try {
            assertFailsWith<IllegalArgumentException> {foreign.invitationManagement(scope,workspace)}
            workspace.browser.openCommunity(community);runCurrent()
            assertTrue(management.refresh());runCurrent();assertTrue(management.requestRevoke(invitation))
            assertEquals(2,paths.size);assertTrue(management.confirmRevoke());runCurrent()
            assertEquals(listOf("GET /v2/nearby/communities/$community","GET /v2/nearby/communities/$community/invitations",
                "POST /v2/nearby/communities/$community/invitations/$invitation/revoke","GET /v2/nearby/communities/$community/invitations"),paths)
            assertTrue(headers.all {it=="Bearer original"});assertEquals(setOf(invitation),management.state.value.confirmedRevocations)
            assertNull(management.state.value.items?.single()?.revokedAtMillis)
            management.close();runCurrent();assertTrue(workspace.active);assertTrue(parent.isActive);assertTrue(screen.isActive)
            assertIs<CommunityResult.Ready<*>>(account.api.communities.detail(community));assertEquals(5,paths.size)
        } finally {management.close();workspace.close();foreign.retire();account.retire();screen.cancel();parent.cancel();client.close();runCurrent()}
    }

    @Test fun acceptanceFactoryUsesOriginalAccountHttpThenReadsCurrentMembershipWithoutNavigation()=runTest {
        val parent=Job();val screen=SupervisorJob(parent);val scope=CoroutineScope(coroutineContext+screen)
        val paths=mutableListOf<String>();val headers=mutableListOf<String?>()
        val client=HttpClient(MockEngine) {engine {
            dispatcher=StandardTestDispatcher(testScheduler)
            addHandler {request ->
                paths+="${request.method.value} ${request.url.encodedPath}";headers+=request.headers[HttpHeaders.Authorization]
                if(request.method==HttpMethod.Post) respond("""{"operationId":"$invitation","invitationId":"$actor","communityId":"$community","revision":1,"membershipChanged":true}""")
                else respond("""{"items":[]}""")
            }
        }}
        fun consumer(bearer:String)=NearbyCommunityAccount(CoroutineScope(coroutineContext+parent),NearbyAccountCredentials(actor,bearer),
            Store(actor),InvitationSecretGenerator {error("No creation entropy")},client,root)
        val account=consumer("original");val foreign=consumer("other")
        val workspace=account.workspace(scope) {error("No server entropy")}
        val acceptance=account.invitationAcceptance(scope,workspace) {invitation}
        try {
            assertFailsWith<IllegalArgumentException> {foreign.invitationAcceptance(scope,workspace) {error("No foreign entropy")}}
            assertTrue(acceptance.accept("A".repeat(43)));runCurrent()
            assertEquals(community,acceptance.state.value.accepted?.communityId)
            assertEquals(listOf("POST /v2/nearby/invitations/accept","GET /v2/nearby/communities"),paths)
            assertTrue(headers.all {it=="Bearer original"})
            assertTrue(workspace.browser.state.value.memberships.items.orEmpty().isEmpty())
            assertNull(workspace.browser.state.value.selectedCommunity)
            acceptance.close();runCurrent();assertTrue(workspace.active);assertTrue(parent.isActive);assertTrue(screen.isActive)
            assertIs<CommunityResult.Ready<*>>(account.api.communities.memberships());assertEquals(3,paths.size)
        } finally {acceptance.close();workspace.close();foreign.retire();account.retire();screen.cancel();parent.cancel();client.close();runCurrent()}
    }

    @Test fun actualInvitationFactoryUsesRetainedDraftAndOriginalAccountWithoutRetiringBorrowedOwner()=runTest {
        val parent=Job();val screen=SupervisorJob(parent);val consumerScope=CoroutineScope(coroutineContext+screen)
        val paths=mutableListOf<String>();val headers=mutableListOf<String?>();val store=Store(actor)
        val detail="""{"revision":1,"community":{"id":"$community","name":"Private","description":"","visibility":"PRIVATE","createdAtEpochMillis":100},"ownerId":"$actor","channels":[]}"""
        val client=HttpClient(MockEngine) {engine {
            dispatcher=StandardTestDispatcher(testScheduler)
            addHandler {request ->
                paths+="${request.method.value} ${request.url.encodedPath}";headers+=request.headers[HttpHeaders.Authorization]
                if(request.method==HttpMethod.Put) {
                    assertNotNull(store.draft)
                    respond("""{"communityId":"$community","invitationId":"$invitation","createdAtMillis":100,"expiresAtMillis":900100,"maxUses":4,"uses":0,"creationRevision":1,"revokedAtMillis":null,"revocationRevision":null}""")
                } else respond(detail)
            }
        }}
        val account=NearbyCommunityAccount(CoroutineScope(coroutineContext+parent),NearbyAccountCredentials(actor,"original"),
            store,InvitationSecretGenerator {InvitationSecret.fromToken("A".repeat(43))},client,root)
        val foreign=NearbyCommunityAccount(CoroutineScope(coroutineContext+parent),NearbyAccountCredentials(actor,"other"),
            Store(actor),InvitationSecretGenerator {error("No foreign entropy")},client,root)
        val workspace=account.workspace(consumerScope) {error("No server entropy")}
        val creation=account.invitationCreation(consumerScope,workspace) {invitation}
        try {
            assertFailsWith<IllegalArgumentException> {foreign.invitationCreation(consumerScope,workspace) {error("No foreign entropy")}}
            workspace.browser.openCommunity(community);runCurrent();assertTrue(creation.create(900_000,4));runCurrent()
            val result=assertNotNull(creation.state.value.created);assertSame(store.draft,result.draft)
            assertEquals(listOf("GET /v2/nearby/communities/$community","PUT /v2/nearby/communities/$community/invitations/$invitation"),paths)
            assertTrue(headers.all {it=="Bearer original"})
            creation.close();runCurrent();assertTrue(parent.isActive);assertTrue(screen.isActive);assertTrue(workspace.active)
            assertIs<InvitationDraftExecution.Created>(account.invitations.execute(invitation));assertEquals(3,paths.size)
            assertTrue(headers.all {it=="Bearer original"})
        } finally {creation.close();workspace.close();foreign.retire();account.retire();screen.cancel();parent.cancel();client.close();runCurrent()}
    }

    @Test fun actualChannelFactoryUsesOriginalAccountAndWorkspaceWithoutRetiringBorrowedOwners() = runTest {
        val parent=Job();val screen=SupervisorJob(parent);val consumerScope=CoroutineScope(coroutineContext+screen)
        val paths=mutableListOf<String>();val headers=mutableListOf<String?>()
        val detail="""{"revision":1,"community":{"id":"$community","name":"Private","description":"","visibility":"PRIVATE","createdAtEpochMillis":100},"ownerId":"$actor","channels":[{"id":"40000000-0000-0000-0000-000000000004","name":"Общий","isDefault":true}]}"""
        val client=HttpClient(MockEngine) { engine {
            dispatcher=StandardTestDispatcher(testScheduler)
            addHandler {request ->
                paths += "${request.method.value} ${request.url.encodedPath}"
                headers += request.headers[HttpHeaders.Authorization]
                if(request.method==HttpMethod.Put) respond("""{"revision":2,"communityId":"$community","channel":{"id":"$invitation","name":"Ride","isDefault":false}}""")
                else respond(detail)
            }
        } }
        val account=NearbyCommunityAccount(CoroutineScope(coroutineContext+parent),NearbyAccountCredentials(actor,"original"),
            Store(actor),InvitationSecretGenerator {error("Unused")},client,root)
        val foreign=NearbyCommunityAccount(CoroutineScope(coroutineContext+parent),NearbyAccountCredentials(actor,"other"),
            Store(actor),InvitationSecretGenerator {error("Unused")},client,root)
        val workspace=account.workspace(consumerScope) {error("No server entropy")}
        val creation=account.channelCreation(consumerScope,workspace) {invitation}
        try {
            assertFailsWith<IllegalArgumentException> {foreign.channelCreation(consumerScope,workspace) {error("No channel entropy")}}
            workspace.browser.openCommunity(community);runCurrent()
            assertTrue(creation.create("Ride"));runCurrent()
            assertEquals(invitation,creation.state.value.created?.channelId)
            // An accepted command receipt cannot fabricate a row in an older detail reply.
            assertFalse(workspace.browser.state.value.detail?.channels.orEmpty().any {it.id==invitation})
            assertEquals(listOf("GET /v2/nearby/communities/$community",
                "PUT /v2/nearby/communities/$community/channels/$invitation",
                "GET /v2/nearby/communities/$community"),paths)
            assertTrue(headers.all {it=="Bearer original"})
            creation.close();runCurrent();assertTrue(workspace.active);assertTrue(parent.isActive);assertTrue(screen.isActive)
            assertIs<CommunityResult.Ready<*>>(account.api.communities.detail(community))
            assertEquals(4,paths.size)
        } finally {creation.close();workspace.close();foreign.retire();account.retire();screen.cancel();parent.cancel();client.close();runCurrent()}
    }

    @Test fun workspaceClosesItsOwnBrowserWithoutRetiringBorrowedAccountOrSiblingBrowser() = runTest {
        val parent = Job(); val screen = SupervisorJob(parent)
        val headers = mutableListOf<String?>()
        val client = HttpClient(MockEngine) { engine {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { headers += it.headers[HttpHeaders.Authorization]; respond(privatePage()) }
        } }
        val account = NearbyCommunityAccount(CoroutineScope(coroutineContext + parent),
            NearbyAccountCredentials(actor, "original"), Store(actor), InvitationSecretGenerator { error("Unused") }, client, root)
        val workspace = account.workspace(CoroutineScope(coroutineContext + screen)) { community }
        try {
            account.browser.refreshMemberships(); workspace.browser.refreshMemberships(); runCurrent()
            assertEquals(2, headers.size); assertTrue(headers.all { it == "Bearer original" })
            screen.cancel(); runCurrent()
            assertEquals(CommunityBrowserState(), workspace.browser.state.value)
            assertEquals(community, account.browser.state.value.memberships.items?.single()?.id)
            assertTrue(parent.isActive)
            assertIs<CommunityResult.Ready<*>>(account.api.communities.memberships())
            assertEquals(3, headers.size)
        } finally { workspace.close(); account.retire(); screen.cancel(); parent.cancel(); client.close(); runCurrent() }
    }

    @Test fun consumerRetirementImmediatelyFencesWorkspaceAdmissionWithoutCancellingAuthentication() = runTest {
        val parent = Job(); val screen = SupervisorJob(parent); var issued = 0
        val client = HttpClient(MockEngine) { engine {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { issued++; respond(privatePage()) }
        } }
        val account = NearbyCommunityAccount(CoroutineScope(coroutineContext + parent),
            NearbyAccountCredentials(actor, "original"), Store(actor), InvitationSecretGenerator { error("Unused") }, client, root)
        val workspace = account.workspace(CoroutineScope(coroutineContext + screen)) { community }
        try {
            workspace.browser.refreshMemberships(); runCurrent(); assertEquals(1, issued)
            account.retire()
            assertFalse(workspace.join(community), "Consumer retirement must fence command admission before another queued task")
            runCurrent(); assertEquals(CommunityBrowserState(), workspace.browser.state.value)
            assertTrue(parent.isActive); assertTrue(screen.isActive); assertEquals(1, issued)
            assertFailsWith<IllegalArgumentException> { account.workspace(CoroutineScope(coroutineContext + screen)) {community} }
        } finally { workspace.close(); account.retire(); screen.cancel(); parent.cancel(); client.close(); runCurrent() }
    }

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
