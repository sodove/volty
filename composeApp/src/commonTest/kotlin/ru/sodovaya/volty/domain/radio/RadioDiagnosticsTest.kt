package ru.sodovaya.volty.domain.radio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RadioDiagnosticsTest {
    @Test
    fun formatsRoleMembersAndRtt() {
        val text = RadioDiagnostics(
            role = RadioRole.CLIENT,
            mode = RadioMode.BALANCED,
            members = listOf(
                RadioMemberStat("Anna", rttMillis = 42, lossPercent = 3),
                RadioMemberStat("Boris", rttMillis = null, lossPercent = null),
            ),
            batteryStartPercent = 80,
            batteryNowPercent = 77,
            elapsedMillis = (12 * 60 + 34) * 1000L,
        ).format()
        val lines = text.lines()
        assertTrue("role=client" in lines, text)
        assertTrue("mode=BALANCED" in lines, text)
        assertTrue("members=2" in lines, text)
        assertTrue("member Anna rtt=42ms loss=3%" in lines, text)
        assertTrue("member Boris rtt=n/a loss=n/a" in lines, text)
        assertTrue("battery start=80% now=77%" in lines, text)
        assertTrue("elapsed=00:12:34" in lines, text)
    }

    @Test
    fun unknownBatteryAndModeAreMarkedNotZero() {
        val text = RadioDiagnostics(RadioRole.HUB, null, emptyList(), null, null, 0).format()
        assertTrue("role=hub" in text.lines(), text)
        assertTrue("mode=n/a" in text.lines(), text)
        assertTrue("battery start=n/a now=n/a" in text.lines(), text)
        assertTrue("members=0" in text.lines(), text)
    }

    @Test
    fun lossPercentFromGaps() {
        val counter = RadioLossCounter()
        assertNull(counter.lossPercent())
        listOf(0L, 1, 2, 4, 5, 9).forEach(counter::onSeq)
        // 10 expected (0..9), 6 arrived.
        assertEquals(40, counter.lossPercent())
    }

    @Test
    fun duplicatesAndLateFramesAreNotNegativeLoss() {
        val counter = RadioLossCounter()
        listOf(10L, 11, 11, 13, 12, 14).forEach(counter::onSeq)
        // 10..14 = 5 expected; 10,11,13,14 counted in order = 4 arrived; 11 repeated and 12 late are ignored.
        assertEquals(20, counter.lossPercent())
    }

    @Test
    fun noGapsIsZeroLoss() {
        val counter = RadioLossCounter()
        (100L..140L).forEach(counter::onSeq)
        assertEquals(0, counter.lossPercent())
    }

    @Test
    fun restartedNumberingStartsOverInsteadOfHidingLoss() {
        val counter = RadioLossCounter()
        (5_000L..5_099L).forEach(counter::onSeq)
        listOf(0L, 1, 3).forEach(counter::onSeq) // the sender rejoined with a new stream; one of 0..3 lost
        assertEquals(25, counter.lossPercent())
    }
}
