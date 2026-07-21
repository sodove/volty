package ru.sodovaya.dumper

import kotlin.test.Test
import kotlin.test.assertEquals

class FrameSanityTest {

    /**
     * Builds a well-formed 24-byte Begode frame: 0x55 0xAA header, [type] at
     * index 18, [num] at index 19, 0x5A 0x5A 0x5A 0x5A tail at 20..23.
     */
    private fun frame(type: Int, num: Int): ByteArray {
        val f = ByteArray(24)
        f[0] = 0x55
        f[1] = 0xAA.toByte()
        // Bytes 2..17 are payload; their values do not matter to FrameSanity.
        for (i in 2..17) f[i] = 0x11
        f[18] = type.toByte()
        f[19] = num.toByte()
        for (i in 20..23) f[i] = 0x5A
        return f
    }

    @Test
    fun countsAWellFormedFrame() {
        val s = FrameSanity()
        s.feed(frame(type = 0x00, num = 0))
        val o = s.observations()
        assertEquals(1, o.notifications)
        assertEquals(24, o.bytes)
        assertEquals(1, o.frames)
        assertEquals(setOf(0x00), o.frameTypes)
    }

    @Test
    fun collectsBmsNumsOnlyFromTypeOne() {
        val s = FrameSanity()
        s.feed(frame(type = 0x01, num = 0))
        s.feed(frame(type = 0x01, num = 3))
        s.feed(frame(type = 0x04, num = 7))
        assertEquals(setOf(0, 3), s.observations().bmsNums)
    }

    @Test
    fun tracksTheHighestCellPacketIndex() {
        val s = FrameSanity()
        s.feed(frame(type = 0x02, num = 1))
        s.feed(frame(type = 0x03, num = 4))
        s.feed(frame(type = 0x02, num = 2))
        // Type 0x01's num must not leak into the cell-packet maximum.
        s.feed(frame(type = 0x01, num = 9))
        assertEquals(4, s.observations().maxCellPacket)
    }

    @Test
    fun reassemblesAFrameSplitAcrossChunks() {
        // At MTU 23 a 24-byte frame ALWAYS arrives split — this is the normal
        // case on a real wheel, not an edge case.
        val f = frame(type = 0x02, num = 5)
        val s = FrameSanity()
        s.feed(f.copyOfRange(0, 20))
        s.feed(f.copyOfRange(20, 24))
        val o = s.observations()
        assertEquals(2, o.notifications)
        assertEquals(24, o.bytes)
        assertEquals(1, o.frames)
        assertEquals(setOf(0x02), o.frameTypes)
        assertEquals(5, o.maxCellPacket)
    }

    @Test
    fun resynchronisesAfterLeadingGarbage() {
        val s = FrameSanity()
        s.feed(byteArrayOf(0x4E, 0x41, 0x4D, 0x45)) // "NAME" — the wheel's ASCII preamble
        s.feed(frame(type = 0x04, num = 0))
        assertEquals(1, s.observations().frames)
        assertEquals(setOf(0x04), s.observations().frameTypes)
    }

    @Test
    fun rejectsAFrameWithABrokenTailAndStillFindsTheNextOne() {
        val bad = frame(type = 0x00, num = 0).also { it[23] = 0x00 }
        val s = FrameSanity()
        s.feed(bad)
        assertEquals(0, s.observations().frames)
        s.feed(frame(type = 0x01, num = 2))
        assertEquals(1, s.observations().frames)
        assertEquals(setOf(0x01), s.observations().frameTypes)
        assertEquals(setOf(2), s.observations().bmsNums)
    }

    @Test
    fun advancesOneByteOnAFalseHeaderSoTheRealFrameBehindItIsNotSwallowed() {
        // A payload-borne 0x55 0xAA immediately followed by a real frame, in a
        // single chunk. The false header's "tail" lands inside the real frame
        // and fails to validate. Advancing one byte finds the real frame at
        // offset 2; skipping a whole 24 bytes would land mid-frame and lose it.
        val s = FrameSanity()
        s.feed(byteArrayOf(0x55, 0xAA.toByte()) + frame(type = 0x03, num = 6))
        val o = s.observations()
        assertEquals(1, o.frames)
        assertEquals(setOf(0x03), o.frameTypes)
        assertEquals(6, o.maxCellPacket)
    }

    @Test
    fun survivesALongHeaderFreeStreamAndStillCountsALaterFrame() {
        // The wrong device picked from the scan list: minutes of chatter with
        // no 0x55 anywhere. The buffer must not accumulate it, and a real
        // frame arriving afterwards must still be found and counted.
        val s = FrameSanity()
        repeat(1000) { s.feed(ByteArray(20) { 0x42 }) }
        assertEquals(0, s.observations().frames)
        s.feed(frame(type = 0x01, num = 1))
        val o = s.observations()
        assertEquals(1, o.frames)
        assertEquals(setOf(0x01), o.frameTypes)
        assertEquals(setOf(1), o.bmsNums)
    }

    @Test
    fun keepsATrailing0x55WhoseHeaderCompletesInTheNextNotification() {
        // Garbage ending in the first header byte: the trim on a header-free
        // buffer must keep that 0x55, because the 0xAA completing the header
        // arrives in the next notification. Dropping it would lose this frame.
        val f = frame(type = 0x02, num = 3)
        val s = FrameSanity()
        s.feed(byteArrayOf(0x10, 0x20, 0x30, 0x55))
        s.feed(f.copyOfRange(1, 24))
        val o = s.observations()
        assertEquals(1, o.frames)
        assertEquals(setOf(0x02), o.frameTypes)
        assertEquals(3, o.maxCellPacket)
    }

    @Test
    fun countsTwoFramesArrivingInOneChunk() {
        val s = FrameSanity()
        s.feed(frame(type = 0x00, num = 0) + frame(type = 0x04, num = 0))
        val o = s.observations()
        assertEquals(1, o.notifications)
        assertEquals(48, o.bytes)
        assertEquals(2, o.frames)
        assertEquals(setOf(0x00, 0x04), o.frameTypes)
    }

    @Test
    fun resetClearsEverything() {
        val s = FrameSanity()
        s.feed(frame(type = 0x01, num = 3))
        s.reset()
        val o = s.observations()
        assertEquals(0, o.notifications)
        assertEquals(0, o.bytes)
        assertEquals(0, o.frames)
        assertEquals(emptySet(), o.frameTypes)
        assertEquals(emptySet(), o.bmsNums)
        assertEquals(-1, o.maxCellPacket)
    }
}
