package ru.sodovaya.volty.data.voice

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import ru.sodovaya.volty.data.social.SocialAuthenticatedAccount
import ru.sodovaya.volty.domain.nearby.*

internal data class ChannelSharingPublicationState(val retired: Boolean=false,val inFlight: Int=0,
    val publishedSequence: Long?=null,val failure: ChannelSharingFailure?=null)

/** Root's one producer; StateFlow conflates samples while actual original HTTP is suspended. */
internal class ChannelSharingPacketPublisher private constructor(account: SocialAuthenticatedAccount,
    private val capture: ChannelSharingCaptureOwner,private val queued: CoroutineDispatcher) {
    private val mutableState=MutableStateFlow(ChannelSharingPublicationState())
    val state=mutableState.asStateFlow()
    private val lifetime=SupervisorJob(checkNotNull(account.retirementParentScope().coroutineContext[Job]))
    private val scope=CoroutineScope(account.retirementParentScope().coroutineContext+lifetime+queued)
    private var accountGuardian: Job?=null
    init {
        accountGuardian=account.scope().launch(start=CoroutineStart.UNDISPATCHED) {
            try {awaitCancellation()} finally {retire()}
        }
        scope.launch(start=CoroutineStart.UNDISPATCHED) {
            try {awaitCancellation()} finally {retire()}
        }
        scope.launch {
            capture.locationForegroundState.collect {if(!it.wanted || it.retired) retire()}
        }
        scope.launch {
            try {
                capture.packet.collect {sample ->
                    if(sample==null || mutableState.value.retired) return@collect
                    mutableState.update {it.copy(inFlight=it.inFlight+1)}
                    try {
                        val reply=capture.publishSnapshot(sample)
                        if(!mutableState.value.retired) when(reply) {
                            is ChannelSharingPacketReply.Published -> mutableState.update {it.copy(publishedSequence=reply.sequence,failure=null)}
                            is ChannelSharingPacketReply.Failed -> {
                                mutableState.update {it.copy(failure=reply.reason)}
                                // An earlier revision treated an expired sample ACK as expired consent.
                                // Capture owns the consent deadline; slow sample delivery must not revoke it.
                                if(reply.reason in setOf(ChannelSharingFailure.ACCESS_DENIED,ChannelSharingFailure.UNAUTHORIZED,
                                        ChannelSharingFailure.RETIRED)) {capture.requestHide();retire()}
                            }
                            else -> Unit
                        }
                    } catch(cancelled: CancellationException) {throw cancelled}
                      catch(_: Exception) {if(!mutableState.value.retired) mutableState.update {it.copy(failure=ChannelSharingFailure.UNAVAILABLE)}}
                      finally {mutableState.update {it.copy(inFlight=it.inFlight-1)}}
                }
            } finally {retire()}
        }
    }
    suspend fun close()=withContext(NonCancellable+queued) {retire()}
    private fun retire() {
        mutableState.update {it.copy(retired=true,publishedSequence=null)}
        accountGuardian?.cancel();lifetime.cancel()
    }
    companion object {
        suspend fun create(account: SocialAuthenticatedAccount,capture: ChannelSharingCaptureOwner,queued: CoroutineDispatcher): ChannelSharingPacketPublisher =
            withContext(queued) {
                currentCoroutineContext().ensureActive()
                require(capture.belongsTo(account)) {"Capture belongs to another account"}
                val root=checkNotNull(account.retirementParentScope().coroutineContext[Job])
                require(root!==account.job && root.isActive && account.job.isActive && queued.isDispatchNeeded(account.retirementParentScope().coroutineContext))
                check(capture.reservePublisher()) {"Capture already has a publisher"}
                ChannelSharingPacketPublisher(account,capture,queued)
            }
    }
}
