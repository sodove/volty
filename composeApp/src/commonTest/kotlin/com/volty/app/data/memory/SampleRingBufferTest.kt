package com.volty.app.data.memory

import com.volty.app.domain.model.BmsData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class SampleRingBufferTest {

    private fun sample(t: Instant, power: Float = 0f) =
        BmsData(power = power, timestamp = t)

    @Test
    fun `push and within returns chronological items inside the window`() {
        val buf = SampleRingBuffer()
        val base = Instant.fromEpochSeconds(1_000_000)
        for (i in 0..9) buf.push(sample(base.plus(i.seconds), i.toFloat()))
        val recent = buf.within(5.seconds, now = base.plus(9.seconds))
        // Window covers t in [4s..9s] -> 6 samples
        assertEquals(6, recent.size)
        assertEquals(4f, recent.first().power)
        assertEquals(9f, recent.last().power)
    }

    @Test
    fun `maxAge evicts samples older than the window`() {
        val buf = SampleRingBuffer(maxAge = 10.seconds)
        val base = Instant.fromEpochSeconds(1_000_000)
        // Push 5 samples 1s apart, then jump forward 25s — earlier ones must evict.
        for (i in 0..4) buf.push(sample(base.plus(i.seconds), i.toFloat()))
        buf.push(sample(base.plus(25.seconds), 99f))
        val all = buf.all()
        // Only the t=25s sample is within maxAge of itself.
        assertEquals(1, all.size)
        assertEquals(99f, all.first().power)
    }

    @Test
    fun `within returns empty when no samples in window`() {
        val buf = SampleRingBuffer()
        val base = Instant.fromEpochSeconds(1_000_000)
        buf.push(sample(base, 1f))
        val recent = buf.within(1.seconds, now = base.plus(1.minutes))
        assertEquals(0, recent.size)
    }

    @Test
    fun `clear empties buffer`() {
        val buf = SampleRingBuffer()
        val base = Instant.fromEpochSeconds(1_000_000)
        buf.push(sample(base, 1f))
        buf.clear()
        assertEquals(0, buf.within(1.minutes, now = base).size)
    }

    @Test
    fun `holds an hour of 2Hz samples under default maxAge`() {
        // ANT BMS polls at 2 Hz. Default maxAge of 4h must comfortably hold an
        // hour's worth (7200 samples), well under hardCap.
        val buf = SampleRingBuffer()
        val base = Instant.fromEpochSeconds(1_000_000)
        for (i in 0 until 7200) {
            buf.push(sample(base.plus((i * 500L).milliseconds), i.toFloat()))
        }
        assertEquals(7200, buf.size())
        // And all of it should be visible to a 1-hour window query.
        val lastT = base.plus((7199 * 500L).milliseconds)
        val window1h = buf.within(1.minutes * 60, now = lastT)
        assertTrue(window1h.size > 7000, "expected >7000 samples in 1h window, got ${window1h.size}")
    }
}
