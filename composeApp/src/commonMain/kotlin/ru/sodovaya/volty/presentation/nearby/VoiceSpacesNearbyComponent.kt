package ru.sodovaya.volty.presentation.nearby

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import ru.sodovaya.volty.data.social.*
import ru.sodovaya.volty.di.nearby.NearbyCommunityAccountFactory
import ru.sodovaya.volty.domain.social.*
import ru.sodovaya.volty.domain.nearby.community.invitationDraftId

enum class NearbyAuthMode { LOGIN, REGISTER }
enum class NearbyShellSection { SERVERS, FRIENDS, PROFILE }
enum class NearbyShellError { INVALID_FORM, UNAUTHORIZED, FORBIDDEN, CONFLICT, RATE_LIMITED, UNAVAILABLE }
data class NearbyAuthDraft(val mode: NearbyAuthMode=NearbyAuthMode.LOGIN,
    val email: String="",val password: String="",val displayName: String="") {
    override fun toString()="NearbyAuthDraft(mode=$mode, email=$email, password=<redacted>, displayName=$displayName)"
}
data class NearbyFriendsState(val items: List<FriendSummary>?=null,val loading: Boolean=false,
    val error: NearbyShellError?=null,val query: String="",val results: List<UserSearchResult>?=null,
    val searchLoading: Boolean=false,val searchError: NearbyShellError?=null)
data class NearbyProfileDraft(val displayName: String = "", val saved: Boolean = false)
data class VoiceSpacesNearbyState(val session: SocialSession=SocialSession.LoggedOut,
    val auth: NearbyAuthDraft=NearbyAuthDraft(),val section: NearbyShellSection=NearbyShellSection.SERVERS,
    val busy: Boolean=false,val error: NearbyShellError?=null,val friends: NearbyFriendsState=NearbyFriendsState(),
    val profile: NearbyProfileDraft = NearbyProfileDraft())

/** Forms and account-bound reads belong to this screen; conversation commands belong to Root. */
class DefaultVoiceSpacesNearbyComponent(componentContext: ComponentContext,parent: CoroutineScope,
    private val repository: DefaultSocialRepository,factory: NearbyCommunityAccountFactory,nextId: ()->String,
    connectChannel: (SocialAuthenticatedAccount,String)->Unit,
    private val call: (SocialAuthenticatedAccount,FriendSummary)->Unit,
    private val onBack: ()->Unit):ComponentContext by componentContext {
    init {require(parent.coroutineContext[Job]?.isActive==true)}
    private val lifetime=SupervisorJob(parent.coroutineContext[Job])
    private val scope=CoroutineScope(parent.coroutineContext+lifetime)
    private val form=MutableStateFlow(VoiceSpacesNearbyState())
    private val output=MutableStateFlow(VoiceSpacesNearbyState())
    val state=output.asStateFlow()
    private val binding=ServerWorkspaceAccountBinding(scope,repository.authenticatedAccount,factory,nextId,connectChannel)
    val servers=binding.state
    private val friends=NearbyFriendsPresenter(scope,repository)
    private var closed=false
    private var operation=0L

    init {
        scope.launch(start=CoroutineStart.UNDISPATCHED) {try {awaitCancellation()} finally {close()}}
        lifecycle.doOnDestroy {lifetime.cancel()}
        scope.launch {
            var previous:SocialAuthenticatedAccount?=null
            repository.authenticatedAccount.collectLatest { account ->
                if(account !== previous) {
                    previous=account;operation++
                    form.update {it.copy(auth=NearbyAuthDraft(),section=NearbyShellSection.SERVERS,busy=false,error=null,
                        profile=NearbyProfileDraft())}
                }
                friends.bind(account)
                if(account!=null) {
                    val retired=CompletableDeferred<Unit>()
                    val guardian=CoroutineScope(scope.coroutineContext+account.job).launch(start=CoroutineStart.UNDISPATCHED) {
                        try {awaitCancellation()} finally {retired.complete(Unit)}
                    }
                    try {retired.await()} finally {guardian.cancel();friends.bind(null)}
                }
            }
        }
        scope.launch {
            combine(repository.session,form,friends.state) {session,form,page ->
                val authority=repository.authenticatedAccount.value
                val visible=if(session is SocialSession.Authenticated &&
                    (authority?.job?.isActive!=true || authority.userId!=session.userId)) SocialSession.LoggedOut else session
                form.copy(session=visible,friends=page,
                    profile=if(visible is SocialSession.Authenticated) form.profile else NearbyProfileDraft())
            }
                .collect {if(lifetime.isActive) output.value=it}
        }
    }

    private fun enabled()=lifetime.isActive && !closed
    private fun edit(change: (NearbyAuthDraft)->NearbyAuthDraft) {
        if(enabled() && !form.value.busy && repository.authenticatedAccount.value==null)
            form.update {it.copy(auth=change(it.auth),error=null)}
    }
    fun setAuthMode(value: NearbyAuthMode)=edit {it.copy(mode=value)}
    fun setEmail(value: String) {if(value.length<=254) edit {it.copy(email=value)}}
    fun setPassword(value: String) {if(value.length<=128) edit {it.copy(password=value)}}
    fun setDisplayName(value: String) {if(value.length<=80) edit {it.copy(displayName=value)}}
    fun submitAuth() {
        if(!enabled() || form.value.busy || repository.session.value!=SocialSession.LoggedOut) return
        val draft=form.value.auth
        if(draft.email.isBlank() || draft.password.isBlank() ||
            (draft.mode==NearbyAuthMode.REGISTER && (draft.password.length !in 12..128 || draft.displayName.trim().length !in 2..80))) {
            form.update {it.copy(error=NearbyShellError.INVALID_FORM)};return
        }
        command(null) {
            if(draft.mode==NearbyAuthMode.LOGIN) repository.login(LoginRequest(draft.email.trim(),draft.password))
            else repository.register(RegistrationRequest(draft.email.trim(),draft.password,draft.displayName.trim()))
        }
    }

    private fun command(account: SocialAuthenticatedAccount?,work: suspend ()->SocialResult<Unit>) {
        if(!enabled() || form.value.busy || (account!=null && !friends.current(account))) return
        val token=++operation
        form.update {it.copy(busy=true,error=null)}
        scope.launch {
            try {
                if(!enabled() || token!=operation || (account!=null && !friends.current(account))) return@launch
                if(account==null && (repository.session.value!=SocialSession.LoggedOut || repository.authenticatedAccount.value!=null)) return@launch
                val result=work()
                if(enabled() && token==operation && (account==null || friends.current(account)))
                    form.update {it.copy(error=(result as? SocialResult.Failure)?.error?.shellError())}
            } catch(cancelled: CancellationException) {throw cancelled}
            catch(_: Exception) {
                if(enabled() && token==operation && (account==null || friends.current(account)))
                    form.update {it.copy(error=NearbyShellError.UNAVAILABLE)}
            } finally {if(enabled() && token==operation) form.update {it.copy(busy=false)}}
        }
    }

    fun logout() {repository.authenticatedAccount.value?.let {command(it) {repository.logout()}}}
    fun openProfile() {
        val account=repository.authenticatedAccount.value ?: return
        val profile=repository.session.value as? SocialSession.Authenticated ?: return
        if(!enabled() || form.value.busy || !friends.current(account) || profile.userId!=account.userId) return
        form.update {it.copy(section=NearbyShellSection.PROFILE,error=null,profile=NearbyProfileDraft(profile.displayName))}
    }
    fun setProfileName(value: String) {
        val account=repository.authenticatedAccount.value ?: return
        if(enabled() && !form.value.busy && friends.current(account) &&
            form.value.section==NearbyShellSection.PROFILE && value.length<=80)
            form.update {it.copy(profile=NearbyProfileDraft(value))}
    }
    fun submitProfile() {
        val account=repository.authenticatedAccount.value ?: return
        if(!enabled() || form.value.busy || !friends.current(account) || form.value.section!=NearbyShellSection.PROFILE) return
        val name=form.value.profile.displayName.trim()
        if(name.length !in 2..80) {form.update {it.copy(error=NearbyShellError.INVALID_FORM)};return}
        command(account) {
            when(val result=repository.updateProfile(ProfileUpdate(name))) {
                is SocialResult.Failure -> result
                is SocialResult.Success -> {
                    if(enabled() && friends.current(account))
                        form.update {it.copy(profile=NearbyProfileDraft(result.value.displayName,saved=true))}
                    SocialResult.Success(Unit)
                }
            }
        }
    }
    fun openServers() {if(enabled()) form.update {it.copy(section=NearbyShellSection.SERVERS)}}
    fun openFriends() {if(enabled()) form.update {it.copy(section=NearbyShellSection.FRIENDS)}}
    fun refreshFriends() {if(enabled()) friends.refresh()}
    fun setFriendQuery(value: String) {if(enabled()) friends.setQuery(value)}
    fun searchFriends() {if(enabled()) friends.search()}
    fun requestFriend(user: SocialUserId) {
        val account=repository.authenticatedAccount.value ?: return
        if(friends.unlinked(user)==null) return
        command(account) {
            if(friends.unlinked(user)==null) return@command SocialResult.Failure(SocialFailure.Conflict)
            val result=repository.sendFriendRequest(FriendRequest(user))
            if(result is SocialResult.Success && friends.current(account)) {friends.requested(user);friends.refresh()}
            result
        }
    }
    fun respondToFriend(id: FriendshipId,accept: Boolean) {
        val account=repository.authenticatedAccount.value ?: return
        if(friends.incoming(id)==null) return
        command(account) {
            if(friends.incoming(id)==null) return@command SocialResult.Failure(SocialFailure.Conflict)
            val result=repository.respondToFriendRequest(id.value,accept)
            if(result is SocialResult.Success && friends.current(account)) friends.refresh()
            result
        }
    }
    fun callFriend(user: SocialUserId) {
        val account=repository.authenticatedAccount.value ?: return
        if(!enabled() || form.value.busy || !friends.current(account) ||
            runCatching {invitationDraftId(user.value)}.isFailure || user==account.userId) return
        friends.accepted(user)?.let {call(account,it)}
    }
    fun retryServers() {if(enabled()) binding.retry()}
    fun back() {if(enabled()) onBack()}
    suspend fun close():Unit=withContext(NonCancellable) {
        if(closed) return@withContext
        closed=true;lifetime.cancel();binding.close();friends.bind(null)
        form.value=VoiceSpacesNearbyState();output.value=VoiceSpacesNearbyState()
    }
}
