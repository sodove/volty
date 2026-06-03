package ru.sodovaya.volty.domain.stats

import ru.sodovaya.volty.domain.model.BmsData
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class MovingAverageTest {

    private fun sample(t: Instant, power: Float, current: Float) =
        BmsData(power = power, current = current, timestamp = t)

    private fun assertClose(expected: Float, actual: Float, delta: Float = 0.01f) {
        assertTrue(abs(expected - actual) <= delta, "expected $expected ± $delta, got $actual")
    }

    @Test
    fun `empty list returns zero average`() {
        val avg = MovingAverage.over(emptyList(), 5.minutes)
        assertEquals(0f, avg.avgPowerW)
        assertEquals(0f, avg.avgCurrentA)
        assertEquals(5.minutes, avg.window)
    }

    @Test
    fun `single sample returns its values`() {
        val base = Instant.fromEpochSeconds(1_000_000)
        val avg = MovingAverage.over(listOf(sample(base, 200f, -4f)), 5.minutes)
        assertEquals(200f, avg.avgPowerW)
        assertEquals(-4f, avg.avgCurrentA)
    }

    @Test
    fun `arithmetic mean of several samples`() {
        val base = Instant.fromEpochSeconds(1_000_000)
        val samples = listOf(
            sample(base, 100f, -2f),
            sample(base.plus(1.seconds), 200f, -4f),
            sample(base.plus(2.seconds), 300f, -6f)
        )
        val avg = MovingAverage.over(samples, 5.minutes)
        assertClose(200f, avg.avgPowerW)
        assertClose(-4f, avg.avgCurrentA)
    }
}
