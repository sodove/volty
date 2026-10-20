package ru.sodovaya.volty.data.radio

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RadioPacketTest {
    private val key = ByteArray(16) { (it + 1).toByte() }
    private val codec = RadioPacketCodec(key)

    private fun packet(type: RadioPacketType, payload: ByteArray = byteArrayOf(1, 2, 3)) =
        RadioPacket(type, sender = 3, session = 0x1234_5678, seq = 0x0102_0304_0506L, payload = payload)

    @Test
    fun roundTripsEveryType() {
        for (type in RadioPacketType.values()) {
            val p = packet(type)
            val back = assertNotNull(codec.decode(codec.encode(p)), "type $type")
            assertEquals(p.type, back.type)
            assertEquals(p.sender, back.sender)
            assertEquals(p.session, back.session)
            assertEquals(p.seq, back.seq)
            assertContentEquals(p.payload, back.payload)
        }
        val unassigned = RadioPacket(RadioPacketType.HELLO, 255, -1, 7L, ByteArray(0))
        assertEquals(255, codec.decode(codec.encode(unassigned))!!.sender)
        assertEquals(-1, codec.decode(codec.encode(unassigned))!!.session)
    }

    @Test
    fun payloadHelpersRoundTrip() {
        val hello = RadioHello("u-1", "Привет Вася", 123456789L, 42L)
        assertEquals(hello, RadioHello.decode(hello.encode()))
        val helloNoEcho = RadioHello("u-1", "Вася", 1L, null)
        assertEquals(helloNoEcho, RadioHello.decode(helloNoEcho.encode()))
        val roster = RadioRoster(
            listOf(RadioRosterEntry(0, "hub", "Хаб"), RadioRosterEntry(4, "u-4", "Ann")),
            mode = 2, sentAtMillis = 99L, echoMillis = 7L,
        )
        assertEquals(roster, RadioRoster.decode(roster.encode()))
        val rosterNoEcho = RadioRoster(emptyList(), mode = 0, sentAtMillis = 5L, echoMillis = null)
        assertEquals(rosterNoEcho, RadioRoster.decode(rosterNoEcho.encode()))
        assertNull(RadioHello.decode(byteArrayOf(1)))
        assertNull(RadioRoster.decode(byteArrayOf(0, 5)))
    }

    @Test
    fun longNamesAreClampedTo80() {
        val hello = RadioHello("u", "x".repeat(200), 1L, null)
        assertEquals(80, RadioHello.decode(hello.encode())!!.displayName.length)
    }

    @Test
    fun rejectsTamperedPacket() {
        val bytes = codec.encode(packet(RadioPacketType.AUDIO))
        bytes[bytes.size - 20 + 3] = (bytes[bytes.size - 20 + 3].toInt() xor 1).toByte()
        assertNull(codec.decode(bytes))
        val tag = codec.encode(packet(RadioPacketType.AUDIO))
        tag[tag.size - 1] = (tag[tag.size - 1].toInt() xor 1).toByte()
        assertNull(codec.decode(tag))
    }

    @Test
    fun rejectsHeaderTamper() {
        val bytes = codec.encode(packet(RadioPacketType.AUDIO))
        bytes[4] = (bytes[4].toInt() xor 1).toByte() // sender
        assertNull(codec.decode(bytes))
    }

    @Test
    fun rejectsBadMagicAndVersion() {
        val magic = codec.encode(packet(RadioPacketType.AUDIO)).also { it[0] = 'X'.code.toByte() }
        assertNull(codec.decode(magic))
        val version = codec.encode(packet(RadioPacketType.AUDIO)).also { it[2] = 2 }
        assertNull(codec.decode(version))
        val type = codec.encode(packet(RadioPacketType.AUDIO)).also { it[3] = 99 }
        assertNull(codec.decode(type))
    }

    @Test
    fun rejectsForeignKey() {
        val bytes = codec.encode(packet(RadioPacketType.AUDIO))
        assertNull(RadioPacketCodec(ByteArray(16) { 9 }).decode(bytes))
    }

    @Test
    fun rejectsTruncated() {
        val bytes = codec.encode(packet(RadioPacketType.AUDIO))
        assertNull(codec.decode(bytes.copyOf(bytes.size - 1)))
        assertNull(codec.decode(bytes.copyOf(10)))
        assertNull(codec.decode(ByteArray(0)))
        assertNull(codec.decode(bytes.copyOf(17 + 15)))
    }

    @Test
    fun rejectsReplayedSequence() {
        val g = RadioReplayGuard()
        assertTrue(g.accept(1, 10, 5))
        assertFalse(g.accept(1, 10, 5))
        assertTrue(g.accept(1, 10, 6))
        assertFalse(g.accept(1, 10, 5))
        // other sender / session keep their own windows
        assertTrue(g.accept(2, 10, 5))
        assertTrue(g.accept(1, 11, 5))
        // out-of-order inside the window is fine once, too old is not
        assertTrue(g.accept(1, 10, 100))
        assertTrue(g.accept(1, 10, 90))
        assertFalse(g.accept(1, 10, 90))
        assertFalse(g.accept(1, 10, 36))
        assertTrue(g.accept(1, 10, 37))
    }

    @Test
    fun audioFrameFitsDatagram() {
        val size = codec.encode(packet(RadioPacketType.AUDIO, ByteArray(640))).size
        assertTrue(size <= 1200, "size $size")
    }
}
