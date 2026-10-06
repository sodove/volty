package ru.sodovaya.volty.presentation.nearby

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import ru.sodovaya.volty.data.social.*
import ru.sodovaya.volty.di.nearby.NearbyCommunityAccountFactory
import ru.sodovaya.volty.domain.nearby.community.*
import ru.sodovaya.volty.domain.social.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ServerWorkspaceAccountBindingTest {
    private val actor="10000000-0000-0000-0000-000000000001"
    private val server="20000000-0000-0000-0000-000000000002"
    private val channel="30000000-0000-0000-0000-000000000003"
    private fun page()="""{"items":[{"id":"$server","name":"Riders","description":"","visibility":"PRIVATE","createdAtEpochMillis":1}]}"""
    private fun detail()="""{"revision":1,"community":{"id":"$server","name":"Riders","description":"","visibility":"PRIVATE","createdAtEpochMillis":1},"ownerId":"$actor","channels":[{"id":"$channel","name":"General","isDefault":true}]}"""
    private inner class Fixture(test: TestScope) {
        val repository=DefaultSocialRepository(Transport(),Credentials(),test.backgroundScope)
        val screen=SupervisorJob(test.coroutineContext[Job])
        val scope=CoroutineScope(test.coroutineContext+screen)
        val headers=mutableListOf<String?>()
        val paths=mutableListOf<String>()
        val connected=mutableListOf<SocialAuthenticatedAccount>()
        var rejectFactory=false
        var entropy:()->InvitationSecret={error("No invitation entropy")}
        var reply: suspend (String)->String={path->if(path.endsWith(server)) detail() else page()}
        val client=HttpClient(MockEngine) {engine {
            dispatcher=StandardTestDispatcher(test.testScheduler)
            addHandler {headers+=it.headers[HttpHeaders.Authorization];paths+="${it.method.value} ${it.url.encodedPath}";respond(reply(it.url.encodedPath))}
        }}
        val factory=NearbyCommunityAccountFactory(client,"https://example.test/v2/nearby",
            {if(rejectFactory) error("Private implementation details");Drafts(it)},InvitationSecretGenerator {entropy()})
        val binding=ServerWorkspaceAccountBinding(scope,repository.authenticatedAccount,factory,{server}) {account,id->
            assertEquals(channel,id);connected+=account
        }
        suspend fun login() {repository.login(LoginRequest("rider","password"))}
        suspend fun close() {binding.close();screen.cancel();client.close()}
    }

    @Test fun managementUsesOriginalHttpAndClosingPreservesAuthentication()=runTest {
        val f=Fixture(this)
        val metadata="""{"items":[{"invitationId":"$channel","communityId":"$server","createdAtMillis":100,"expiresAtMillis":900100,"maxUses":10,"uses":0,"revokedAtMillis":null,"creationRevision":1,"revocationRevision":null}]}"""
        f.reply={path -> when {
            path.endsWith("/revoke") -> """{"invitationId":"$channel","communityId":"$server","revision":2}"""
            path.endsWith("/invitations") -> metadata
            path.endsWith(server) -> detail()
            else -> page()
        }}
        try {
            f.login();runCurrent();val account=assertNotNull(f.repository.authenticatedAccount.value)
            val presenter=assertNotNull(f.binding.state.value.presenter)
            presenter.openServer(server);runCurrent();presenter.openInvitationManagement();runCurrent()
            presenter.requestInvitationRevocation(channel);runCurrent();assertEquals(3,f.paths.size)
            presenter.confirmInvitationRevocation();runCurrent()
            assertEquals(listOf("GET /v2/nearby/communities","GET /v2/nearby/communities/$server",
                "GET /v2/nearby/communities/$server/invitations","POST /v2/nearby/communities/$server/invitations/$channel/revoke",
                "GET /v2/nearby/communities/$server/invitations"),f.paths)
            assertTrue(f.headers.all {it=="Bearer token-1"});assertTrue(f.connected.isEmpty())
            assertEquals(setOf(channel),presenter.state.value.invitationManagement.confirmedRevocations)
            assertNull(presenter.state.value.invitationManagement.items?.single()?.revokedAtMillis)
            f.binding.close();runCurrent();assertEquals(ServerWorkspaceUiState(),presenter.state.value)
            assertSame(account,f.repository.authenticatedAccount.value);assertTrue(account.job.isActive);assertTrue(f.screen.isActive)
        } finally {f.close();runCurrent()}
    }
    @Test fun channelFormUsesOriginalAccountHttpAndNeverStartsVoice()=runTest {
        val f=Fixture(this)
        try {
            f.login();runCurrent();val account=assertNotNull(f.repository.authenticatedAccount.value)
            val presenter=assertNotNull(f.binding.state.value.presenter)
            presenter.openServer(server);runCurrent();presenter.openChannelCreation();presenter.setChannelName("Ride")
            f.reply={path -> if(path.endsWith("/channels/$server"))
                """{"communityId":"$server","channel":{"id":"$server","name":"Ride","isDefault":false},"revision":2}"""
                else if(path.endsWith(server)) detail() else page()}
            presenter.submitChannelCreation();runCurrent()
            assertEquals(listOf("GET /v2/nearby/communities","GET /v2/nearby/communities/$server",
                "PUT /v2/nearby/communities/$server/channels/$server","GET /v2/nearby/communities/$server"),f.paths)
            assertEquals("Ride",presenter.state.value.channelCreation.created?.name)
            assertFalse(presenter.state.value.channelForm.visible);assertTrue(f.connected.isEmpty())
            assertTrue(f.headers.all {it=="Bearer token-1"})
            f.binding.close();runCurrent();assertSame(account,f.repository.authenticatedAccount.value);assertTrue(account.job.isActive)
        } finally {f.close();runCurrent()}
    }

    @Test fun acceptanceFormIssuesOriginalPostAndUsesCurrentMembershipReadWithoutInventingServer()=runTest {
        val f=Fixture(this)
        try {
            f.login();runCurrent();val account=assertNotNull(f.repository.authenticatedAccount.value)
            val presenter=assertNotNull(f.binding.state.value.presenter)
            presenter.openInvitationAcceptance();presenter.setInvitationCode("A".repeat(43))
            f.reply={path -> if(path.endsWith("/invitations/accept"))
                """{"operationId":"$server","invitationId":"$channel","communityId":"$server","revision":1,"membershipChanged":true}"""
                else """{"items":[]}"""}
            presenter.submitInvitationAcceptance();runCurrent()
            assertEquals(listOf("GET /v2/nearby/communities","POST /v2/nearby/invitations/accept","GET /v2/nearby/communities"),f.paths)
            assertTrue(presenter.state.value.browser.memberships.items.orEmpty().isEmpty())
            assertNull(presenter.state.value.browser.selectedCommunity);assertFalse(presenter.state.value.acceptanceForm.visible)
            assertEquals("",presenter.state.value.acceptanceForm.code.reveal());assertTrue(f.connected.isEmpty())
            assertTrue(f.headers.all {it=="Bearer token-1"});assertFalse(presenter.state.value.toString().contains("A".repeat(43)))
            f.binding.close();runCurrent();assertSame(account,f.repository.authenticatedAccount.value);assertTrue(account.job.isActive)
        } finally {f.close();runCurrent()}
    }

    @Test fun invitationFormCreatesThroughOriginalHttpAndClosingDoesNotRetireAuthentication()=runTest {
        val f=Fixture(this);val copied=mutableListOf<String>()
        f.entropy={InvitationSecret.fromToken("A".repeat(43))}
        try {
            f.login();runCurrent();val account=assertNotNull(f.repository.authenticatedAccount.value)
            val presenter=assertNotNull(f.binding.state.value.presenter)
            presenter.openServer(server);runCurrent();presenter.openInvitationCreation()
            presenter.setInvitationTtl(900_000);presenter.setInvitationUses("4")
            f.reply={path -> if(path.endsWith("/invitations/$server"))
                """{"communityId":"$server","invitationId":"$server","createdAtMillis":100,"expiresAtMillis":900100,"maxUses":4,"uses":0,"creationRevision":1,"revokedAtMillis":null,"revocationRevision":null}"""
                else if(path.endsWith(server)) detail() else page()}
            presenter.submitInvitationCreation();runCurrent()
            assertEquals(listOf("GET /v2/nearby/communities","GET /v2/nearby/communities/$server",
                "PUT /v2/nearby/communities/$server/invitations/$server"),f.paths)
            assertTrue(presenter.copyInvitation(copied::add));assertEquals(listOf("A".repeat(43)),copied)
            assertTrue(f.headers.all {it=="Bearer token-1"});assertTrue(f.connected.isEmpty())
            f.binding.close();runCurrent();assertFalse(presenter.copyInvitation(copied::add))
            assertSame(account,f.repository.authenticatedAccount.value);assertTrue(account.job.isActive);assertTrue(f.screen.isActive)
        } finally {f.close();runCurrent()}
    }

    @Test fun actualLoginCreatesWorkspaceAndClosingScreenPreservesAuthenticationAndBorrowedScreen()=runTest {
        val f=Fixture(this)
        try {
            runCurrent();assertNull(f.binding.state.value.presenter);assertTrue(f.headers.isEmpty())
            f.login();runCurrent()
            val account=assertNotNull(f.repository.authenticatedAccount.value)
            val presenter=assertNotNull(f.binding.state.value.presenter)
            assertEquals(server,presenter.state.value.browser.memberships.items?.single()?.id)
            presenter.openServer(server);runCurrent();presenter.connect(channel)
            assertEquals(listOf(account),f.connected);assertTrue(f.headers.all {it=="Bearer token-1"})
            f.binding.close();runCurrent();presenter.connect(channel)
            assertNull(f.binding.state.value.presenter);assertEquals(ServerWorkspaceUiState(),presenter.state.value)
            assertSame(account,f.repository.authenticatedAccount.value);assertTrue(account.job.isActive)
            assertTrue(f.screen.isActive);assertEquals(1,f.connected.size)
        } finally {f.close();runCurrent()}
    }

    @Test fun sameUserReloginCannotAdoptOldResponseOrReuseOldChannelCommand()=runTest {
        val f=Fixture(this);val release=CompletableDeferred<Unit>();var returned=false
        try {
            f.login();runCurrent();val oldAccount=assertNotNull(f.repository.authenticatedAccount.value)
            val old=assertNotNull(f.binding.state.value.presenter)
            old.openServer(server);runCurrent();old.connect(channel);assertEquals(listOf(oldAccount),f.connected)
            f.reply={path->if(path.endsWith("/catalog")) withContext(NonCancellable) {
                release.await();returned=true;page()
            } else if(path.endsWith(server)) detail() else page()}
            old.openCatalog();runCurrent();assertEquals(3,f.headers.size)
            f.repository.logout();f.login();runCurrent()
            val freshAccount=assertNotNull(f.repository.authenticatedAccount.value)
            val fresh=assertNotNull(f.binding.state.value.presenter)
            assertNotSame(oldAccount,freshAccount);assertNotSame(old,fresh)
            assertEquals(ServerWorkspaceUiState(),old.state.value)
            old.connect(channel);assertEquals(1,f.connected.size)
            fresh.openServer(server);runCurrent();fresh.connect(channel)
            assertEquals(listOf(oldAccount,freshAccount),f.connected)
            assertEquals<List<String?>>(listOf("Bearer token-1","Bearer token-1","Bearer token-1","Bearer token-2","Bearer token-2"),f.headers)
            release.complete(Unit);runCurrent();assertTrue(returned)
            assertSame(fresh,f.binding.state.value.presenter)
            assertEquals(ServerWorkspaceSection.SERVER,fresh.state.value.section)
            assertEquals(ServerWorkspaceUiState(),old.state.value)
        } finally {release.complete(Unit);f.close();runCurrent()}
    }

    @Test fun accountCancellationBeforeFlowUpdateWithdrawsWorkspaceAndBlocksConnectionImmediately()=runTest {
        val f=Fixture(this);val release=CompletableDeferred<Unit>()
        try {
            f.login();runCurrent();val account=assertNotNull(f.repository.authenticatedAccount.value)
            val presenter=assertNotNull(f.binding.state.value.presenter)
            presenter.openServer(server);runCurrent()
            account.scope().launch(start=CoroutineStart.UNDISPATCHED) {withContext(NonCancellable) {release.await()}}
            account.job.cancel();presenter.connect(channel);runCurrent()
            assertFalse(account.job.isCompleted,"A held child must keep account completion pending")
            assertTrue(f.connected.isEmpty());assertNull(f.binding.state.value.presenter)
            assertTrue(f.screen.isActive)
        } finally {release.complete(Unit);f.close();runCurrent()}
    }

    @Test fun cancelledScreenBeforeFirstDispatchNeverConstructsConsumerOrIssuesHttp()=runTest {
        val f=Fixture(this)
        try {
            f.login();f.screen.cancel();runCurrent()
            assertNull(f.binding.state.value.presenter);assertTrue(f.headers.isEmpty())
            assertTrue(assertNotNull(f.repository.authenticatedAccount.value).job.isActive)
        } finally {f.close();runCurrent()}
    }

    @Test fun failedConstructionCanBeRetriedWithoutReplacingAuthenticationOrLeakingChildren()=runTest {
        val f=Fixture(this)
        try {
            runCurrent();f.rejectFactory=true;f.login()
            val account=assertNotNull(f.repository.authenticatedAccount.value)
            val children=account.job.children.toList()
            runCurrent();assertTrue(f.binding.state.value.failed);assertNull(f.binding.state.value.presenter)
            assertEquals(children,account.job.children.toList());assertTrue(f.headers.isEmpty())
            f.rejectFactory=false;f.binding.retry();f.binding.retry();runCurrent()
            assertFalse(f.binding.state.value.failed);assertNotNull(f.binding.state.value.presenter)
            assertSame(account,f.repository.authenticatedAccount.value)
            assertEquals(listOf<String?>("Bearer token-1"),f.headers)
            f.binding.close();f.binding.retry();runCurrent();assertNull(f.binding.state.value.presenter)
            assertEquals(1,f.headers.size)
        } finally {f.close();runCurrent()}
    }

    private class Drafts(override val actorId: String):InvitationDraftStore {
        private val saved=mutableMapOf<String,InvitationCreationDraft>()
        override suspend fun read(invitationId: String)=saved[invitationId]
        override suspend fun retain(draft: InvitationCreationDraft)=saved.getOrPut(draft.invitationId) {draft}
    }
    private class Credentials:SocialCredentialStore {
        var value:SocialCredentials?=null
        override suspend fun read()=value
        override suspend fun write(credentials: SocialCredentials) {value=credentials}
        override suspend fun clear() {value=null}
    }
    private inner class Transport:SocialTransport by UnavailableSocialTransport() {
        var ordinal=0
        override suspend fun login(request: LoginRequest)=SocialResult.Success(SessionCredentials("token-${++ordinal}","refresh-$ordinal",Long.MAX_VALUE))
        override suspend fun getProfile(accessToken: String)=SocialResult.Success(SocialSession.Authenticated(SocialUserId(actor),"Rider",SessionTokenState.ACTIVE,true))
        override suspend fun logout(accessToken: String)=SocialResult.Success(Unit)
    }
}

