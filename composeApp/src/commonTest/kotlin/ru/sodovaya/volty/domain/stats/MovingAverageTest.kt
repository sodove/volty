package ru.sodovaya.volty.domain.stats

import ru.sodovaya.volty.domain.model.BmsData
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class MovingAverageTest {

    private fun sample(t: Instant, power: Float, current: Float) =
        BmsData(power = power, current = current, timestamp = t)

    private fun assertClose(expected: Float, actual: Float?, delta: Float = 0.01f) {
        val measured = assertNotNull(actual)
        assertTrue(abs(expected - measured) <= delta, "expected $expected ± $delta, got $measured")
    }

    @Test
    fun `empty list retains that neither average was measured`() {
        val avg = MovingAverage.over(emptyList(), 5.minutes)
        assertNull(avg.avgPowerW)
        assertNull(avg.avgCurrentA)
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

    @Test
    fun `unknown current and power do not dilute measured values or erase a genuine zero`() {
        val base = Instant.fromEpochSeconds(1_000_000)
        val samples = listOf(
            BmsData(power = 900f, hasPower = false, current = 99f, hasCurrent = false, timestamp = base),
            BmsData(power = 0f, hasPower = true, current = 0f, hasCurrent = true, timestamp = base.plus(1.seconds)),
            BmsData(power = 200f, hasPower = true, current = -4f, hasCurrent = true, timestamp = base.plus(2.seconds))
        )

        val avg = MovingAverage.over(samples, 5.minutes)

        assertClose(100f, avg.avgPowerW)
        assertClose(-2f, avg.avgCurrentA)
    }

    @Test
    fun `all unavailable samples remain unknown while measured zero stays zero`() {
        val base = Instant.fromEpochSeconds(1_000_000)
        val unavailable = MovingAverage.over(
            listOf(
                BmsData(power = 900f, hasPower = false, current = 99f, hasCurrent = false, timestamp = base),
                BmsData(power = 700f, hasPower = false, current = 88f, hasCurrent = false, timestamp = base.plus(1.seconds))
            ),
            5.minutes
        )
        assertNull(unavailable.avgPowerW)
        assertNull(unavailable.avgCurrentA)

        val measuredZero = MovingAverage.over(
            listOf(BmsData(power = 0f, hasPower = true, current = 0f, hasCurrent = true, timestamp = base)),
            5.minutes
        )
        assertClose(0f, measuredZero.avgPowerW)
        assertClose(0f, measuredZero.avgCurrentA)
    }
}
