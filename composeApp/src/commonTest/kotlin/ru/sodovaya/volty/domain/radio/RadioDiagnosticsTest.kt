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
    fun formatsHubLoad() {
        val text = RadioDiagnostics(
            RadioRole.HUB, RadioMode.BALANCED, emptyList(), 90, 88, 60_000,
            load = RadioLoad(
                tickMaxMillis = 23.4,
                tickP95Millis = 2.0,
                lateTicks = 3,
                droppedEncodes = 1,
                rejectedInbound = 7,
                decodeFailures = 2,
                jitterDepthFrames = mapOf(3 to 4, 1 to 2),
            ),
        ).format()
        val lines = text.lines()
        assertTrue("tick max=23.4ms p95=2.0ms late(>20ms)=3" in lines, text)
        assertTrue("dropped_encodes=1" in lines, text)
        assertTrue("rejected_inbound=7" in lines, text)
        assertTrue("decode_failures=2" in lines, text)
        assertTrue("jitter_frames slot1=2 slot3=4" in lines, text)
    }

    @Test
    fun unmeasuredLoadIsNotAPrintedZero() {
        val none = RadioDiagnostics(RadioRole.HUB, null, emptyList(), null, null, 0).format()
        assertTrue("tick max=n/a p95=n/a late(>20ms)=n/a" in none.lines(), none)
        val empty = RadioDiagnostics(
            RadioRole.CLIENT, null, emptyList(), null, null, 0,
            load = RadioLoad(null, null, 0, 0, 0, 0, emptyMap()),
        ).format()
        assertTrue("tick max=n/a p95=n/a late(>20ms)=0" in empty.lines(), empty)
        assertTrue("jitter_frames n/a" in empty.lines(), empty)
    }

    @Test
    fun tickStatsMaxP95AndLate() {
        val stats = RadioTickStats()
        assertNull(stats.maxMillis())
        assertNull(stats.p95Millis())
        repeat(94) { stats.record(1_000_000) } // 1 ms
        repeat(5) { stats.record(5_000_000) } // 5 ms
        stats.record(25_000_000) // one late tick
        assertEquals(25.0, stats.maxMillis())
        assertEquals(5.0, stats.p95Millis())
        assertEquals(1L, stats.lateTicks)
        stats.record(20_000_000) // exactly one frame is not late
        assertEquals(1L, stats.lateTicks)
    }

    @Test
    fun tickStatsP95BeyondTheHistogramIsTheMax() {
        val stats = RadioTickStats()
        repeat(10) { stats.record(400_000_000) } // 400 ms: a stalled thread
        assertEquals(400.0, stats.p95Millis())
        assertEquals(10L, stats.lateTicks)
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
