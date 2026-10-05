package ru.sodovaya.volty.data.voice

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.sodovaya.volty.data.social.SocialAuthenticatedAccount
import ru.sodovaya.volty.domain.voice.VoiceSessionTag

/** Adopts capture/publisher only. Every negative snapshots its original selection synchronously. */
internal class ChannelSharingCoordinator private constructor(private val account: SocialAuthenticatedAccount,
    private val runtime: AuthenticatedVoiceRuntime,private val queued: CoroutineDispatcher) {
    private class Entry(val original: VoiceSessionTag,val capture: ChannelSharingCaptureOwner) {
        val ready=CompletableDeferred<Boolean>()
        var start: Job?=null
        var publisher: ChannelSharingPacketPublisher?=null
    }
    private val lifetime=SupervisorJob(checkNotNull(account.retirementParentScope().coroutineContext[Job]))
    private val scope=CoroutineScope(account.retirementParentScope().coroutineContext+lifetime+queued)
    private val selected=MutableStateFlow<Entry?>(null)
    private val entries=mutableListOf<Entry>()
    private val retired=MutableStateFlow(false)
    private val mutablePublisher=MutableStateFlow<ChannelSharingPacketPublisher?>(null)
    val publisher=mutablePublisher.asStateFlow()
    private val mutableCleanupPending=MutableStateFlow(false)
    val cleanupPending=mutableCleanupPending.asStateFlow()
    private var accountGuardian: Job?=null
    init {
        accountGuardian=account.scope().launch(start=CoroutineStart.UNDISPATCHED) {
            try {awaitCancellation()} finally {withContext(NonCancellable+queued) {fence()}}
        }
        scope.launch(start=CoroutineStart.UNDISPATCHED) {
            try {awaitCancellation()} finally {withContext(NonCancellable+queued) {fence();cleanupOwned()}}
        }
    }
    suspend fun enable(original: VoiceSessionTag,capture: ChannelSharingCaptureOwner): Deferred<Boolean> = withContext(queued) {
        currentCoroutineContext().ensureActive()
        check(!retired.value && account.job.isActive && lifetime.isActive)
        scope.async {
            check(runtime.canShare(original) && !runtime.state.value.sharingEnabled) {"Original channel must be active and not sharing"}
            check(capture.reserveCoordinator(account,runtime,original)) {"Capture already adopted"}
            val entry=Entry(original,capture);entries+=entry
            selected.value?.let {withdraw(it)};selected.value=entry
            scope.launch {
                capture.locationForegroundState.collect {state ->
                    if(!state.wanted || state.retired) {
                        if(selected.value===entry) hideVoice(entry)
                        withdraw(entry)
                        cancel()
                    }
                }
            }
            try {
                if(!runtime.enableSharing()) {withdraw(entry);false} else withTimeout(30_000) {entry.ready.await()}
            } catch(_: TimeoutCancellationException) {hideVoice(entry);withdraw(entry);false
            } catch(cancelled: CancellationException) {
                withdraw(entry);throw cancelled
            } catch(_: Exception) {hideVoice(entry);withdraw(entry);false}
        }
    }
    /** Called directly by the queued voice owner; never look up selection inside a later coroutine. */
    fun dispatch(effect: VoiceSharingDispatch) {
        if(retired.value || !account.job.isActive) return
        val entry=selected.value ?: return
        if(effect.original!=entry.original) return
        when(effect.effect) {
            VoiceSharingEffect.ADD_DEMAND -> {
                if(entry.start!=null) return
                entry.start=scope.launch {
                    try {
                        if(!entry.capture.begin().await() || !current(entry)) {withdraw(entry);return@launch}
                        val sending=ChannelSharingPacketPublisher.create(account,entry.capture,queued)
                        entry.publisher=sending
                        if(!current(entry)) {sending.close();withdraw(entry);return@launch}
                        mutablePublisher.value=sending;entry.ready.complete(true)
                    } catch(_: CancellationException) {
                        if(selected.value===entry) hideVoice(entry)
                        withdraw(entry)
                    } catch(_: Exception) {hideVoice(entry);withdraw(entry)}
                }
            }
            VoiceSharingEffect.REMOVE_DEMAND,VoiceSharingEffect.CLEAR_QUEUE -> withdraw(entry)
        }
    }
    private fun current(entry: Entry)=!retired.value && account.job.isActive && selected.value===entry && entry.capture.locationForegroundState.value.wanted
    private suspend fun hideVoice(entry: Entry) {
        if(selected.value===entry && runtime.canShare(entry.original)) runtime.hidePosition()
    }
    private fun withdraw(entry: Entry) {
        if(selected.compareAndSet(entry,null)) mutablePublisher.value=null
        entry.ready.complete(false);entry.capture.requestHide()
        updateCleanupPending()
        if(lifetime.isActive) scope.launch {entry.publisher?.close();entry.capture.close();updateCleanupPending()}
    }
    private fun updateCleanupPending() {
        mutableCleanupPending.value=entries.any {entry -> entry.capture.locationForegroundState.value.let {
            it.retired && (it.nativeOwned || it.nativeInFlight>0 || it.cleanupFailed)
        }}
    }
    private fun fence() {
        retired.value=true;selected.value=null;mutablePublisher.value=null
        entries.toList().forEach {it.ready.complete(false);it.capture.requestHide()}
        accountGuardian?.cancel();lifetime.cancel()
    }
    /** Native cleanup result only. Remote original revoke is exposed by each capture separately. */
    suspend fun close(): Boolean = withContext(NonCancellable+queued) {
        selected.value?.let {hideVoice(it)}
        fence();cleanupOwned()
    }
    private suspend fun cleanupOwned(): Boolean {
        var clean=true
        for(entry in entries.toList()) {
            entry.publisher?.close()
            if(!entry.capture.close()) clean=false
        }
        mutableCleanupPending.value=!clean;return clean
    }
    companion object {
        suspend fun create(account: SocialAuthenticatedAccount,runtime: AuthenticatedVoiceRuntime,queued: CoroutineDispatcher): ChannelSharingCoordinator = withContext(queued) {
            currentCoroutineContext().ensureActive();require(runtime.belongsTo(account))
            val root=checkNotNull(account.retirementParentScope().coroutineContext[Job])
            require(root!==account.job && root.isActive && account.job.isActive && queued.isDispatchNeeded(account.retirementParentScope().coroutineContext))
            ChannelSharingCoordinator(account,runtime,queued)
        }
    }
}
