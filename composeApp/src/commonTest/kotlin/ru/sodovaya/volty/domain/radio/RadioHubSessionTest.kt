package ru.sodovaya.volty.domain.radio

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RadioHubSessionTest {
    private fun addr(n: Int) = RadioAddress("192.168.49.$n", 5000)
    private fun hello(n: Int, user: String = "u$n", at: Long = 0) =
        RadioDatagram(addr(n), RadioMessage.Hello(user, "Name$n", at, 1))
    private fun tone(v: Int) = ShortArray(RADIO_FRAME_SAMPLES) { v.toShort() }
    private fun audio(n: Int, slot: Int, seq: Long, pcm: ShortArray) =
        RadioDatagram(addr(n), RadioMessage.Audio(slot, seq, pcm))

    private fun RadioHubSession.joinAt(n: Int, now: Long = 0) = receive(hello(n), now)

    private fun List<RadioDatagram>.audioTo(n: Int) =
        filter { it.peer == addr(n) }.map { it.message }.filterIsInstance<RadioMessage.Audio>()

    private fun List<RadioDatagram>.rostersTo(n: Int) =
        filter { it.peer == addr(n) }.map { it.message }.filterIsInstance<RadioMessage.Roster>()

    @Test
    fun firstHelloGetsSlotOne() {
        val hub = RadioHubSession("hub", "Hub")
        val out = hub.joinAt(1)
        assertEquals(listOf(1), hub.members.map { it.slot })
        val roster = out.rostersTo(1).single()
        assertEquals(listOf(0, 1), roster.entries.map { it.slot })
        assertEquals("hub", roster.entries[0].userId)
    }

    @Test
    fun tenthClientGetsFull() {
        val hub = RadioHubSession("hub", "Hub")
        for (n in 1..9) hub.joinAt(n)
        val out = hub.joinAt(10)
        assertEquals(listOf(RadioDatagram(addr(10), RadioMessage.Full)), out)
        assertEquals(9, hub.members.size)
    }

    @Test
    fun sameUserNewAddressReplacesSlot() {
        val hub = RadioHubSession("hub", "Hub")
        hub.receive(hello(1, user = "bob"), 0)
        hub.receive(hello(2, user = "carol"), 0)
        hub.receive(RadioDatagram(addr(7), RadioMessage.Hello("bob", "Bob", 5, 1)), 100)
        assertEquals(listOf(1, 2), hub.members.map { it.slot })
        assertEquals("bob", hub.members.first { it.slot == 1 }.userId)
        // The new address is the one that is served; the old one hears nothing.
        for (s in 0L..2L) hub.receive(audio(2, 2, s, tone(100)), 100)
        val out = (0 until 3).flatMap { hub.tick(100, null) }
        assertTrue(out.audioTo(1).isEmpty())
        assertTrue(out.filter { it.peer == addr(7) }.any { it.message is RadioMessage.Audio })
    }

    @Test
    fun clientNeverHearsItself() {
        val hub = RadioHubSession("hub", "Hub")
        hub.joinAt(1); hub.joinAt(2)
        val a = tone(1000); val b = tone(2000)
        for (s in 0L..2L) { hub.receive(audio(1, 1, s, a), 0); hub.receive(audio(2, 2, s, b), 0) }
        val out = hub.tick(20, null)
        assertContentEquals(b, out.audioTo(1).single().pcm)
        assertContentEquals(a, out.audioTo(2).single().pcm)
    }

    @Test
    fun hubHearsEveryClient() {
        val hub = RadioHubSession("hub", "Hub")
        hub.joinAt(1); hub.joinAt(2)
        for (s in 0L..2L) { hub.receive(audio(1, 1, s, tone(1000)), 0); hub.receive(audio(2, 2, s, tone(2000)), 0) }
        hub.tick(20, tone(500))
        assertContentEquals(RadioMixer.mix(listOf(tone(1000), tone(2000))), hub.hubPlayback())
    }

    @Test
    fun hubFrameReachesClientsButNotHubPlayback() {
        val hub = RadioHubSession("hub", "Hub")
        hub.joinAt(1)
        val out = hub.tick(20, tone(500))
        assertContentEquals(tone(500), out.audioTo(1).single().pcm)
        assertEquals(0, out.audioTo(1).single().slot)
        assertNull(hub.hubPlayback())
    }

    @Test
    fun silenceSendsNoAudio() {
        val hub = RadioHubSession("hub", "Hub")
        hub.joinAt(1)
        assertTrue(hub.tick(20, null).audioTo(1).isEmpty())
    }

    @Test
    fun audioSeqIsPerRecipientAndCountsOnlySentMixes() {
        val hub = RadioHubSession("hub", "Hub")
        hub.joinAt(1); hub.joinAt(2)
        assertEquals(0L, hub.tick(20, tone(1)).audioTo(1).single().seq)
        assertTrue(hub.tick(40, null).audioTo(1).isEmpty())
        assertEquals(1L, hub.tick(60, tone(1)).audioTo(1).single().seq)
        assertEquals(2L, hub.tick(80, tone(1)).audioTo(1).single().seq)
        // Client 2 received the same three mixes, so its counter is independent and also at 3.
        assertEquals(3L, hub.tick(100, tone(1)).audioTo(2).single().seq)
    }

    @Test
    fun rosterEverySecondEchoesSentAt() {
        val hub = RadioHubSession("hub", "Hub")
        hub.receive(RadioDatagram(addr(1), RadioMessage.Hello("u1", "N", 777, 1)), 0)
        hub.receive(RadioDatagram(addr(1), RadioMessage.Hello("u1", "N", 888, 1)), 10)
        for (t in 1..49) assertTrue(hub.tick(t * 20L, null).rostersTo(1).isEmpty(), "tick $t")
        val rosters = hub.tick(1_000, null).rostersTo(1)
        assertEquals(1, rosters.size)
        assertEquals(888L, rosters.single().echoMillis)
        assertEquals(listOf(0, 1), rosters.single().entries.map { it.slot })
        for (t in 51..99) assertTrue(hub.tick(1_000 + (t - 50) * 20L, null).rostersTo(1).isEmpty())
        assertEquals(1, hub.tick(2_000, null).rostersTo(1).size)
    }

    @Test
    fun rosterCarriesModeAndSetModeApplies() {
        val hub = RadioHubSession("hub", "Hub", mode = RadioMode.LOW_LATENCY)
        assertEquals(RadioMode.LOW_LATENCY.wire, hub.joinAt(1).rostersTo(1).single().modeWire)
        hub.setMode(RadioMode.RELIABLE)
        // RELIABLE needs 6 buffered frames before a client's audio plays; LOW_LATENCY needed 2.
        for (s in 0L..2L) hub.receive(audio(1, 1, s, tone(9)), 0)
        hub.tick(20, null)
        assertNull(hub.hubPlayback())
        for (s in 3L..5L) hub.receive(audio(1, 1, s, tone(9)), 0)
        hub.tick(40, null)
        assertNotNull(hub.hubPlayback())
        var last: RadioMessage.Roster? = null
        for (t in 3..50) hub.tick(t * 20L, null).rostersTo(1).firstOrNull()?.let { last = it }
        assertEquals(RadioMode.RELIABLE.wire, last!!.modeWire)
    }

    @Test
    fun hubEvictsSilentClient() {
        val hub = RadioHubSession("hub", "Hub")
        hub.joinAt(1, now = 0)
        hub.tick(3_000, null)
        assertEquals(1, hub.members.size)
        hub.tick(3_001, null)
        assertTrue(hub.members.isEmpty())
        hub.joinAt(5, now = 3_002)
        assertEquals(1, hub.members.single().slot)
    }

    @Test
    fun audioKeepsClientAlive() {
        val hub = RadioHubSession("hub", "Hub")
        hub.joinAt(1, now = 0)
        hub.receive(audio(1, 1, 0, tone(1)), 2_500)
        hub.tick(5_000, null)
        assertEquals(1, hub.members.size)
        hub.tick(5_501, null)
        assertTrue(hub.members.isEmpty())
    }

    @Test
    fun byeFreesSlotImmediately() {
        val hub = RadioHubSession("hub", "Hub")
        hub.joinAt(1); hub.joinAt(2)
        hub.receive(RadioDatagram(addr(1), RadioMessage.Bye(1)), 5)
        assertEquals(listOf(2), hub.members.map { it.slot })
        hub.joinAt(3, now = 6)
        assertEquals(1, hub.members.first { it.userId == "u3" }.slot)
    }

    @Test
    fun byeFromWrongAddressOrSlotIsIgnored() {
        val hub = RadioHubSession("hub", "Hub")
        hub.joinAt(1); hub.joinAt(2)
        hub.receive(RadioDatagram(addr(2), RadioMessage.Bye(1)), 5)
        hub.receive(RadioDatagram(addr(9), RadioMessage.Bye(2)), 5)
        assertEquals(2, hub.members.size)
    }

    @Test
    fun audioFromUnknownAddressIsIgnored() {
        val hub = RadioHubSession("hub", "Hub")
        for (s in 0L..2L) hub.receive(audio(4, 1, s, tone(1000)), 0)
        hub.tick(20, null)
        assertNull(hub.hubPlayback())
        assertTrue(hub.members.isEmpty())
    }

    @Test
    fun rejoinAfterEvictionContinuesSeq() {
        val hub = RadioHubSession("hub", "Hub")
        hub.joinAt(1, now = 0)
        hub.tick(20, tone(1)); val n = hub.tick(40, tone(1)).audioTo(1).single().seq
        hub.tick(4_000, null)
        assertTrue(hub.members.isEmpty())
        hub.joinAt(1, now = 4_010)
        assertTrue(hub.tick(4_020, tone(1)).audioTo(1).single().seq > n)
    }

    @Test
    fun newStreamIdResetsClientBuffer() {
        val hub = RadioHubSession("hub", "Hub")
        hub.joinAt(1)
        for (s in 0L..10L) hub.receive(audio(1, 1, s, tone(700)), 0)
        for (t in 1..6) hub.tick(t * 20L, null)
        hub.receive(RadioDatagram(addr(1), RadioMessage.Hello("u1", "Name1", 50, 2)), 200)
        for (s in 0L..2L) hub.receive(audio(1, 1, s, tone(900)), 200)
        hub.tick(220, null)
        assertContentEquals(tone(900), hub.hubPlayback())
    }

    @Test
    fun rosterCarriesHubStreamId() {
        val hub = RadioHubSession("hub", "Hub", hubStreamId = 4242)
        assertEquals(4242, hub.joinAt(1).rostersTo(1).single().hubStreamId)
        var seen = false
        for (t in 1..50) hub.tick(t * 20L, null).rostersTo(1).forEach { seen = true; assertEquals(4242, it.hubStreamId) }
        assertTrue(seen)
    }
}
