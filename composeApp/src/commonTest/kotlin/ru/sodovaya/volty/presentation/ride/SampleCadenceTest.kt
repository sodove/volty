package ru.sodovaya.volty.presentation.ride

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class SampleCadenceTest {

    @Test
    fun `no arrivals expose no rate`() {
        val cadence = sampleCadence(emptyList())

        assertNull(cadence.rateHz)
        assertEquals(SampleCadencePhase.NO_SAMPLES, cadence.phase)
    }

    @Test
    fun `one arrival exposes warmup without inventing zero hertz`() {
        val cadence = sampleCadence(listOf(Instant.fromEpochSeconds(10)))

        assertNull(cadence.rateHz)
        assertEquals(SampleCadencePhase.WARMUP, cadence.phase)
    }

    @Test
    fun `rate is derived from observed timestamp intervals`() {
        val start = Instant.fromEpochSeconds(10)
        val cadence = sampleCadence(listOf(start, start + 100L.milliseconds, start + 200L.milliseconds))

        assertEquals(10f, cadence.rateHz!!, absoluteTolerance = 0.001f)
        assertEquals(SampleCadencePhase.WARMUP, cadence.phase)
    }

    @Test
    fun `cadence leaves warmup only after the observed window`() {
        val start = Instant.fromEpochSeconds(10)
        val cadence = sampleCadence(
            listOf(start, start + 1.seconds, start + 2.seconds, start + 6.seconds)
        )

        assertEquals(0.5f, cadence.rateHz!!, absoluteTolerance = 0.001f)
        assertEquals(SampleCadencePhase.STEADY, cadence.phase)
    }
}
