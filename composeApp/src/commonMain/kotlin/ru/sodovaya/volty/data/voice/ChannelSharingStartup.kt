package ru.sodovaya.volty.data.voice

import io.ktor.client.HttpClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.sodovaya.volty.data.social.SocialAuthenticatedAccount
import ru.sodovaya.volty.domain.location.RideLocationRepository
import ru.sodovaya.volty.domain.nearby.*
import ru.sodovaya.volty.domain.social.EarnedTelemetry
import ru.sodovaya.volty.domain.voice.VoiceSessionTag
import ru.sodovaya.volty.domain.nearby.community.invitationDraftId
import ru.sodovaya.volty.data.nearby.nearbyHttpsApiBaseUrl

internal enum class ChannelSharingForegroundLaunch { STARTED, PERMISSION_REQUIRED, NOT_VISIBLE, REJECTED }
internal data class ChannelSharingStartupState(val wanted: Boolean=true,val preparing: Boolean=true,
    val active: Boolean=false,val launchResult: ChannelSharingForegroundLaunch?=null,
    val nativeCleanupPending: Boolean=false,val childCleanupPending: Boolean=false,
    val originalCleanupPending: Boolean=false)
/** Own capture/control and actual preparation producer; borrower of voice/auth/navigation. */
internal class ChannelSharingStartHandle internal constructor(private val account: SocialAuthenticatedAccount,
    private val original: VoiceSessionTag,
    private val queued: CoroutineDispatcher,launch: (ChannelLocationForegroundTicket)->ChannelSharingForegroundLaunch,
    private val recover: suspend ()->Boolean,
    prepare: suspend (ChannelSharingStartHandle)->Boolean) {
    private val mutableState=MutableStateFlow(ChannelSharingStartupState())
    val state=mutableState.asStateFlow()
    private val mutableCapture=MutableStateFlow<ChannelSharingCaptureOwner?>(null)
    val capture=mutableCapture.asStateFlow()
    private val mutableRevoke=MutableStateFlow<Deferred<ChannelSharingReply>?>(null)
    val originalRevoke=mutableRevoke.asStateFlow()
    internal fun belongsTo(account: SocialAuthenticatedAccount,original: VoiceSessionTag)=this.account===account && this.original==original
    private var control: ChannelSharingAccountControl?=null
    private val launcher=MutableStateFlow<((ChannelLocationForegroundTicket)->ChannelSharingForegroundLaunch)?>(launch)
    private val lifetime=SupervisorJob(checkNotNull(account.retirementParentScope().coroutineContext[Job]))
    private val scope=CoroutineScope(account.retirementParentScope().coroutineContext+lifetime+queued)
    private val cleanup=Mutex()
    private var accountGuardian: Job?=null
    private var rootGuardian: Job?=null
    private var captureObserver: Job?=null
    private var childDrain: Job?=null
    val result: Deferred<Boolean> = scope.async(start=CoroutineStart.LAZY) {
        var active=false
        try {ensureWanted();active=prepare(this@ChannelSharingStartHandle);ensureWanted()
            mutableState.update {it.copy(active=active)};active
        } catch(_: CancellationException) {false}
          catch(_: Exception) {false}
          finally {withContext(NonCancellable+queued) {
              mutableState.update {it.copy(preparing=false)}
              if(!active) requestHide()
              if(!mutableState.value.wanted) cleanupOwned()
          }}
    }
    init {
        accountGuardian=account.scope().launch(start=CoroutineStart.UNDISPATCHED) {
            try {awaitCancellation()} finally {requestHide()}
        }
        rootGuardian=scope.launch(start=CoroutineStart.UNDISPATCHED) {
            try {awaitCancellation()} finally {withContext(NonCancellable+queued) {requestHide();cleanupOwned()}}
        }
        result.start()
    }
    internal fun own(value: ChannelSharingAccountControl) {control=value}
    internal fun recoveryPending() {mutableState.update {it.copy(originalCleanupPending=true)}}
    internal fun own(value: ChannelSharingCaptureOwner) {
        mutableCapture.value=value
        captureObserver=scope.launch {
            value.locationForegroundState.collect {resource ->
                if(!resource.wanted || resource.retired) {
                    requestHide()
                    if(!resource.nativeOwned && resource.nativeInFlight==0 && !resource.cleanupFailed) cleanupOwned()
                }
            }
        }
    }
    internal fun ensureWanted() {
        check(mutableState.value.wanted && account.job.isActive && lifetime.isActive) {"Original sharing preparation retired"}
    }
    internal fun launch(ticket: ChannelLocationForegroundTicket): ChannelSharingForegroundLaunch {
        ensureWanted();val action=launcher.getAndUpdate {null}
        val result=try {action?.invoke(ticket) ?: ChannelSharingForegroundLaunch.REJECTED}
            catch(_: Exception) {ChannelSharingForegroundLaunch.REJECTED}
        mutableState.update {it.copy(launchResult=result)};return result
    }
    fun requestHide() {
        launcher.value=null
        if(!mutableState.value.wanted) return
        mutableState.update {it.copy(wanted=false,active=false)}
        mutableCapture.value?.requestHide()
        if(lifetime.isActive) scope.launch {cleanupOwned()}
    }
    /** Native and child cleanup only; originalRevoke separately carries remote business result. */
    suspend fun close(): Boolean = withContext(NonCancellable+queued) {requestHide();cleanupOwned()}
    /** Cancellable waiter only: original preparation/native/negative producers remain independently owned. */
    internal suspend fun waitLocalCleanup():Boolean=withContext(queued) {
        requestHide()
        if(mutableState.value.preparing) result.await()
        currentCoroutineContext().ensureActive()
        mutableCapture.value?.let {capture ->
            capture.locationForegroundState.first {it.nativeInFlight==0 || it.cleanupFailed}
        }
        currentCoroutineContext().ensureActive()
        close()
        val child=control
        if(mutableState.value.childCleanupPending && child!=null && !child.closed.value) child.closed.first {it}
        currentCoroutineContext().ensureActive()
        close()
    }
    /** Retry durable original negatives after owned control closes; never restores capture or voice. */
    internal suspend fun recoverOriginalCleanup():Boolean=withContext(queued) {
        val state=mutableState.value
        if(state.wanted || state.preparing || state.nativeCleanupPending || state.childCleanupPending) return@withContext false
        recover().also {if(it) mutableState.update {state -> state.copy(originalCleanupPending=false)}}
    }
    private suspend fun cleanupOwned(): Boolean = cleanup.withLock {
        val nativeClean=mutableCapture.value?.close() ?: true
        mutableRevoke.value=mutableCapture.value?.originalRevoke?.value
        val childClean=control?.close() ?: true
        mutableState.update {it.copy(nativeCleanupPending=!nativeClean,childCleanupPending=!childClean)}
        val child=control
        if(!childClean && child!=null && !child.closed.value && childDrain==null && lifetime.isActive) {
            childDrain=scope.launch {child.closed.first {it};cleanupOwned()}
        }
        val clean=nativeClean && childClean && !mutableState.value.preparing
        if(clean && !mutableState.value.wanted) {
            accountGuardian?.cancel();captureObserver?.cancel();rootGuardian?.cancel();lifetime.complete()
        }
        clean
    }
}
internal class ChannelSharingStartup(private val client: HttpClient,private val baseUrl: String,
    private val queued: CoroutineDispatcher,private val repository: RideLocationRepository,
    private val demand: NearbyLocationDemandCoordinator,private val foreground: ChannelLocationForegroundPort,
    private val clock: ()->Long,private val epoch: ()->Long,private val telemetry: ()->EarnedTelemetry,
    private val journal: ChannelSharingRevokeJournal,private val nextRequestId: ()->String,
    private val acquire: suspend (SocialAuthenticatedAccount)->VoiceAccountRuntimeBinding) {
    private val base=nearbyHttpsApiBaseUrl(baseUrl)
    private val recovery=ChannelSharingRevokeRecovery(client,base,queued,clock,journal)
    private val admission=Mutex()
    private data class Preparation(val actor: String,val tag: ChannelSharingTag,val handle: ChannelSharingStartHandle)
    private val preparations=mutableListOf<Preparation>()
    private val issued=mutableSetOf<String>()
    private var rootJob: Job?=null
    suspend fun start(account: SocialAuthenticatedAccount,original: VoiceSessionTag,generation: String,
        mode: ChannelSharingMode,ttl: ChannelSharingTtl,
        launch: (ChannelLocationForegroundTicket)->ChannelSharingForegroundLaunch): ChannelSharingStartHandle = withContext(queued) {
        currentCoroutineContext().ensureActive();invitationDraftId(generation);invitationDraftId(account.userId.value)
        invitationDraftId(original.sessionId.value)
        val parent=account.retirementParentScope();val root=checkNotNull(parent.coroutineContext[Job])
        require(root!==account.job && root.isActive && account.job.isActive && queued.isDispatchNeeded(parent.coroutineContext))
        require(demand.owns(repository));require(rootJob==null || rootJob===root)
        require(issued.add(generation)) {"Sharing preparation generation cannot be reused"};rootJob=root
        val tag=ChannelSharingTag(original.sessionId.value,generation)
        val prepared=ChannelSharingStartHandle(account,original,queued,launch,recover={
            admission.withLock {
                val protected=preparations.filter {entry ->
                    entry.actor==account.userId.value && entry.tag!=tag && entry.handle.state.value.let {it.wanted || it.preparing}
                }.map {it.tag}.toSet()
                recovery.drain(account,protected)
            }
        }) {handle ->
            val binding=acquire(account);handle.ensureWanted()
            admission.withLock {
                handle.ensureWanted()
                require(binding.voice.belongsTo(account)) {"Voice binding belongs to another account"}
                check(!binding.sharing.retired.value && binding.voice.canShare(original) && !binding.voice.state.value.sharingEnabled)
                val protected=preparations.filter {entry ->
                    entry.actor==account.userId.value && entry.handle!==handle && entry.handle.state.value.let {it.wanted || it.preparing}
                }.map {it.tag}.toSet()
                if(!recovery.drain(account,protected)) {handle.recoveryPending();return@withLock false}
                handle.ensureWanted()
                check(!binding.sharing.retired.value && binding.voice.canShare(original) && !binding.voice.state.value.sharingEnabled)
                val control=ChannelSharingAccountControl.createDurable(account,binding.voice,original,client,base,queued,clock,journal,nextRequestId)
                handle.own(control);handle.ensureWanted()
                val capture=ChannelSharingCaptureOwner.create(account,binding.voice,original,control,generation,mode,ttl,
                    repository,demand,foreground,queued,clock,epoch,telemetry)
                handle.own(capture);handle.ensureWanted()
                if(handle.launch(capture.foregroundTicket)!=ChannelSharingForegroundLaunch.STARTED) false
                else {handle.ensureWanted();binding.sharing.enable(original,capture).await()}
            }
        }
        // Queued root body starts after registration. Hidden held producers remain protected.
        preparations.removeAll {entry -> entry.handle.state.value.let {!it.wanted && !it.preparing}}
        preparations+=Preparation(account.userId.value,ChannelSharingTag(original.sessionId.value,generation),prepared)
        prepared
    }
}
