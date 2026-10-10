package ru.sodovaya.volty.data.voice

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import ru.sodovaya.volty.data.social.SocialAuthenticatedAccount
import ru.sodovaya.volty.domain.nearby.*
import ru.sodovaya.volty.domain.voice.VoiceSessionTag

internal data class ChannelSharingAudienceView(val observed: Boolean = false, val peers: List<ObservedChannelSharingPeer> = emptyList())

/** Own root reader/timer only. Borrowed control retains publication and original negatives. */
internal class ChannelSharingAudienceOwner private constructor(private val account: SocialAuthenticatedAccount,
    private val runtime: AuthenticatedVoiceRuntime,private val original: VoiceSessionTag,
    private val control: ChannelSharingAccountControl,private val channel: String,
    private val queued: CoroutineDispatcher,private val clock: ()->Long) {
    private val mutableState=MutableStateFlow(ChannelSharingAudienceView())
    val state=mutableState.asStateFlow()
    private val mutableRetired=MutableStateFlow(false)
    val retired=mutableRetired.asStateFlow()
    private val lifetime=SupervisorJob(checkNotNull(account.retirementParentScope().coroutineContext[Job]))
    private val scope=CoroutineScope(account.retirementParentScope().coroutineContext+lifetime+queued)
    private var pending: Deferred<Boolean>?=null
    private var lastClock: Long?=null
    private var ordinal=-1L
    private var serverEpoch=-1L
    // Retain the original packet deadline after its visible entry expires.
    private val remembered=linkedMapOf<String,ObservedChannelSharingPeer>()
    private var accountGuardian: Job?=null
    init {
        accountGuardian=account.scope().launch(start=CoroutineStart.UNDISPATCHED) {
            try {awaitCancellation()} finally {withContext(NonCancellable+queued) {retire()}}
        }
        scope.launch(start=CoroutineStart.UNDISPATCHED) {
            try {awaitCancellation()} finally {withContext(NonCancellable+queued) {retire()}}
        }
        scope.launch {
            combine(runtime.state,runtime.retired) {_,_ -> Unit}.collect {tick()}
        }
        scope.launch {
            try {while(isActive) {tick();delay(500)}}
            finally {withContext(NonCancellable+queued) {retire()}}
        }
    }
    suspend fun refresh(): Deferred<Boolean> = withContext(queued) {
        currentCoroutineContext().ensureActive();tick()
        if(mutableRetired.value) return@withContext CompletableDeferred(false)
        pending?.takeIf {!it.isCompleted}?.let {return@withContext it}
        scope.async {
            val reply=control.audience();tick()
            if(mutableRetired.value) false
            else when(reply) {
                is ChannelSharingPacketReply.Audience -> apply(reply,checkNotNull(lastClock))
                is ChannelSharingPacketReply.Failed -> {
                    if(reply.reason in setOf(ChannelSharingFailure.ACCESS_DENIED,ChannelSharingFailure.UNAUTHORIZED,ChannelSharingFailure.RETIRED)) retire()
                    false
                }
                else -> false
            }
        }.also {pending=it}
    }
    suspend fun tick() = withContext(queued) {
        if(mutableRetired.value) return@withContext
        val now=try {clock()} catch(_: Exception) {retire();return@withContext}
        if(now<0 || lastClock?.let {now<it}==true || !account.job.isActive || !control.canReadAudience()) {
            retire();return@withContext
        }
        lastClock=now
        mutableState.value=mutableState.value.copy(peers=mutableState.value.peers.mapNotNull {
            if (control.canObserve(it)) channelSharingPeerAtTime(it,now) else null
        })
    }
    suspend fun close() = withContext(NonCancellable+queued) {retire()}
    private fun apply(reply: ChannelSharingPacketReply.Audience,now: Long): Boolean {
        if(reply.channelId!=channel || reply.requestOrdinal<=ordinal || reply.requestStartedMillis<0 ||
            reply.responseReceivedMillis<reply.requestStartedMillis || now<reply.responseReceivedMillis) return false
        val next=reply.peers.mapNotNull {peer ->
            if(peer.deadlineMillis<=now || !control.canObserve(peer)) return@mapNotNull null
            val old=remembered[peer.actorId]?.takeIf {it.tag==peer.tag}
            val safe=if(old==null) peer
            else when {
                peer.packet.sequence<old.packet.sequence || peer.packet.capturedAtEpochMillis<old.packet.capturedAtEpochMillis -> null
                peer.packet.sequence==old.packet.sequence && peer.packet!=old.packet &&
                    !(peer.stale && peer.packet.copy(telemetry=old.packet.telemetry)==old.packet) -> null
                peer.packet.sequence==old.packet.sequence && peer.sampleDeadlineMillis != null && old.sampleDeadlineMillis != null &&
                    reply.serverTimeEpochMillis>serverEpoch -> {
                    // Current server consent/lease can renew authority, never the original capture bound.
                    val bound=minOf(peer.sampleDeadlineMillis,old.sampleDeadlineMillis)
                    peer.copy(deadlineMillis=minOf(peer.deadlineMillis,bound),sampleDeadlineMillis=bound,
                        freshUntilMillis=listOfNotNull(peer.freshUntilMillis,old.freshUntilMillis).minOrNull())
                }
                peer.packet.sequence==old.packet.sequence -> peer.copy(deadlineMillis=minOf(peer.deadlineMillis,old.deadlineMillis))
                else -> peer
            }
            if(safe!=null) {
                remembered.remove(peer.actorId);remembered[peer.actorId]=safe
                while(remembered.size>512) remembered.remove(remembered.keys.first())
            }
            safe?.let {channelSharingPeerAtTime(it,now)}
        }
        ordinal=reply.requestOrdinal;serverEpoch=maxOf(serverEpoch,reply.serverTimeEpochMillis)
        mutableState.value=ChannelSharingAudienceView(true,next);return true
    }
    private fun retire() {
        if(!mutableRetired.compareAndSet(false,true)) return
        mutableState.value=ChannelSharingAudienceView();remembered.clear();accountGuardian?.cancel();lifetime.cancel()
    }
    companion object {
        suspend fun create(account: SocialAuthenticatedAccount,runtime: AuthenticatedVoiceRuntime,original: VoiceSessionTag,
            control: ChannelSharingAccountControl,queued: CoroutineDispatcher,clock: ()->Long): ChannelSharingAudienceOwner = withContext(queued) {
            currentCoroutineContext().ensureActive()
            require(control.belongsTo(account,runtime,original)) {"Sharing control belongs to another account/session"}
            check(account.job.isActive && control.canReadAudience()) {"Original channel session retired"}
            val channel=checkNotNull(runtime.sharingChannel(original))
            val parent=account.retirementParentScope();val root=checkNotNull(parent.coroutineContext[Job])
            require(root!==account.job && root.isActive && queued.isDispatchNeeded(parent.coroutineContext))
            ChannelSharingAudienceOwner(account,runtime,original,control,channel,queued,clock)
        }
    }
}
