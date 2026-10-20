package ru.sodovaya.volty.presentation.root.nearby

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import ru.sodovaya.volty.data.social.*
import ru.sodovaya.volty.data.voice.*
import ru.sodovaya.volty.domain.voice.*
import ru.sodovaya.volty.domain.nearby.community.invitationDraftId
import ru.sodovaya.volty.domain.social.FriendSummary
import ru.sodovaya.volty.domain.social.FriendshipState
import ru.sodovaya.volty.data.nearby.DirectCallSnapshot
import ru.sodovaya.volty.data.nearby.DirectCallPhase

internal interface NearbyVoiceSwitchGuard {
    fun requiresConfirmation(operation: VoiceOperationId?): Boolean
    fun withdrawSharing(operation: VoiceOperationId?)
    suspend fun awaitSharingCleanup(operation: VoiceOperationId?): Boolean
    suspend fun waitSharingCleanup(operation: VoiceOperationId?): Boolean
}
enum class NearbyVoiceError { MICROPHONE_DENIED, NOT_VISIBLE, CLEANUP_REQUIRED, UNAVAILABLE }
enum class NearbyVoiceUiPhase { IDLE, PREPARING, ACTIVE, FAILED }
enum class NearbyConversationKind { CHANNEL, CALL }
data class NearbyPendingVoiceView(val id: String,val label: String,
    val confirmationRequired: Boolean=false,val confirmed: Boolean=false,
    val kind: NearbyConversationKind=NearbyConversationKind.CHANNEL)
data class NearbyVoiceView(val pending: NearbyPendingVoiceView?=null,val label: String="",
    val operation: VoiceOperationId?=null,val phase: NearbyVoiceUiPhase=NearbyVoiceUiPhase.IDLE,
    val voice: VoiceSessionState?=null,val error: NearbyVoiceError?=null,
    val starting: NearbyPendingVoiceView?=null,val kind: NearbyConversationKind?=null,
    val callRoster: DirectCallRosterPresenceView?=null,
    val channelMap: NearbyChannelMapState=NearbyChannelMapState(),
    val channelRoster: VoiceRosterPresenceView?=null,
    val audio: VoiceConversationAudioState?=null)

/** All mutations run on the queued process owner. UI supplies explicit permission results and a disposable launcher. */
internal class NearbyVoiceController(private val repository: DefaultSocialRepository,private val voice: VoiceConversationStartup,
    private val queued: CoroutineDispatcher,private val nextId: ()->String,private val readPreferences: suspend ()->VoiceAudioPreferences,
    private val switches: NearbyVoiceSwitchGuard,
    /** Called just before a server conversation is started; the offline radio uses it to stop itself. */
    private val onConversationStarting: () -> Unit = {}) {
    private val parent=repository.applicationScope()
    private val root=checkNotNull(parent.coroutineContext[Job])
    init {require(root.isActive && queued.isDispatchNeeded(parent.coroutineContext))}
    private val lifetime=SupervisorJob(root)
    private val scope=CoroutineScope(parent.coroutineContext+lifetime+queued)
    private data class Request(val id: String,val account: SocialAuthenticatedAccount,val target: VoiceConversationTarget,
        val label: String,val original: VoiceOperationId?,val confirmation: Boolean,val confirmed: Boolean) {
        val operation get()=VoiceOperationId(id)
        val kind get()=if(target is VoiceConversationTarget.Channel) NearbyConversationKind.CHANNEL else NearbyConversationKind.CALL
        fun view()=NearbyPendingVoiceView(id,label,confirmation,confirmed,kind)
    }
    private class Start(val request: Request) {var job: Job?=null}
    private var pending: Request?=null
    private var starting: Start?=null
    private var committed: Request?=null
    private var failure: Pair<SocialAuthenticatedAccount,NearbyVoiceError>?=null
    private var closed=false
    private val issued=mutableSetOf<String>()
    private val output=MutableStateFlow(NearbyVoiceView())
    val state=output.asStateFlow()

    init {
        scope.launch(start=CoroutineStart.UNDISPATCHED) {try {awaitCancellation()} finally {close()}}
        scope.launch {voice.state.collect {source ->
            pending?.takeIf {it.original!=source.operationId}?.let {pending=null}
            if (source.phase == VoiceConversationPhase.STOPPED && committed?.operation == source.operationId)
                committed = null
            render()
        }}
        scope.launch {repository.authenticatedAccount.collectLatest {account ->
            listOfNotNull(pending,starting?.request,committed).filter {it.account!==account}.forEach {retire(it.account)}
            if(account!=null) {
                val retired=CompletableDeferred<Unit>()
                val guardian=account.scope().launch(start=CoroutineStart.UNDISPATCHED) {
                    try {awaitCancellation()} finally {retired.complete(Unit)}
                }
                try {retired.await()} finally {guardian.cancel();retire(account)}
            }
            render()
        }}
    }
    private fun current(account: SocialAuthenticatedAccount)=!closed && lifetime.isActive && root.isActive &&
        account.job.isActive && repository.authenticatedAccount.value===account
    private fun retire(account: SocialAuthenticatedAccount) {
        if(pending?.account===account) pending=null
        if(starting?.request?.account===account) {starting?.job?.cancel();starting=null}
        if(committed?.account===account) {switches.withdrawSharing(committed?.operation);committed=null}
        if(failure?.first===account) failure=null
        render()
    }
    private fun render() {
        if(closed) {output.value=NearbyVoiceView();return}
        val source=voice.state.value
        val owner=committed?.takeIf {current(it.account) && source.operationId==it.operation}
        val preparing=starting?.request?.takeIf {current(it.account)}
        val active=owner?.takeIf {source.phase==VoiceConversationPhase.PREPARING || source.phase==VoiceConversationPhase.JOIN_SUBMITTED}
        val visible=active ?: preparing ?: owner
        output.value=NearbyVoiceView(
            pending=pending?.takeIf {current(it.account)}?.view(),label=visible?.label.orEmpty(),operation=visible?.operation,
            phase=when {
                active!=null -> if(source.phase==VoiceConversationPhase.JOIN_SUBMITTED) NearbyVoiceUiPhase.ACTIVE else NearbyVoiceUiPhase.PREPARING
                preparing!=null -> NearbyVoiceUiPhase.PREPARING
                owner!=null && source.phase==VoiceConversationPhase.FAILED -> NearbyVoiceUiPhase.FAILED
                else -> NearbyVoiceUiPhase.IDLE
            },voice=source.voice.takeIf {owner!=null && source.phase==VoiceConversationPhase.JOIN_SUBMITTED},
            error=failure?.takeIf {current(it.first)}?.second,starting=preparing?.view(),kind=visible?.kind,
            callRoster=source.callRoster.takeIf {owner!=null && source.phase==VoiceConversationPhase.JOIN_SUBMITTED},
            channelMap=nearbyChannelMapState(source,owner?.operation),
            channelRoster=source.channelRoster.takeIf {owner!=null && source.phase==VoiceConversationPhase.JOIN_SUBMITTED},
            audio=source.audio.takeIf {owner!=null && source.phase==VoiceConversationPhase.JOIN_SUBMITTED})
    }
    fun requestChannel(account: SocialAuthenticatedAccount,channel: String,label: String):String? {
        if(!current(account) || runCatching {invitationDraftId(channel)}.isFailure) return null
        return request(account,label) {VoiceConversationTarget.Channel(channel)}
    }
    fun requestFriendCall(account: SocialAuthenticatedAccount,friend: FriendSummary):String? {
        if(!current(account) || friend.state!=FriendshipState.ACCEPTED || friend.userId==account.userId ||
            runCatching {invitationDraftId(friend.userId.value)}.isFailure) return null
        return request(account,friend.displayName) {operation ->
            VoiceConversationTarget.OutgoingCall(nextId().also(::invitationDraftId),friend.userId.value,operation)
        }
    }
    fun requestIncomingCall(account: SocialAuthenticatedAccount,call: DirectCallSnapshot,label: String):String? {
        if(!current(account) || call.calleeId!=account.userId.value || call.callerId==account.userId.value ||
            call.phase!=DirectCallPhase.RINGING || call.revision<0 || call.ringDeadlineEpochMillis<0 || call.outcome!=null ||
            runCatching {invitationDraftId(call.callId);invitationDraftId(call.callerId)}.isFailure) return null
        return request(account,label) {VoiceConversationTarget.IncomingCall(call)}
    }
    private fun request(account: SocialAuthenticatedAccount,label: String,target: (String)->VoiceConversationTarget):String {
        val id=nextId().also(::invitationDraftId)
        val destination=target(id)
        scope.launch {
            if(!current(account)) return@launch
            if(!issued.add(id)) {failure=account to NearbyVoiceError.UNAVAILABLE;render();return@launch}
            starting?.job?.cancel();starting=null
            val original=voice.state.value.operationId
            val confirm=switches.requiresConfirmation(original)
            pending=Request(id,account,destination,label.take(160).filterNot {it.isISOControl()},original,confirm,!confirm)
            failure=null;render()
        }
        return id
    }
    fun confirmSwitch(id: String) {scope.launch {
        val request=pending?.takeIf {it.id==id && current(it.account) && it.original==voice.state.value.operationId} ?: return@launch
        pending=request.copy(confirmed=true);render()
    }}
    fun begin(id: String,microphoneGranted: Boolean,boundary: NearbyVisibleLaunchBoundary) {scope.launch {
        val request=pending?.takeIf {it.id==id} ?: return@launch
        if(!current(request.account) || voice.state.value.operationId!=request.original) {pending=null;render();return@launch}
        if(!request.confirmed) return@launch
        if(!microphoneGranted || !boundary.available) {
            pending=null;failure=request.account to if(!microphoneGranted) NearbyVoiceError.MICROPHONE_DENIED else NearbyVoiceError.NOT_VISIBLE
            render();return@launch
        }
        pending=null
        val work=Start(request);starting=work;failure=null
        work.job=scope.launch(start=CoroutineStart.LAZY) {execute(work,boundary)}
        render();work.job?.start()
    }}
    private fun selected(work: Start)=starting===work && current(work.request.account)
    private suspend fun execute(work: Start,boundary: NearbyVisibleLaunchBoundary) {
        val request=work.request;var submitted=false
        try {
            if(!selected(work) || !boundary.available || voice.state.value.operationId!=request.original) return
            val destination=when(val target=request.target) {
                is VoiceConversationTarget.IncomingCall -> {
                    val verified=voice.prepareIncoming(request.account,target.call)
                    if(verified==null) {
                        if(selected(work)) failure=request.account to NearbyVoiceError.UNAVAILABLE
                        return
                    }
                    VoiceConversationTarget.IncomingCall(verified)
                }
                else -> target
            }
            if(!selected(work) || voice.state.value.operationId!=request.original) return
            if(!boundary.available) {failure=request.account to NearbyVoiceError.NOT_VISIBLE;return}
            switches.withdrawSharing(request.original)
            request.original?.let {voice.stop(it)}
            if(!selected(work)) return
            if(!switches.waitSharingCleanup(request.original)) {
                if(selected(work)) failure=request.account to NearbyVoiceError.CLEANUP_REQUIRED
                return
            }
            if(!selected(work) || voice.state.value.operationId!=request.original) return
            if(!boundary.available) {failure=request.account to NearbyVoiceError.NOT_VISIBLE;return}
            onConversationStarting()
            voice.start(request.account,destination,request.operation,readPreferences=readPreferences,launch=boundary::launchVoice)
            if(!selected(work) || voice.state.value.operationId!=request.operation) return
            committed=request;submitted=true
        } catch(cancelled: CancellationException) {
            if(currentCoroutineContext().isActive && selected(work)) failure=request.account to NearbyVoiceError.UNAVAILABLE
        } catch(_: Exception) {if(selected(work)) failure=request.account to NearbyVoiceError.UNAVAILABLE}
        finally {
            if(!submitted) withContext(NonCancellable) {voice.stop(request.operation)}
            if(starting===work) {starting=null;render()}
        }
    }
    fun cancelRequest(id: String) {scope.launch {
        if(pending?.id==id) pending=null
        if(starting?.request?.id==id) {starting?.job?.cancel();starting=null;voice.stop(VoiceOperationId(id))}
        render()
    }}
    fun disconnect(operation: VoiceOperationId) {scope.launch {
        val start=starting?.takeIf {it.request.operation==operation || it.request.original==operation}
        val owner=committed?.takeIf {it.operation==operation && voice.state.value.operationId==operation}
        if(start==null && owner==null) return@launch
        if(owner!=null) committed=null
        if(pending?.original==operation) pending=null
        start?.let {it.job?.cancel();starting=null;voice.stop(it.request.operation)}
        switches.withdrawSharing(owner?.operation ?: start?.request?.original)
        voice.stop(operation);render()
    }}
    fun configureAudio(original:VoiceConversationAudioState, preferences:VoiceAudioPreferences) {scope.launch {
        val owner=committed ?: return@launch
        if(!current(owner.account) || owner.operation!=original.operation || voice.state.value.audio!=original || original.pending!=null) return@launch
        voice.configureAudio(original.operation,preferences,original.tag)
    }}
    fun setMuted(operation: VoiceOperationId,value: Boolean) {scope.launch {voice.setMuted(operation,value)}}
    fun setDeafened(operation: VoiceOperationId,value: Boolean) {scope.launch {voice.setDeafened(operation,value)}}
    suspend fun close():Unit=withContext(NonCancellable+queued) {
        if(closed) return@withContext
        closed=true
        val start=starting;val owner=committed
        pending=null;starting=null;committed=null
        start?.job?.cancel();lifetime.cancel()
        switches.withdrawSharing(owner?.operation ?: start?.request?.original)
        start?.let {voice.stop(it.request.operation)};owner?.let {voice.stop(it.operation)}
        output.value=NearbyVoiceView()
    }
}
