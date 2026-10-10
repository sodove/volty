package ru.sodovaya.volty.data.voice

import ru.sodovaya.volty.domain.nearby.*
import kotlin.test.*

class ChannelSharingPeerTimeTest {
    private val peer = ObservedChannelSharingPeer("actor", ChannelSharingTag("10000000-0000-0000-0000-000000000001",
        "20000000-0000-0000-0000-000000000002"), ChannelSharingMode.LOCATION, ChannelSharingPacket(0,0,null), 1000,
        freshUntilMillis = 100)
    @Test fun freshnessExpiryMarksLastKnownRatherThanHidingIt() {
        assertFalse(channelSharingPeerAtTime(peer,99)!!.stale)
        val stale = assertNotNull(channelSharingPeerAtTime(peer,100))
        assertTrue(stale.stale); assertEquals(peer.packet,stale.packet);assertEquals(1000,stale.deadlineMillis)
    }
    @Test fun displayAuthorityDeadlineStillRemovesLastKnownPoint() {
        assertNotNull(channelSharingPeerAtTime(peer,999))
        assertNull(channelSharingPeerAtTime(peer,1000))
    }
}
