package com.volty.app.data.memory

import com.volty.app.domain.model.BmsData
import kotlin.test.Test
import kotlin.test.assertEquals
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
        val buf = SampleRingBuffer(capacity = 100)
        val base = Instant.fromEpochSeconds(1_000_000)
        for (i in 0..9) buf.push(sample(base.plus(i.seconds), i.toFloat()))
        val recent = buf.within(5.seconds, now = base.plus(9.seconds))
        // Window covers t in [4s..9s] -> 6 samples
        assertEquals(6, recent.size)
        assertEquals(4f, recent.first().power)
        assertEquals(9f, recent.last().power)
    }

    @Test
    fun `push beyond capacity overwrites oldest`() {
        val buf = SampleRingBuffer(capacity = 3)
        val base = Instant.fromEpochSeconds(1_000_000)
        for (i in 0..4) buf.push(sample(base.plus(i.seconds), i.toFloat()))
        val all = buf.within(1.minutes, now = base.plus(10.seconds))
        assertEquals(3, all.size)
        assertEquals(2f, all.first().power)
        assertEquals(4f, all.last().power)
    }

    @Test
    fun `within returns empty when no samples in window`() {
        val buf = SampleRingBuffer(capacity = 10)
        val base = Instant.fromEpochSeconds(1_000_000)
        buf.push(sample(base, 1f))
        val recent = buf.within(1.seconds, now = base.plus(1.minutes))
        assertEquals(0, recent.size)
    }

    @Test
    fun `clear empties buffer`() {
        val buf = SampleRingBuffer(capacity = 10)
        val base = Instant.fromEpochSeconds(1_000_000)
        buf.push(sample(base, 1f))
        buf.clear()
        assertEquals(0, buf.within(1.minutes, now = base).size)
    }
}
