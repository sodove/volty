package ru.sodovaya.volty.data.ble

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotificationRateMeterTest {
    private var nowMs = 1_000_000L
    private val meter = NotificationRateMeter(clock = { nowMs })

    private fun notifyEvery(intervalMs: Long, times: Int): Float? {
        var last: Float? = null
        repeat(times) { nowMs += intervalMs; last = meter.onNotification() }
        return last
    }

    @Test
    fun oneNotificationIsNotARate() {
        assertNull(meter.onNotification())
    }

    @Test
    fun steadyTenHertzReadsTen() {
        assertEquals(10f, assertNotNull(notifyEvery(100L, 30)), 0.01f)
    }

    @Test
    fun theWindowForgetsAnOlderFasterBurst() {
        notifyEvery(50L, 200) // 20 Hz for 10 s
        // Then 5 Hz: after 6 s the window holds only the slow stretch.
        val rate = assertNotNull(notifyEvery(200L, 30))
        assertEquals(5f, rate, 0.1f)
    }

    @Test
    fun measuredByNotificationTimeNotCallTime() {
        // Two bursts of the same notifications; the clock is the only thing that differs.
        val slow = NotificationRateMeter(clock = { nowMs })
        nowMs = 0L
        slow.onNotification()
        nowMs = 1_000L
        assertEquals(1f, assertNotNull(slow.onNotification()), 0.001f)
    }

    @Test
    fun aSilentGapLongerThanTheWindowStartsOver() {
        notifyEvery(100L, 20)
        nowMs += 20_000L
        assertNull(meter.onNotification(), "one notification after a long silence is not a rate")
    }

    @Test
    fun aClockGoingBackwardsNeverGrowsTheWindow() {
        // A wall clock stepping back (NTP, a manual change): with pruning relative to the newest
        // stamp alone, every earlier stamp would stay "in the window" and the deque would grow forever.
        nowMs = 1_000_000L
        meter.onNotification()
        repeat(20_000) {
            nowMs -= 1L
            meter.onNotification()
            assertTrue(meter.windowSize <= NotificationRateMeter.MAX_STAMPS, "window ${meter.windowSize}")
        }
        assertTrue(meter.windowSize <= 1, "a backwards step restarts the measurement: ${meter.windowSize}")
    }

    @Test
    fun aClockSteppedBackOnceRestartsTheRateInsteadOfInventingOne() {
        notifyEvery(100L, 30)
        nowMs -= 60_000L
        assertNull(meter.onNotification(), "one notification after a step back is not a rate")
        assertEquals(10f, assertNotNull(notifyEvery(100L, 10)), 0.01f)
    }

    @Test
    fun aStuckClockIsStillBounded() {
        repeat(3 * NotificationRateMeter.MAX_STAMPS) { meter.onNotification() }
        assertEquals(NotificationRateMeter.MAX_STAMPS, meter.windowSize)
    }

    @Test
    fun theMonotonicClockNeverReadsBackwards() {
        val clock = monotonicClockMs()
        var last = clock()
        repeat(10_000) {
            val now = clock()
            assertTrue(now >= last)
            last = now
        }
    }
}
