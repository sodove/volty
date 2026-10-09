package ru.sodovaya.volty.presentation.root.nearby

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import ru.sodovaya.volty.data.social.SocialAuthenticatedAccount
import ru.sodovaya.volty.domain.social.FriendSummary
import ru.sodovaya.volty.domain.nearby.ChannelSharingMode
import ru.sodovaya.volty.domain.nearby.ChannelSharingTtl

data class NearbyRootView(val voice: NearbyVoiceView = NearbyVoiceView(), val sharing: NearbySharingView = NearbySharingView(),
    val incoming: NearbyIncomingCallView? = null)
interface NearbyRootPresentation {
    val state: StateFlow<NearbyRootView>
    val actions: NearbyConversationPanelActions
    val incomingActions: NearbyIncomingCallActions
}
internal fun interface NearbyOperationIds { fun next(): String }

internal class NearbyRootPresenter(parent: CoroutineScope, private val voice: NearbyVoiceController,
    private val sharing: NearbySharingController, private val incoming: NearbyIncomingCallUi? = null): NearbyRootPresentation {
    init { require(parent.coroutineContext[Job]?.isActive == true) }
    private val lifetime = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + lifetime)
    private var closed = false
    private val output = MutableStateFlow(NearbyRootView())
    override val state = output.asStateFlow()
    private fun enabled() = !closed && lifetime.isActive
    override val incomingActions = NearbyIncomingCallActions(
        accept={original,label -> if(enabled()) scope.launch {
            try {incoming?.accept(original,label)} catch(cancelled:CancellationException) {throw cancelled} catch(_:Exception) {}
        }},
        decline={original -> if(enabled()) scope.launch {
            try {incoming?.decline(original)} catch(cancelled:CancellationException) {throw cancelled} catch(_:Exception) {}
        }},
    )
    override val actions = NearbyConversationPanelActions(
        disconnect = { if (enabled()) voice.disconnect(it) },
        mute = { operation, value -> if (enabled()) voice.setMuted(operation,value) },
        deafen = { operation, value -> if (enabled()) voice.setDeafened(operation,value) },
        configureAudio = { original, preferences -> if(enabled()) voice.configureAudio(original,preferences) },
        startTrip = { operation, ttl -> if (enabled()) sharing.request(operation,ChannelSharingMode.LOCATION,ttl) },
        hideTrip = { if (enabled()) sharing.hide(it.operation,it.generation) },
        retryTripCleanup = { if (enabled()) sharing.retryCleanup(it.operation,it.generation) },
        confirmSwitch = { if (enabled()) voice.confirmSwitch(it) },
        cancelVoiceRequest = { if (enabled()) voice.cancelRequest(it) },
        cancelSharingRequest = { if (enabled()) sharing.cancelRequest(it) },
    )
    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {try {awaitCancellation()} finally {close()}}
        scope.launch {combine(voice.state,sharing.state,incoming?.state ?: flowOf(null),::NearbyRootView)
            .collect {if (enabled()) output.value=it}}
    }
    fun connectChannel(account: SocialAuthenticatedAccount, channel: String, label: String) {
        if (enabled()) voice.requestChannel(account,channel,label)
    }
    fun callFriend(account: SocialAuthenticatedAccount, friend: FriendSummary) {
        if (enabled()) voice.requestFriendCall(account,friend)
    }
    /** Root disposes presentation only. Process conversation/authentication retain their owner. */
    fun close() {
        if (closed) return
        closed=true;output.value=NearbyRootView();lifetime.cancel()
    }
}
