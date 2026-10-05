package ru.sodovaya.volty.presentation.nearby

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import ru.sodovaya.volty.data.social.*
import ru.sodovaya.volty.domain.social.*

/** Screen-local friendship reads. The repository still owns credentials and their generation. */
internal class NearbyFriendsPresenter(private val scope: CoroutineScope,
    private val repository: DefaultSocialRepository) {
    private var account: SocialAuthenticatedAccount? = null
    private var readVersion = 0L
    private var searchVersion = 0L
    private var reader: Job? = null
    private var searcher: Job? = null
    private val output = MutableStateFlow(NearbyFriendsState())
    val state = output.asStateFlow()

    fun current(authority: SocialAuthenticatedAccount) = scope.isActive && authority.job.isActive &&
        account === authority && repository.authenticatedAccount.value === authority

    fun bind(authority: SocialAuthenticatedAccount?) {
        account = authority
        readVersion++;searchVersion++
        reader?.cancel();searcher?.cancel()
        output.value = NearbyFriendsState()
        authority?.let { refresh() }
    }

    fun refresh() {
        val authority = account ?: return
        if (!current(authority) || output.value.loading) return
        val version = ++readVersion
        output.update { it.copy(loading=true,error=null) }
        reader = scope.launch {
            if (!current(authority)) return@launch
            val result = reply { repository.listFriends() }
            if (!current(authority) || version != readVersion) return@launch
            output.update { when(result) {
                is SocialResult.Success -> it.copy(items=result.value,loading=false,error=null)
                is SocialResult.Failure -> it.copy(items=null,loading=false,error=result.error.shellError())
            } }
        }
    }

    fun setQuery(query: String) {
        val authority = account ?: return
        if (!current(authority) || query.length>80 || query == output.value.query) return
        searchVersion++;searcher?.cancel()
        output.update { it.copy(query=query,results=null,searchLoading=false,searchError=null) }
    }

    fun search() {
        val authority = account ?: return
        if (!current(authority) || output.value.searchLoading) return
        val query = output.value.query.trim()
        if (query.isEmpty()) {output.update {it.copy(results=emptyList(),searchError=null)};return}
        val version = ++searchVersion
        output.update {it.copy(searchLoading=true,searchError=null,results=null)}
        searcher = scope.launch {
            if (!current(authority)) return@launch
            val result = reply {repository.searchUsers(query)}
            if (!current(authority) || searchVersion != version) return@launch
            output.update {when(result) {
                is SocialResult.Success -> it.copy(results=result.value,searchLoading=false,searchError=null)
                is SocialResult.Failure -> it.copy(results=null,searchLoading=false,searchError=result.error.shellError())
            }}
        }
    }

    fun accepted(user: SocialUserId): FriendSummary? = output.value.takeUnless {it.loading || it.error != null}
        ?.items?.singleOrNull {it.userId==user}?.takeIf {it.state==FriendshipState.ACCEPTED}
    fun incoming(id: FriendshipId) = output.value.takeUnless {it.loading || it.error != null}
        ?.items?.singleOrNull {it.friendshipId==id}?.takeIf {it.state==FriendshipState.REQUEST_RECEIVED}
    fun unlinked(user: SocialUserId) = output.value.takeUnless {it.searchLoading || it.searchError != null}
        ?.results?.singleOrNull {it.userId==user}?.takeIf {it.state==null && it.friendshipId==null && user!=account?.userId}
    fun requested(user: SocialUserId) {output.update {page ->page.copy(results=page.results?.map {
        if(it.userId==user) it.copy(state=FriendshipState.REQUEST_SENT) else it
    })}}

    private suspend fun <T> reply(call: suspend ()->SocialResult<T>):SocialResult<T> = try {call()}
    catch(cancelled: CancellationException) {
        currentCoroutineContext().ensureActive()
        SocialResult.Failure(SocialFailure.Network())
    }
    catch(_: Exception) {SocialResult.Failure(SocialFailure.Network())}
}

internal fun SocialFailure.shellError(): NearbyShellError = when(this) {
    SocialFailure.Unauthorized -> NearbyShellError.UNAUTHORIZED
    SocialFailure.Forbidden -> NearbyShellError.FORBIDDEN
    SocialFailure.Conflict -> NearbyShellError.CONFLICT
    is SocialFailure.RateLimited -> NearbyShellError.RATE_LIMITED
    is SocialFailure.InvalidRequest -> NearbyShellError.INVALID_FORM
    else -> NearbyShellError.UNAVAILABLE
}
