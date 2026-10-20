package ru.sodovaya.volty.domain.radio

import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RadioClientSessionTest {
    private val random = Random(7)

    private fun dbfsToRms(db: Double): Double = 32768.0 * 10.0.pow(db / 20.0)

    private fun quiet(): ShortArray {
        val a = dbfsToRms(-60.0) * 1.7320508
        return ShortArray(RADIO_FRAME_SAMPLES) { ((random.nextDouble() * 2 - 1) * a).toInt().toShort() }
    }

    private fun loud(): ShortArray {
        val amp = dbfsToRms(-30.0) * 1.4142136
        return ShortArray(RADIO_FRAME_SAMPLES) { (amp * sin(2 * PI * 440.0 * it / 16000.0)).toInt().toShort() }
    }

    private fun frameOf(marker: Int) = ShortArray(RADIO_FRAME_SAMPLES) { marker.toShort() }

    private fun session() = RadioClientSession("me", "Me", streamId = 11)

    private fun roster(
        mode: RadioMode = RadioMode.BALANCED,
        echo: Long? = null,
        hubStream: Int = 5,
        mySlot: Int = 2,
    ) = RadioMessage.Roster(
        listOf(RadioRosterEntry(0, "hub", "Hub"), RadioRosterEntry(mySlot, "me", "Me")),
        mode.wire, echo, hubStream,
    )

    /** Connected session with a built noise floor; its clock ends at about 2 s, hub last heard at 10 ms. */
    private fun connected(): RadioClientSession {
        val s = session()
        s.tick(0, quiet())
        s.receive(roster(), 10)
        var t = 20L
        repeat(100) { s.tick(t, quiet()); t += 20 }
        return s
    }

    private fun audio(out: List<RadioMessage>) = out.filterIsInstance<RadioMessage.Audio>()

    @Test
    fun sendsHelloOnStartAndEverySecond() {
        val s = session()
        val first = s.tick(0, quiet())
        assertEquals(listOf<RadioMessage>(RadioMessage.Hello("me", "Me", 0, 11)), first)
        assertTrue(s.tick(500, quiet()).none { it is RadioMessage.Hello })
        assertTrue(s.tick(999, quiet()).none { it is RadioMessage.Hello })
        assertEquals(RadioMessage.Hello("me", "Me", 1000, 11), s.tick(1000, quiet()).single())
        assertEquals(RadioMessage.Hello("me", "Me", 2000, 11), s.tick(2000, quiet()).single())
    }

    @Test
    fun becomesConnectedOnRoster() {
        val s = session()
        s.tick(0, quiet())
        assertEquals(RadioClientPhase.JOINING, s.phase)
        s.receive(roster(mySlot = 3), 100)
        assertEquals(RadioClientPhase.CONNECTED, s.phase)
        assertEquals(3, s.slot)
        assertEquals(listOf(0, 3), s.roster.map { it.slot })
    }

    @Test
    fun silenceIsNotTransmitted() {
        val s = connected()
        var t = 2_100L
        repeat(50) { assertTrue(audio(s.tick(t, quiet())).isEmpty()); t += 20 }
    }

    @Test
    fun speechIsTransmittedWithIncreasingSeq() {
        val s = connected()
        val loudFrame = loud()
        val a = audio(s.tick(2_100, loudFrame)).single()
        val b = audio(s.tick(2_120, loudFrame)).single()
        assertEquals(2, a.slot)
        assertEquals(listOf(0L, 1L), listOf(a.seq, b.seq))
        // 15 hangover frames are still sent; quiet frames after them are not and consume no seq
        var t = 2_140L
        val sent = mutableListOf<Long>()
        repeat(40) { sent += audio(s.tick(t, quiet())).map { it.seq }; t += 20 }
        assertEquals((2L..16L).toList(), sent)
        assertEquals(17L, audio(s.tick(t, loudFrame)).single().seq)
    }

    @Test
    fun noAudioUntilSlotKnown() {
        val s = session()
        var t = 0L
        repeat(100) { s.tick(t, quiet()); t += 20 }
        assertTrue(audio(s.tick(t, loud())).isEmpty())
        s.receive(roster(), t)
        assertEquals(0L, audio(s.tick(t + 20, loud())).single().seq)
    }

    @Test
    fun clientMarksLostAfterThreeSilentSeconds() {
        val s = connected()
        s.receive(roster(), 5_000)
        s.tick(7_999, quiet())
        assertEquals(RadioClientPhase.CONNECTED, s.phase)
        s.tick(8_000, quiet())
        assertEquals(RadioClientPhase.LOST, s.phase)
        assertTrue(audio(s.tick(8_020, loud())).isEmpty())
        assertNull(s.playback())
    }

    @Test
    fun lostSessionKeepsProbingAndRosterRevivesIt() {
        val s = connected()
        s.tick(9_000, quiet())
        assertEquals(RadioClientPhase.LOST, s.phase)
        assertTrue(s.tick(10_000, quiet()).any { it is RadioMessage.Hello })
        s.receive(roster(), 10_100)
        assertEquals(RadioClientPhase.CONNECTED, s.phase)
    }

    @Test
    fun fullStopsTransmission() {
        val s = session()
        s.tick(0, quiet())
        s.receive(RadioMessage.Full, 50)
        assertEquals(RadioClientPhase.FULL, s.phase)
        assertTrue(s.tick(1_000, quiet()).isEmpty())
        assertTrue(s.tick(9_000, loud()).isEmpty())
        assertEquals(RadioClientPhase.FULL, s.phase)
    }

    @Test
    fun rttFromEcho() {
        val s = session()
        s.tick(0, quiet())
        s.receive(roster(echo = 0), 40)
        assertEquals(40L, s.rttMillis)
    }

    @Test
    fun staleRosterDoesNotSampleRtt() {
        val s = session()
        s.tick(0, quiet())
        s.receive(roster(echo = 0), 40)
        s.tick(1_000, quiet())
        // periodic roster arrives 1.5 s after the latest Hello, echoing the old one
        s.receive(roster(echo = 0), 2_500)
        assertEquals(40L, s.rttMillis)
        // within a second of the latest Hello: sampled
        s.receive(roster(echo = 1_000), 1_060)
        assertEquals(60L, s.rttMillis)
    }

    @Test
    fun followsHubMode() {
        val s = session()
        assertEquals(RadioMode.BALANCED, s.mode)
        s.receive(roster(mode = RadioMode.RELIABLE), 10)
        assertEquals(RadioMode.RELIABLE, s.mode)
        s.receive(roster(mode = RadioMode.LOW_LATENCY), 20)
        assertEquals(RadioMode.LOW_LATENCY, s.mode)
    }

    @Test
    fun playbackUsesModeJitterTarget() {
        val s = session()
        s.tick(0, quiet())
        s.receive(roster(mode = RadioMode.LOW_LATENCY), 10)
        s.receive(RadioMessage.Audio(0, 0, frameOf(1)), 20)
        assertNull(s.playback())
        s.receive(RadioMessage.Audio(0, 1, frameOf(2)), 30)
        assertContentEquals(frameOf(1), s.playback())
        assertContentEquals(frameOf(2), s.playback())
    }

    @Test
    fun modeChangeRebuildsBuffer() {
        val s = session()
        s.tick(0, quiet())
        s.receive(roster(), 10)
        repeat(3) { s.receive(RadioMessage.Audio(0, it.toLong(), frameOf(it + 1)), 20) }
        s.receive(roster(mode = RadioMode.RELIABLE), 30)
        assertNull(s.playback(), "old frames discarded with the old buffer")
    }

    @Test
    fun newHubStreamStartsFreshJitterBuffer() {
        val s = session()
        s.tick(0, quiet())
        s.receive(roster(hubStream = 5), 10)
        repeat(3) { s.receive(RadioMessage.Audio(0, 100L + it, frameOf(1)), 20) }
        repeat(3) { assertNotNull(s.playback()) }
        // a re-raised hub restarts seq at 0: an old high-water mark would reject these
        s.receive(roster(hubStream = 6), 100)
        repeat(3) { s.receive(RadioMessage.Audio(0, it.toLong(), frameOf(9)), 110) }
        assertContentEquals(frameOf(9), s.playback())
    }

    @Test
    fun sameHubStreamKeepsBuffer() {
        val s = session()
        s.tick(0, quiet())
        s.receive(roster(hubStream = 5), 10)
        repeat(3) { s.receive(RadioMessage.Audio(0, 100L + it, frameOf(1)), 20) }
        repeat(3) { assertNotNull(s.playback()) }
        s.receive(roster(hubStream = 5), 100)
        repeat(3) { s.receive(RadioMessage.Audio(0, it.toLong(), frameOf(9)), 110) }
        assertNull(s.playback(), "high-water mark kept: stale seq rejected")
    }

    @Test
    fun leavingLostStartsFreshJitterBuffer() {
        val s = session()
        s.tick(0, quiet())
        s.receive(roster(), 10)
        repeat(3) { s.receive(RadioMessage.Audio(0, 100L + it, frameOf(1)), 20) }
        repeat(3) { assertNotNull(s.playback()) }
        s.tick(5_000, quiet())
        assertEquals(RadioClientPhase.LOST, s.phase)
        s.receive(roster(), 6_000)
        repeat(3) { s.receive(RadioMessage.Audio(0, it.toLong(), frameOf(9)), 6_010) }
        assertContentEquals(frameOf(9), s.playback())
    }
}
