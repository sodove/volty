package ru.sodovaya.volty.data.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.*

class ChannelLocationForegroundAuthorityTest {
    private fun id(n: Int)="10000000-0000-0000-0000-${n.toString().padStart(12,'0')}"
    private class Owner : ChannelLocationForegroundOwner {
        override val locationForegroundState=MutableStateFlow(ChannelLocationForegroundState(wanted=true))
        var hidden=0
        override fun requestHide() {hidden++;locationForegroundState.value=locationForegroundState.value.copy(wanted=false)}
    }
    @Test fun permissionAndNativeAcknowledgementAreBothRequiredAndLossHidesOnlyOriginalOwner() {
        var permitted=false;var n=0;val authority=ChannelLocationForegroundAuthority({permitted},{id(++n)})
        val owner=Owner();val service=Any();val ticket=authority.request(owner)
        assertFalse(authority.allows(owner));assertFalse(authority.acknowledge(ticket,service))
        permitted=true;assertFalse(authority.allows(owner));assertTrue(authority.acknowledge(ticket,service));assertTrue(authority.allows(owner))
        permitted=false;assertFalse(authority.allows(owner));authority.refreshPermissions()
        assertEquals(1,owner.hidden);assertNull(authority.current.value);assertFalse(authority.destroyed(service))
    }
    @Test fun retiredOwnerCannotReplaceAndOldServiceOrNotificationCannotHideReplacement() {
        var n=0;val authority=ChannelLocationForegroundAuthority({true},{id(++n)})
        val old=Owner();val fresh=Owner();val oldService=Any();val newService=Any()
        val oldTicket=authority.request(old);assertTrue(authority.acknowledge(oldTicket,oldService))
        val retired=Owner().also {it.locationForegroundState.value=ChannelLocationForegroundState(wanted=false,retired=true)}
        assertFailsWith<IllegalStateException> {authority.request(retired)}
        assertTrue(authority.allows(old));assertEquals(0,old.hidden)
        val ticket=authority.request(fresh);assertEquals(1,old.hidden);assertTrue(authority.acknowledge(ticket,newService))
        assertFalse(authority.stop(oldTicket));assertFalse(authority.destroyed(oldService));assertFalse(authority.acknowledge(oldTicket,oldService))
        assertTrue(authority.allows(fresh));assertEquals(0,fresh.hidden)
        assertTrue(authority.stop(ticket));assertEquals(1,fresh.hidden)
    }
    @Test fun clearedIntentCannotReleaseForegroundUntilOriginalNativeCleanupIsEarned() {
        val clear=ChannelLocationForegroundState()
        assertTrue(canReleaseChannelLocationForeground(false,listOf(clear)))
        assertFalse(canReleaseChannelLocationForeground(true,listOf(clear)))
        for(pending in listOf(clear.copy(wanted=true),clear.copy(nativeOwned=true),clear.copy(nativeInFlight=1),clear.copy(cleanupFailed=true))) {
            assertFalse(canReleaseChannelLocationForeground(false,listOf(clear,pending)))
        }
    }
    @Test fun microphoneAcknowledgementNeverAdmitsLocationAndLocationLossLeavesVoiceAuthorityLive() {
        var n=0;var voiceLost=0
        val voice=VoiceForegroundGate({true},{id(++n)})
        val runtime=Any();val voiceTicket=voice.request(runtime) {voiceLost++}
        val service=Any();assertTrue(voice.acknowledge(voiceTicket,service))
        val location=ChannelLocationForegroundAuthority({true},{id(++n)})
        val owner=Owner();val ticket=location.request(owner)
        assertFalse(location.allows(owner));assertFalse(location.acknowledge(voiceTicket,service))
        assertTrue(location.acknowledge(ticket,Any()));assertTrue(location.stop(ticket))
        assertTrue(voice.allows(runtime));assertEquals(0,voiceLost)
        val pending=Owner();val pendingTicket=location.request(pending)
        pending.locationForegroundState.value=pending.locationForegroundState.value.copy(wanted=false)
        assertFalse(location.acknowledge(pendingTicket,Any()));assertFalse(location.allows(pending))
    }
}
