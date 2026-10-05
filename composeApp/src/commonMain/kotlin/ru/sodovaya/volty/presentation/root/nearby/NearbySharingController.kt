package ru.sodovaya.volty.presentation.root.nearby

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import ru.sodovaya.volty.data.social.*
import ru.sodovaya.volty.data.voice.*
import ru.sodovaya.volty.domain.nearby.*
import ru.sodovaya.volty.domain.voice.VoiceOperationId
import ru.sodovaya.volty.domain.voice.VoiceSessionTag
import ru.sodovaya.volty.domain.nearby.community.invitationDraftId

enum class NearbySharingError { LOCATION_DENIED, NOT_VISIBLE, CLEANUP_REQUIRED, UNAVAILABLE }
data class NearbyPendingSharingView(val id: String,val operation: VoiceOperationId,val mode: ChannelSharingMode,val ttl: ChannelSharingTtl)
data class NearbySharingView(val pending: NearbyPendingSharingView?=null,val operation: VoiceOperationId?=null,
    val generation: String?=null,val wanted: Boolean=false,val preparing: Boolean=false,val active: Boolean=false,
    val localCleanupPending: Boolean=false,val serverConfirmationPending: Boolean=false,val error: NearbySharingError?=null,
    val mode: ChannelSharingMode?=null,val ttl: ChannelSharingTtl?=null)

/** Process-owned consent and presentation. Activity permission callbacks are disposable borrowers. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class NearbySharingController(private val repository: DefaultSocialRepository,private val voice: VoiceConversationStartup,
    private val startup: ChannelSharingStartup,private val switches: NearbySharingSwitchGuard,
    private val queued: CoroutineDispatcher,private val nextId: ()->String) {
    private val parent=repository.applicationScope()
    private val root=checkNotNull(parent.coroutineContext[Job])
    init {require(root.isActive && queued.isDispatchNeeded(parent.coroutineContext))}
    private val lifetime=SupervisorJob(root)
    private val scope=CoroutineScope(parent.coroutineContext+lifetime+queued)
    private data class Request(val id: String,val account: SocialAuthenticatedAccount,val operation: VoiceOperationId,
        val original: VoiceSessionTag,val mode: ChannelSharingMode,val ttl: ChannelSharingTtl) {
        fun view()=NearbyPendingSharingView(id,operation,mode,ttl)
    }
    private class Owned(val request: Request,val handle: ChannelSharingStartHandle) {
        var observer: Job?=null
        var cleanup: Job?=null
        var confirmed=false
    }
    private var pending: Request?=null
    private var starting: Request?=null
    private var work: Job?=null
    private var owner: Owned?=null
    private var failure: Pair<SocialAuthenticatedAccount,NearbySharingError>?=null
    private var closed=false
    private val issued=mutableSetOf<String>()
    private val output=MutableStateFlow(NearbySharingView())
    val state=output.asStateFlow()
    init {
        scope.launch(start=CoroutineStart.UNDISPATCHED) {try {awaitCancellation()} finally {close()}}
        scope.launch {voice.state.collect {
            pending?.takeIf {!matches(it)}?.let {pending=null}
            starting?.takeIf {!matches(it)}?.let {work?.cancel();starting=null}
            owner?.takeIf {!matches(it.request)}?.let {it.handle.requestHide();cleanup(it)}
            render()
        }}
        scope.launch {repository.authenticatedAccount.collectLatest {account ->
            if(pending?.account!==account) pending=null
            if(starting?.account!==account) {work?.cancel();starting=null}
            owner?.takeIf {it.request.account!==account}?.let {
                it.handle.requestHide();cleanup(it);it.observer?.cancel();owner=null
            }
            if(failure?.first!==account) failure=null
            render()
            if(account!=null) {
                val retired=CompletableDeferred<Unit>()
                val guardian=account.scope().launch(start=CoroutineStart.UNDISPATCHED) {
                    try {awaitCancellation()} finally {retired.complete(Unit)}
                }
                try {retired.await()} finally {
                    guardian.cancel()
                    if(pending?.account===account) pending=null
                    if(starting?.account===account) {work?.cancel();starting=null}
                    owner?.takeIf {it.request.account===account}?.let {it.handle.requestHide();cleanup(it);it.observer?.cancel();owner=null}
                    if(failure?.first===account) failure=null
                    render()
                }
            }
        }}
    }
    private fun current(account: SocialAuthenticatedAccount)=!closed && lifetime.isActive && root.isActive &&
        account.job.isActive && repository.authenticatedAccount.value===account
    private fun original(account: SocialAuthenticatedAccount,operation: VoiceOperationId):VoiceSessionTag? {
        if(!current(account)) return null
        val source=voice.state.value;val actual=source.voice ?: return null
        if(source.operationId!=operation || source.phase!=VoiceConversationPhase.JOIN_SUBMITTED ||
            source.target !is VoiceConversationTarget.Channel || !actual.wanted) return null
        return actual.sessionId?.let {VoiceSessionTag(it,actual.generation)}
    }
    private fun matches(request: Request)=original(request.account,request.operation)==request.original
    private fun selected(request: Request)=starting===request && matches(request)
    private fun busy()=starting!=null || owner?.handle?.state?.value?.let {it.wanted || it.preparing}==true
    fun request(operation: VoiceOperationId,mode: ChannelSharingMode,ttl: ChannelSharingTtl):String? {
        val account=repository.authenticatedAccount.value ?: return null
        val tag=original(account,operation) ?: return null
        if(busy()) return null
        val id=nextId().also(::invitationDraftId)
        scope.launch {
            if(original(account,operation)!=tag || busy()) return@launch
            if(!issued.add(id)) {failure=account to NearbySharingError.UNAVAILABLE;render();return@launch}
            pending=Request(id,account,operation,tag,mode,ttl);failure=null;render()
        }
        return id
    }
    fun begin(id: String,locationGranted: Boolean,boundary: NearbyVisibleLaunchBoundary) {scope.launch {
        val request=pending?.takeIf {it.id==id} ?: return@launch
        if(!matches(request)) {pending=null;render();return@launch}
        pending=null
        if(!locationGranted || !boundary.available) {
            failure=request.account to if(!locationGranted) NearbySharingError.LOCATION_DENIED else NearbySharingError.NOT_VISIBLE
            render();return@launch
        }
        starting=request;failure=null
        work=scope.launch(start=CoroutineStart.LAZY) {execute(request,boundary)}
        render();work?.start()
    }}
    private suspend fun execute(request: Request,boundary: NearbyVisibleLaunchBoundary) {
        var acquired: Owned?=null;var active=false
        try {
            val old=owner
            if(old!=null && !switches.waitSharingCleanup(old.request.operation)) {
                if(selected(request)) failure=request.account to NearbySharingError.CLEANUP_REQUIRED
                return
            }
            if(!selected(request)) return
            if(!boundary.available) {failure=request.account to NearbySharingError.NOT_VISIBLE;return}
            val handle=startup.start(request.account,request.original,request.id,request.mode,request.ttl,boundary::launchLocation)
            acquired=Owned(request,handle)
            if(!selected(request) || !switches.remember(request.account,request.operation,handle)) return
            old?.observer?.cancel();owner=acquired
            acquired.observer=scope.launch {
                combine(handle.state,handle.capture.flatMapLatest {capture ->
                    capture?.locationForegroundState ?: flowOf(ChannelLocationForegroundState())
                }) {_,_ -> Unit}.collect {
                    if(!handle.state.value.wanted) cleanup(acquired)
                    render()
                }
            }
            render()
            active=handle.result.await()
            if(!selected(request)) return
            if(!active) failure=request.account to when {
                !boundary.available -> NearbySharingError.NOT_VISIBLE
                handle.state.value.launchResult==ChannelSharingForegroundLaunch.PERMISSION_REQUIRED -> NearbySharingError.LOCATION_DENIED
                handle.state.value.originalCleanupPending -> NearbySharingError.CLEANUP_REQUIRED
                else -> NearbySharingError.UNAVAILABLE
            }
        } catch(cancelled: CancellationException) {
            if(currentCoroutineContext().isActive && selected(request)) failure=request.account to NearbySharingError.UNAVAILABLE
        } catch(_: Exception) {if(selected(request)) failure=request.account to NearbySharingError.UNAVAILABLE}
        finally {
            if(!active || !selected(request)) acquired?.let {it.handle.requestHide();cleanup(it)}
            if(starting===request) starting=null
            render()
        }
    }
    private fun cleanup(owned: Owned) {
        if(owned.cleanup?.isActive==true || owned.confirmed || !lifetime.isActive) return
        owned.cleanup=scope.launch(start=CoroutineStart.LAZY) {
            try {
                val clean=if(owner===owned) switches.waitSharingCleanup(owned.request.operation)
                    else owned.handle.waitLocalCleanup() && owned.handle.recoverOriginalCleanup()
                owned.confirmed=clean
                if(owner===owned && current(owned.request.account)) {
                    if(!clean) failure=owned.request.account to NearbySharingError.CLEANUP_REQUIRED
                    else if(failure?.second==NearbySharingError.CLEANUP_REQUIRED) failure=null
                }
            } catch(cancelled: CancellationException) {throw cancelled}
              catch(_: Exception) {if(owner===owned && current(owned.request.account)) failure=owned.request.account to NearbySharingError.CLEANUP_REQUIRED}
            finally {render()}
        }.also {it.start()}
    }
    fun hide(operation: VoiceOperationId,generation: String) {scope.launch {
        val owned=owner?.takeIf {it.request.operation==operation && it.request.id==generation} ?: return@launch
        owned.handle.requestHide();cleanup(owned);render()
    }}
    fun cancelRequest(id: String) {scope.launch {
        if(pending?.id==id) pending=null
        if(starting?.id==id) {work?.cancel();starting=null}
        owner?.takeIf {it.request.id==id}?.let {it.handle.requestHide();cleanup(it)}
        render()
    }}
    fun retryCleanup(operation: VoiceOperationId,generation: String) {scope.launch {
        val owned=owner?.takeIf {it.request.operation==operation && it.request.id==generation && !it.handle.state.value.wanted} ?: return@launch
        cleanup(owned);render()
    }}
    private fun render() {
        if(closed) {output.value=NearbySharingView();return}
        val owned=owner?.takeIf {current(it.request.account)}
        val preparing=starting?.takeIf {current(it.account)}
        val visible=preparing ?: owned?.request
        val resource=owned?.handle?.state?.value
        val native=owned?.handle?.capture?.value?.locationForegroundState?.value
        output.value=NearbySharingView(pending=pending?.takeIf {matches(it)}?.view(),operation=visible?.operation,generation=visible?.id,
            wanted=if(preparing!=null && owned?.request!==preparing) true else resource?.wanted==true,
            preparing=preparing!=null || resource?.preparing==true,
            active=resource?.active==true && resource.wanted && visible!=null && owned?.request===visible && matches(visible),
            localCleanupPending=resource?.wanted==false && (resource.preparing || resource.nativeCleanupPending || resource.childCleanupPending ||
                native?.let {it.nativeOwned || it.nativeInFlight>0 || it.cleanupFailed}==true),
            serverConfirmationPending=resource?.wanted==false && owned.confirmed.not() &&
                (resource.journalCleanupPending || resource.originalCleanupPending ||
                    (resource.childCleanupPending && owned.handle.capture.value!=null)),
            error=failure?.takeIf {current(it.first)}?.second,mode=visible?.mode,ttl=visible?.ttl)
    }
    suspend fun close():Unit=withContext(NonCancellable+queued) {
        if(closed) return@withContext
        closed=true;pending=null;starting=null;work?.cancel()
        val original=owner;owner=null
        original?.handle?.requestHide();original?.observer?.cancel();original?.cleanup?.cancel();lifetime.cancel()
        original?.handle?.close();output.value=NearbySharingView()
    }
}
