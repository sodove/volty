package ru.sodovaya.volty.data.voice

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

internal class NearbyVisibleLaunchBoundary(parent: CoroutineScope,visible: ()->Boolean,
    voice: (VoiceForegroundTicket)->Boolean,
    location: (ChannelLocationForegroundTicket)->ChannelSharingForegroundLaunch) {
    init {require(parent.coroutineContext[Job]?.isActive==true)}
    private data class Callbacks(val visible: ()->Boolean,val voice: (VoiceForegroundTicket)->Boolean,
        val location: (ChannelLocationForegroundTicket)->ChannelSharingForegroundLaunch)
    private val lifetime=SupervisorJob(parent.coroutineContext[Job])
    private val callbacks=MutableStateFlow<Callbacks?>(Callbacks(visible,voice,location))
    init {
        CoroutineScope(parent.coroutineContext+lifetime).launch(start=CoroutineStart.UNDISPATCHED) {
            try {awaitCancellation()} finally {close()}
        }
    }
    val available: Boolean get()=lifetime.isActive &&
        try {callbacks.value?.visible?.invoke()==true} catch(_: Exception) {false}
    fun launchVoice(ticket: VoiceForegroundTicket):Boolean {
        val current=callbacks.value ?: return false
        if(!available) return false
        return try {current.voice(ticket) && available} catch(_: Exception) {false}
    }
    fun launchLocation(ticket: ChannelLocationForegroundTicket):ChannelSharingForegroundLaunch {
        val current=callbacks.value ?: return ChannelSharingForegroundLaunch.REJECTED
        if(!available) return ChannelSharingForegroundLaunch.REJECTED
        return try {current.location(ticket).takeIf {available} ?: ChannelSharingForegroundLaunch.REJECTED}
            catch(_: Exception) {ChannelSharingForegroundLaunch.REJECTED}
    }
    /** Releases Activity references only; never stops a borrowed foreground ticket or conversation. */
    fun close() {callbacks.value=null;lifetime.cancel()}
}
