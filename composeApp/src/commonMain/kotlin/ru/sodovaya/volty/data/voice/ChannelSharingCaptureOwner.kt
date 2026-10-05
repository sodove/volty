package ru.sodovaya.volty.data.voice

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.sodovaya.volty.data.social.SocialAuthenticatedAccount
import ru.sodovaya.volty.domain.location.*
import ru.sodovaya.volty.domain.nearby.*
import ru.sodovaya.volty.domain.nearby.community.invitationDraftId
import ru.sodovaya.volty.domain.social.*
import ru.sodovaya.volty.domain.voice.VoiceSessionTag

/** Unique generation's capture only; borrowed sharing control owns original remote negatives. */
internal class ChannelSharingCaptureOwner private constructor(private val account: SocialAuthenticatedAccount,
    private val control: ChannelSharingAccountControl,private val generation: String,private val mode: ChannelSharingMode,
    private val ttl: ChannelSharingTtl,private val repository: RideLocationRepository,
    private val demand: NearbyLocationDemandCoordinator,private val foreground: ChannelLocationForegroundPort,
    private val queued: CoroutineDispatcher,private val clock: ()->Long,private val epoch: ()->Long,
    private val telemetry: ()->EarnedTelemetry) : ChannelLocationForegroundOwner {
    private val mutableResources=MutableStateFlow(ChannelLocationForegroundState(wanted=true))
    override val locationForegroundState=mutableResources.asStateFlow()
    private val mutablePacket=MutableStateFlow<ChannelSharingPacket?>(null)
    val packet=mutablePacket.asStateFlow()
    lateinit var foregroundTicket: ChannelLocationForegroundTicket
        private set
    private val nativeTicket=demand.newTicket()
    private val lifetime=SupervisorJob(checkNotNull(account.retirementParentScope().coroutineContext[Job]))
    private val scope=CoroutineScope(account.retirementParentScope().coroutineContext+lifetime+queued)
    private val positives=SupervisorJob(lifetime)
    private val positiveScope=CoroutineScope(scope.coroutineContext+positives)
    private val cleanupMutex=Mutex()
    private var started: Deferred<Boolean>?=null
    private var foregroundGranted=false
    private var consentGranted=false
    private var fixFloor=0L
    private var epochFloor=0L
    private var lastFix=-1L
    private var nextSequence=0L
    private var lastClock: Long?=null
    private var accountGuardian: Job?=null
    private val mutableRevoke=MutableStateFlow<Deferred<ChannelSharingReply>?>(null)
    val originalRevoke=mutableRevoke.asStateFlow()
    init {
        accountGuardian=account.scope().launch(start=CoroutineStart.UNDISPATCHED) {
            try {awaitCancellation()} finally {requestHide()}
        }
        scope.launch(start=CoroutineStart.UNDISPATCHED) {
            try {awaitCancellation()} finally {withContext(NonCancellable+queued) {fence();releaseNative()}}
        }
        positiveScope.launch {while(isActive) {tick();delay(500)}}
    }
    suspend fun begin(): Deferred<Boolean> = withContext(queued) {
        currentCoroutineContext().ensureActive()
        if(!mutableResources.value.wanted) return@withContext CompletableDeferred(false)
        started?.let {return@withContext it}
        positiveScope.async {
            var installed=false
            try {
                ensureSession()
                if(!foreground.awaitReady(foregroundTicket)) return@async false
                foregroundGranted=true;ensureSession()
                val consent=control.start(generation,mode,ttl)
                if(consent !is ChannelSharingReply.Active) return@async false
                consentGranted=true;ensureSession()
                fixFloor=clock();epochFloor=epoch();require(fixFloor>=0 && epochFloor>=0)
                mutableResources.update {it.copy(nativeOwned=true,nativeInFlight=it.nativeInFlight+1)}
                try {if(!demand.acquire(nativeTicket)) return@async false}
                finally {withContext(NonCancellable+queued) {
                    mutableResources.update {it.copy(nativeInFlight=it.nativeInFlight-1)};finishIfReleased()
                }}
                ensureSession()
                positiveScope.launch {
                    try {repository.state.collect {state ->
                        ensureSession()
                        when(val status=state.status) {
                            is RideLocationStatus.Available -> if(LocationConsumer.NEARBY_CHANNEL_SHARING in state.demands) emitFix(status.fix)
                            RideLocationStatus.PermissionRequired,RideLocationStatus.PermissionDenied,RideLocationStatus.ProviderDisabled -> requestHide()
                            else -> Unit
                        }
                    }} catch(cancelled: CancellationException) {throw cancelled}
                      catch(_: Exception) {requestHide()}
                }
                installed=true;true
            } finally {if(!installed) requestHide()}
        }.also {started=it}
    }
    override fun requestHide() {
        if(!mutableResources.value.wanted) return
        fence()
        if(lifetime.isActive) scope.launch {releaseNative()}
        else if(mutableResources.value.nativeOwned) mutableResources.update {it.copy(cleanupFailed=true)}
    }
    /** Native release only. originalRevoke separately exposes the original remote acknowledgement. */
    suspend fun close(): Boolean = withContext(NonCancellable+queued) {
        fence();val released=releaseNative()
        if(released) {accountGuardian?.cancel();lifetime.cancel()}
        released
    }
    private fun fence() {
        mutableResources.update {it.copy(wanted=false,retired=true)}
        mutablePacket.value=null;positives.cancel()
    }
    private suspend fun releaseNative(): Boolean = cleanupMutex.withLock {
        if(this::foregroundTicket.isInitialized) foreground.stop(foregroundTicket)
        if(mutableRevoke.value==null) mutableRevoke.value=control.requestRevoke(generation)
        val released=demand.release(nativeTicket)
        mutableResources.update {it.copy(nativeOwned=if(released) false else it.nativeOwned,cleanupFailed=!released)}
        finishIfReleased()
        released && mutableResources.value.nativeInFlight==0
    }
    suspend fun tick() = withContext(queued) {
        if(!mutableResources.value.wanted) return@withContext
        try {
            ensureSession()
            mutablePacket.value?.let {
                val at=epoch()
                if(clock()-lastFix>=CHANNEL_SHARING_SAMPLE_MILLIS || at<it.capturedAtEpochMillis ||
                    at-it.capturedAtEpochMillis>=CHANNEL_SHARING_SAMPLE_MILLIS) mutablePacket.value=null
            }
        } catch(_: CancellationException) {requestHide()}
            catch(_: Exception) {requestHide()}
    }
    private fun finishIfReleased() {
        val state=mutableResources.value
        if(state.retired && !state.nativeOwned && state.nativeInFlight==0 && !state.cleanupFailed) {
            accountGuardian?.cancel();lifetime.cancel()
        }
    }
    private suspend fun ensureSession() {
        currentCoroutineContext().ensureActive()
        val now=clock()
        if(!mutableResources.value.wanted || !account.job.isActive || !control.canReadAudience() ||
            now<0 || lastClock?.let {now<it}==true || (foregroundGranted && !foreground.allows(this)) ||
            (consentGranted && !control.canCapture(generation))) throw CancellationException("Original location capture retired")
        lastClock=now
    }
    private fun emitFix(fix: RideLocationFix) {
        val elapsed=fix.elapsedRealtimeMillis ?: return
        val now=clock();val at=epoch();val captured=fix.capturedAtEpochMillis
        if(elapsed<fixFloor || elapsed<=lastFix || elapsed>now || now-elapsed>=CHANNEL_SHARING_SAMPLE_MILLIS ||
            captured<epochFloor || captured>at || at-captured>=CHANNEL_SHARING_SAMPLE_MILLIS ||
            captured>Long.MAX_VALUE-CHANNEL_SHARING_SAMPLE_MILLIS) return
        if(nextSequence==Long.MAX_VALUE) {requestHide();return}
        val location=LocationSnapshot(fix.coordinate.latitude,fix.coordinate.longitude,fix.accuracyMeters,captured,captured+CHANNEL_SHARING_SAMPLE_MILLIS)
        val shared=if(mode==ChannelSharingMode.LOCATION) null else TelemetryShareMapper.map(TelemetryShareProfile.valueOf(mode.name),telemetry())
        val packet=ChannelSharingPacket(nextSequence,captured,location,shared).sanitized(mode)
        lastFix=elapsed;nextSequence++;mutablePacket.value=packet
    }
    companion object {
        suspend fun create(account: SocialAuthenticatedAccount,runtime: AuthenticatedVoiceRuntime,original: VoiceSessionTag,
            control: ChannelSharingAccountControl,generation: String,mode: ChannelSharingMode,ttl: ChannelSharingTtl,
            repository: RideLocationRepository,demand: NearbyLocationDemandCoordinator,foreground: ChannelLocationForegroundPort,
            queued: CoroutineDispatcher,clock: ()->Long,epoch: ()->Long,telemetry: ()->EarnedTelemetry): ChannelSharingCaptureOwner =
            withContext(queued) {
                currentCoroutineContext().ensureActive();invitationDraftId(generation)
                require(control.belongsTo(account,runtime,original)) {"Sharing control belongs to another original session"}
                require(demand.owns(repository)) {"Location demand uses another source"}
                check(account.job.isActive && control.canReadAudience()) {"Original channel retired"}
                val parent=account.retirementParentScope();val root=checkNotNull(parent.coroutineContext[Job])
                require(root!==account.job && root.isActive && queued.isDispatchNeeded(parent.coroutineContext))
                check(control.reserveCaptureGeneration(generation)) {"Sharing generation already captured or withdrawn"}
                val owner=ChannelSharingCaptureOwner(account,control,generation,mode,ttl,repository,demand,foreground,queued,clock,epoch,telemetry)
                try {
                    owner.foregroundTicket=foreground.request(owner)
                    owner.ensureSession();owner
                } catch(failure: Throwable) {owner.close();throw failure}
            }
    }
}
