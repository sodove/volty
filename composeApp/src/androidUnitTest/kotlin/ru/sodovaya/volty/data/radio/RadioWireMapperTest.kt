package ru.sodovaya.volty.data.radio

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import ru.sodovaya.volty.domain.radio.RadioAddress
import ru.sodovaya.volty.domain.radio.RadioDatagram
import ru.sodovaya.volty.domain.radio.RadioMessage
import ru.sodovaya.volty.domain.radio.RadioRosterEntry

class RadioWireMapperTest {
    private val key = ByteArray(16) { (it + 1).toByte() }
    private val codec = RadioPacketCodec(key)
    private val clients = listOf(
        RadioAddress("192.168.49.2", 47_820),
        RadioAddress("192.168.49.3", 47_820),
        RadioAddress("192.168.49.4", 47_820),
    )

    // Identity frame codec so these tests exercise only the mapping and the counter.
    private fun mapper(session: Int, sender: Int = 0, codec: RadioPacketCodec = this.codec) = RadioWireMapper(
        codec = codec,
        session = session,
        ownSender = { sender },
        clock = { 1234L },
        encodeFrame = { _, pcm -> ByteArray(pcm.size) { pcm[it].toByte() } },
        decodeFrame = { _, _, bytes -> ShortArray(bytes.size) { bytes[it].toShort() } },
    )

    private fun audio(seq: Long) = RadioMessage.Audio(0, seq, ShortArray(4) { (it + 1).toShort() })

    @Test
    fun headerSeqUniqueAcrossRecipientsAndTypes() {
        val hub = mapper(session = 77)
        val roster = RadioMessage.Roster(listOf(RadioRosterEntry(1, "u1", "A")), 2, null, 77)
        val seen = HashSet<Pair<Int, Long>>()
        var total = 0
        // every recipient gets audio with the SAME message seqs 0..9 (per-recipient counters collide)
        for (round in 0 until 10L) {
            for (c in clients) {
                for (m in listOf(audio(round), roster, RadioMessage.Hello("hub", "Hub", 1L, 77))) {
                    val bytes = assertNotNull(hub.encode(RadioDatagram(c, m)))
                    val p = assertNotNull(codec.decode(bytes))
                    assertEquals(77, p.session)
                    seen += p.session to p.seq
                    total++
                }
            }
        }
        assertEquals(total, seen.size, "every packet needs a distinct (session, seq): it is the GCM nonce")
    }

    @Test
    fun audioSeqRoundTripsInsidePayload() {
        val hub = mapper(session = 5)
        val client = mapper(session = 6, sender = 3)
        val peer = clients[0]
        hub.encode(RadioDatagram(peer, audio(1))) // header counter advances independently of message seq
        val bytes = assertNotNull(hub.encode(RadioDatagram(peer, audio(4_000_000_000L))))
        val got = assertNotNull(client.decode(RadioAddress("192.168.49.1", 47_820), bytes))
        val msg = assertIs<RadioMessage.Audio>(got.message)
        assertEquals(4_000_000_000L, msg.seq)
        assertEquals(0, msg.slot)
        assertContentEquals(ShortArray(4) { (it + 1).toShort() }, msg.pcm)
    }

    @Test
    fun helloStreamIdIsHeaderSession() {
        val client = mapper(session = 0x1234_5678, sender = 255)
        val hub = mapper(session = 9)
        val hubAddress = RadioAddress("192.168.49.1", 47_820)
        val bytes = assertNotNull(
            client.encode(RadioDatagram(hubAddress, RadioMessage.Hello("u", "Name", 42L, streamId = 1))),
        )
        val got = assertNotNull(hub.decode(clients[0], bytes))
        val hello = assertIs<RadioMessage.Hello>(got.message)
        assertEquals(0x1234_5678, hello.streamId)
        assertEquals("u", hello.userId)
        assertEquals(42L, hello.sentAtMillis)
        assertEquals(clients[0], got.peer)
    }

    @Test
    fun rosterCarriesHubSessionAndModeAndEcho() {
        val hub = mapper(session = 31)
        val client = mapper(session = 8, sender = 2)
        val roster = RadioMessage.Roster(listOf(RadioRosterEntry(2, "u2", "B")), 3, 99L, 0)
        val bytes = assertNotNull(hub.encode(RadioDatagram(clients[0], roster)))
        val got = assertIs<RadioMessage.Roster>(assertNotNull(client.decode(RadioAddress("h", 1), bytes)).message)
        assertEquals(31, got.hubStreamId)
        assertEquals(3, got.modeWire)
        assertEquals(99L, got.echoMillis)
        assertEquals(listOf(RadioRosterEntry(2, "u2", "B")), got.entries)
    }

    @Test
    fun headerSeqStartsAtARandomPointBelowTwoToThe62() {
        // I7: a nonce space starting at 0 every time makes (session, seq) collisions far likelier.
        val firsts = List(2) {
            val bytes = assertNotNull(mapper(session = 4).encode(RadioDatagram(clients[0], RadioMessage.Full)))
            assertNotNull(codec.decode(bytes)).seq
        }
        assertTrue(firsts[0] != firsts[1], "two mappers started at the same seq ${firsts[0]}")
        firsts.forEach { assertTrue(it in 0 until (1L shl 62), "seq $it out of range") }
    }

    @Test
    fun replayGuardAcceptsALargeFirstSeq() {
        val guard = RadioReplayGuard()
        val big = (1L shl 62) - 1
        assertTrue(guard.accept(1, 9, big))
        assertTrue(guard.accept(1, 9, big + 1))
        assertFalse(guard.accept(1, 9, big), "replay")
        // And end to end through a mapper whose counter starts high.
        val hub = mapper(session = 3)
        val client = mapper(session = 5, sender = 1)
        repeat(3) { assertNotNull(client.decode(clients[0], assertNotNull(hub.encode(RadioDatagram(clients[0], RadioMessage.Full))))) }
    }

    @Test
    fun replayedAndForeignPacketsAreDropped() {
        val hub = mapper(session = 1)
        val client = mapper(session = 2, sender = 1)
        val bytes = assertNotNull(hub.encode(RadioDatagram(clients[0], RadioMessage.Full)))
        assertNotNull(client.decode(clients[0], bytes))
        assertNull(client.decode(clients[0], bytes), "replay")
        val other = mapper(session = 1, codec = RadioPacketCodec(ByteArray(16) { 9 }))
        val foreign = assertNotNull(other.encode(RadioDatagram(clients[0], RadioMessage.Full)))
        assertNull(client.decode(clients[0], foreign), "wrong key")
    }

    @Test
    fun encodeFailureIsDroppedNotThrown() {
        val hub = mapper(session = 1)
        val tooLong = RadioMessage.Roster(List(300) { RadioRosterEntry(1, "u", "n") }, 2, null, 1)
        assertNull(hub.encode(RadioDatagram(clients[0], tooLong)))
        val badMode = RadioMessage.Roster(emptyList(), 999, null, 1)
        assertNull(hub.encode(RadioDatagram(clients[0], badMode)))
        assertEquals(2, hub.droppedEncodes)
        assertNotNull(hub.encode(RadioDatagram(clients[0], RadioMessage.Full)))
    }
}
