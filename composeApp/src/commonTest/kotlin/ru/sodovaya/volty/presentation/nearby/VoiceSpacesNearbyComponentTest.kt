package ru.sodovaya.volty.presentation.nearby

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.Lifecycle
import com.arkivanov.essenty.lifecycle.create
import com.arkivanov.essenty.lifecycle.destroy
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import ru.sodovaya.volty.data.social.*
import ru.sodovaya.volty.di.nearby.NearbyCommunityAccountFactory
import ru.sodovaya.volty.domain.nearby.community.*
import ru.sodovaya.volty.domain.social.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceSpacesNearbyComponentTest {
    private val actor="10000000-0000-0000-0000-000000000001"
    private val accepted=FriendSummary(FriendshipId("accepted"),SocialUserId("20000000-0000-0000-0000-000000000002"),"Friend",FriendshipState.ACCEPTED)
    private val pending=FriendSummary(FriendshipId("pending"),SocialUserId("30000000-0000-0000-0000-000000000003"),"Waiting",FriendshipState.REQUEST_RECEIVED)
    private inner class Transport:SocialTransport by UnavailableSocialTransport() {
        var ordinal=0;val issued=mutableListOf<String>()
        var friends: suspend (String)->SocialResult<List<FriendSummary>> = {SocialResult.Success(listOf(accepted,pending))}
        var search: suspend (String)->SocialResult<List<UserSearchResult>> = {SocialResult.Success(emptyList())}
        var respond: suspend ()->SocialResult<Unit> = {SocialResult.Success(Unit)}
        var profile: suspend (ProfileUpdate)->SocialResult<SocialSession.Authenticated> = { request ->
            SocialResult.Success(SocialSession.Authenticated(SocialUserId(actor),request.displayName,SessionTokenState.ACTIVE,true)) }
        override suspend fun updateProfile(accessToken: String,request: ProfileUpdate):SocialResult<SocialSession.Authenticated> {
            issued += "profile:${request.displayName}:$accessToken"
            return profile(request)
        }
        override suspend fun login(request: LoginRequest):SocialResult<SessionCredentials> {
            issued+="login:${request.email}"
            return SocialResult.Success(SessionCredentials("token-${++ordinal}","refresh-$ordinal",Long.MAX_VALUE))
        }
        override suspend fun register(request: RegistrationRequest):SocialResult<SessionCredentials> {
            issued+="register:${request.displayName}"
            return SocialResult.Success(SessionCredentials("token-${++ordinal}","refresh-$ordinal",Long.MAX_VALUE))
        }
        override suspend fun getProfile(accessToken: String)=SocialResult.Success(SocialSession.Authenticated(SocialUserId(actor),"Rider",SessionTokenState.ACTIVE,true))
        override suspend fun logout(accessToken: String)=SocialResult.Success(Unit)
        override suspend fun listFriends(accessToken: String):SocialResult<List<FriendSummary>> {issued+="friends:$accessToken";return friends(accessToken)}
        override suspend fun searchUsers(accessToken: String,query: String):SocialResult<List<UserSearchResult>> {issued+="search:$query:$accessToken";return search(query)}
        override suspend fun respondToFriendRequest(accessToken: String,friendshipId: String,accept: Boolean):SocialResult<Unit> {
            issued+="respond:$friendshipId:$accept:$accessToken";return respond()
        }
        override suspend fun sendFriendRequest(accessToken: String,request: FriendRequest):SocialResult<Unit> {
            issued+="request:${request.userId.value}:$accessToken";return SocialResult.Success(Unit)
        }
        override suspend fun listGroups(accessToken: String):SocialResult<List<RideGroup>> = error("New shell must never read legacy groups")
    }
    private inner class Fixture(test: TestScope) {
        val transport=Transport();val repository=DefaultSocialRepository(transport,Credentials(),test.backgroundScope)
        val parent=SupervisorJob(test.coroutineContext[Job]);val scope=CoroutineScope(test.coroutineContext+parent)
        val lifecycle=LifecycleRegistry().apply {create()}
        val calls=mutableListOf<Pair<SocialAuthenticatedAccount,FriendSummary>>()
        val client=HttpClient(MockEngine) {engine {dispatcher=StandardTestDispatcher(test.testScheduler);addHandler {respond("""{"items":[]}""")}}}
        val factory=NearbyCommunityAccountFactory(client,"https://example.test/v2/nearby",{Drafts(it)},InvitationSecretGenerator {error("No entropy")})
        val component=DefaultVoiceSpacesNearbyComponent(DefaultComponentContext(lifecycle),scope,repository,factory,
            {actor},{_,_->error("No channel selected")},{account,friend->calls+=account to friend},{})
        suspend fun login() {repository.login(LoginRequest("rider","password"))}
        suspend fun close() {component.close();parent.cancel();client.close()}
    }

    @Test fun profile_uses_verified_name_and_exact_original_account_without_duplicate_write()=runTest {
        val f=Fixture(this)
        try {
            f.login();runCurrent();val account=assertNotNull(f.repository.authenticatedAccount.value)
            f.component.openProfile();runCurrent()
            assertEquals(NearbyShellSection.PROFILE,f.component.state.value.section)
            assertEquals("Rider",f.component.state.value.profile.displayName)
            f.component.setProfileName("x");f.component.submitProfile();runCurrent()
            assertEquals(NearbyShellError.INVALID_FORM,f.component.state.value.error)
            assertFalse(f.transport.issued.any {it.startsWith("profile:")})
            f.component.setProfileName("  New Rider  ");f.component.submitProfile();f.component.submitProfile();runCurrent()
            assertEquals(listOf("profile:New Rider:token-1"),f.transport.issued.filter {it.startsWith("profile:")})
            assertEquals("New Rider",assertIs<SocialSession.Authenticated>(f.component.state.value.session).displayName)
            assertTrue(f.component.state.value.profile.saved)
            assertSame(account,f.repository.authenticatedAccount.value)
            assertTrue(account.job.isActive);assertTrue(f.parent.isActive)
            assertEquals(listOf(accepted,pending),f.component.state.value.friends.items)
        } finally {f.close();runCurrent()}
    }
    @Test fun held_old_profile_write_cannot_publish_after_same_user_relogin()=runTest {
        val f=Fixture(this);val release=CompletableDeferred<Unit>();var returned=false
        f.transport.profile={withContext(NonCancellable) {
            release.await();returned=true
            SocialResult.Success(SocialSession.Authenticated(SocialUserId(actor),"Old edited",SessionTokenState.ACTIVE,true))
        }}
        try {
            f.login();runCurrent();f.component.openProfile();f.component.setProfileName("Old edited")
            f.component.submitProfile();runCurrent()
            assertTrue("profile:Old edited:token-1" in f.transport.issued)
            f.repository.logout();f.login();runCurrent()
            val fresh=assertNotNull(f.repository.authenticatedAccount.value)
            release.complete(Unit);runCurrent();assertTrue(returned)
            assertEquals("Rider",assertIs<SocialSession.Authenticated>(f.component.state.value.session).displayName)
            assertEquals(NearbyProfileDraft(),f.component.state.value.profile)
            assertSame(fresh,f.repository.authenticatedAccount.value)
            assertTrue(fresh.job.isActive)
        } finally {release.complete(Unit);f.close();runCurrent()}
    }
    @Test fun closed_profile_editor_cannot_issue_a_write_or_retire_account()=runTest {
        val f=Fixture(this)
        try {
            f.login();runCurrent();val account=assertNotNull(f.repository.authenticatedAccount.value)
            f.component.openProfile();f.component.setProfileName("Closed editor")
            runCurrent();assertEquals("Closed editor",f.component.state.value.profile.displayName)
            f.component.close();f.component.submitProfile();runCurrent()
            assertFalse(f.transport.issued.any {it.startsWith("profile:")})
            assertSame(account,f.repository.authenticatedAccount.value);assertTrue(account.job.isActive)
        } finally {f.close();runCurrent()}
    }
    @Test fun authValidationAndActualRegistrationClearPasswordAndLoadFriendsWithoutLegacyGroups()=runTest {
        val f=Fixture(this)
        try {
            runCurrent();f.component.setAuthMode(NearbyAuthMode.REGISTER)
            f.component.setEmail("rider@example.test");f.component.setPassword("short");f.component.setDisplayName("Rider")
            f.component.submitAuth();runCurrent();assertTrue(f.transport.issued.isEmpty())
            assertEquals(NearbyShellError.INVALID_FORM,f.component.state.value.error)
            f.component.setPassword("long-password-123");f.component.submitAuth();f.component.submitAuth();runCurrent()
            assertEquals(listOf("register:Rider","friends:token-1"),f.transport.issued)
            assertIs<SocialSession.Authenticated>(f.component.state.value.session)
            assertEquals("",f.component.state.value.auth.password)
            assertEquals(listOf(accepted,pending),f.component.state.value.friends.items)
            assertNotNull(f.component.servers.value.presenter);assertTrue(f.calls.isEmpty())
        } finally {f.close();runCurrent()}
    }

    @Test fun callRequiresActualAcceptedRowAndOriginalLiveAccount()=runTest {
        val f=Fixture(this)
        try {
            f.login();runCurrent();val account=assertNotNull(f.repository.authenticatedAccount.value)
            f.component.callFriend(pending.userId);f.component.callFriend(SocialUserId(actor));assertTrue(f.calls.isEmpty())
            f.component.callFriend(accepted.userId);assertEquals(listOf(account to accepted),f.calls)
            account.job.cancel();f.component.callFriend(accepted.userId);runCurrent();assertEquals(1,f.calls.size)
        } finally {f.close();runCurrent()}
    }

    @Test fun lateOldFriendsCannotReplaceFreshRowsAfterSameUserRelogin()=runTest {
        val f=Fixture(this);val release=CompletableDeferred<Unit>();var returned=false
        f.transport.friends={token->if(token=="token-1") withContext(NonCancellable) {
            release.await();returned=true;SocialResult.Success(listOf(pending))
        } else SocialResult.Success(listOf(accepted))}
        try {
            f.login();runCurrent();assertEquals(listOf("login:rider","friends:token-1"),f.transport.issued)
            f.repository.logout();f.login();runCurrent()
            assertEquals(listOf(accepted),f.component.state.value.friends.items)
            release.complete(Unit);runCurrent();assertTrue(returned)
            assertEquals(listOf(accepted),f.component.state.value.friends.items)
            f.component.callFriend(accepted.userId);assertSame(f.repository.authenticatedAccount.value,f.calls.single().first)
        } finally {release.complete(Unit);f.close();runCurrent()}
    }

    @Test fun laterSearchWinsAndRequestMustReferToActualUnlinkedSearchResult()=runTest {
        val f=Fixture(this);val release=CompletableDeferred<Unit>();var returned=false
        val user=UserSearchResult(accepted.userId,"Found")
        f.transport.search={query->if(query=="old") withContext(NonCancellable) {
            release.await();returned=true;SocialResult.Success(listOf(UserSearchResult(pending.userId,"Old")))
        } else SocialResult.Success(listOf(user))}
        try {
            f.login();runCurrent();f.component.setFriendQuery("old");f.component.searchFriends();runCurrent()
            f.component.setFriendQuery("new");f.component.searchFriends();runCurrent()
            assertEquals(listOf(user),f.component.state.value.friends.results)
            f.component.requestFriend(pending.userId);runCurrent();assertFalse(f.transport.issued.any {it.startsWith("request:")})
            f.component.requestFriend(user.userId);runCurrent()
            assertTrue("request:${user.userId.value}:token-1" in f.transport.issued)
            f.component.requestFriend(user.userId);runCurrent()
            assertEquals(1,f.transport.issued.count {it.startsWith("request:")})
            release.complete(Unit);runCurrent();assertTrue(returned)
            assertEquals(listOf(user.copy(state=FriendshipState.REQUEST_SENT)),f.component.state.value.friends.results)
        } finally {release.complete(Unit);f.close();runCurrent()}
    }

    @Test fun respondUsesActualIncomingRequestAndScreenClosePreservesAccountAndParent()=runTest {
        val f=Fixture(this)
        try {
            f.login();runCurrent();val account=assertNotNull(f.repository.authenticatedAccount.value)
            f.component.respondToFriend(accepted.friendshipId,true);runCurrent()
            assertFalse(f.transport.issued.any {it.startsWith("respond:")})
            f.component.respondToFriend(pending.friendshipId,false);runCurrent()
            assertTrue("respond:pending:false:token-1" in f.transport.issued)
            f.component.close();runCurrent();val issued=f.transport.issued.size
            f.component.refreshFriends();f.component.callFriend(accepted.userId);runCurrent()
            assertEquals(issued,f.transport.issued.size);assertTrue(f.calls.isEmpty())
            assertEquals(VoiceSpacesNearbyState(),f.component.state.value)
            assertTrue(account.job.isActive);assertTrue(f.parent.isActive)
        } finally {f.close();runCurrent()}
    }

    @Test fun authFormEnteredBeforeFirstDispatchIsNotErasedByInitialLoggedOutObservation()=runTest {
        val f=Fixture(this)
        try {
            f.component.setEmail("rider@example.test");f.component.setPassword("password")
            runCurrent()
            assertEquals("rider@example.test",f.component.state.value.auth.email)
            assertEquals("password",f.component.state.value.auth.password)
            f.component.submitAuth();runCurrent()
            assertTrue("login:rider@example.test" in f.transport.issued)
        } finally {f.close();runCurrent()}
    }

    @Test fun heldOldFriendMutationDoesNotBlockNewAccountOrPublishItsLateError()=runTest {
        val f=Fixture(this);val release=CompletableDeferred<Unit>();var returned=false
        f.transport.respond={withContext(NonCancellable) {
            release.await();returned=true;SocialResult.Failure(SocialFailure.Server("private internal details"))
        }}
        try {
            f.login();runCurrent();f.component.respondToFriend(pending.friendshipId,true);runCurrent()
            assertTrue("respond:pending:true:token-1" in f.transport.issued);assertTrue(f.component.state.value.busy)
            f.repository.logout();f.login();runCurrent()
            assertFalse(f.component.state.value.busy)
            f.component.callFriend(accepted.userId);assertSame(f.repository.authenticatedAccount.value,f.calls.single().first)
            release.complete(Unit);runCurrent();assertTrue(returned)
            assertNull(f.component.state.value.error);assertFalse(f.component.state.value.busy)
        } finally {release.complete(Unit);f.close();runCurrent()}
    }

    @Test fun decomposeDestructionWithdrawsPrivateScreenDataAndFencesActionsWithoutLoggingOut()=runTest {
        val f=Fixture(this)
        try {
            f.login();runCurrent();val account=assertNotNull(f.repository.authenticatedAccount.value)
            assertNotNull(f.component.state.value.friends.items);assertNotNull(f.component.servers.value.presenter)
            f.lifecycle.destroy();f.component.callFriend(accepted.userId);runCurrent()
            assertEquals(Lifecycle.State.DESTROYED,f.lifecycle.state)
            assertEquals(VoiceSpacesNearbyState(),f.component.state.value);assertNull(f.component.servers.value.presenter)
            assertTrue(f.calls.isEmpty());assertTrue(account.job.isActive);assertTrue(f.parent.isActive)
        } finally {f.close();runCurrent()}
    }

    @Test fun cancelledAccountWithdrawsFriendsBeforeCancellationIgnoringChildCompletes()=runTest {
        val f=Fixture(this);val release=CompletableDeferred<Unit>()
        try {
            f.login();runCurrent();val account=assertNotNull(f.repository.authenticatedAccount.value)
            assertNotNull(f.component.state.value.friends.items)
            f.component.openProfile();f.component.setProfileName("Private draft");runCurrent()
            assertEquals("Private draft",f.component.state.value.profile.displayName)
            account.scope().launch(start=CoroutineStart.UNDISPATCHED) {withContext(NonCancellable) {release.await()}}
            account.job.cancel();runCurrent()
            assertFalse(account.job.isCompleted)
            assertEquals(NearbyFriendsState(),f.component.state.value.friends)
            assertEquals(SocialSession.LoggedOut,f.component.state.value.session)
            assertEquals(NearbyProfileDraft(),f.component.state.value.profile)
            assertNull(f.component.servers.value.presenter);assertTrue(f.parent.isActive)
        } finally {release.complete(Unit);f.close();runCurrent()}
    }

    @Test fun independentlyCancelledFriendTransportFinishesLoadingAndKeepsAuthenticationLive()=runTest {
        val f=Fixture(this)
        f.transport.friends={throw CancellationException("Transport cancelled independently")}
        try {
            f.login();runCurrent()
            assertFalse(f.component.state.value.friends.loading)
            assertEquals(NearbyShellError.UNAVAILABLE,f.component.state.value.friends.error)
            assertTrue(assertNotNull(f.repository.authenticatedAccount.value).job.isActive)
            f.transport.friends={SocialResult.Success(listOf(accepted))};f.component.refreshFriends();runCurrent()
            assertEquals(listOf(accepted),f.component.state.value.friends.items)
        } finally {f.close();runCurrent()}
    }

    @Test fun queuedScreenLoginCannotReplaceAccountEstablishedBeforeItsDispatch()=runTest {
        val f=Fixture(this)
        try {
            runCurrent();f.component.setEmail("queued@example.test");f.component.setPassword("password")
            f.component.submitAuth()
            f.repository.login(LoginRequest("external","password"))
            val original=assertNotNull(f.repository.authenticatedAccount.value)
            runCurrent()
            assertSame(original,f.repository.authenticatedAccount.value)
            assertEquals(listOf("login:external","friends:token-1"),f.transport.issued)
        } finally {f.close();runCurrent()}
    }

    @Test fun contradictoryRowsForSamePersonCannotGrantCallOrFriendRequest()=runTest {
        val f=Fixture(this)
        f.transport.friends={SocialResult.Success(listOf(accepted,accepted.copy(state=FriendshipState.BLOCKED)))}
        f.transport.search={SocialResult.Success(listOf(UserSearchResult(pending.userId,"New"),
            UserSearchResult(pending.userId,"Existing",pending.friendshipId,FriendshipState.BLOCKED)))}
        try {
            f.login();runCurrent();f.component.callFriend(accepted.userId);assertTrue(f.calls.isEmpty())
            f.component.setFriendQuery("person");f.component.searchFriends();runCurrent()
            f.component.requestFriend(pending.userId);runCurrent()
            assertFalse(f.transport.issued.any {it.startsWith("request:")})
        } finally {f.close();runCurrent()}
    }

    private class Drafts(override val actorId: String):InvitationDraftStore {
        override suspend fun read(invitationId: String):InvitationCreationDraft?=null
        override suspend fun retain(draft: InvitationCreationDraft)=draft
    }
    private class Credentials:SocialCredentialStore {
        var value:SocialCredentials?=null
        override suspend fun read()=value
        override suspend fun write(credentials: SocialCredentials) {value=credentials}
        override suspend fun clear() {value=null}
    }
}
