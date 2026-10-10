package ru.sodovaya.volty.data.voice

import kotlin.test.*

class VoiceCaptureCadenceTest {
    @Test fun schedulerOversleepDoesNotAccumulateCaptureClockDrift() {
        val cadence=VoiceCaptureCadence()
        assertEquals(10_000_000,cadence.deadline(0))
        assertEquals(20_000_000,cadence.deadline(10_500_000))
        assertEquals(30_000_000,cadence.deadline(20_700_000))
    }
    @Test fun longNativeStallResetsInsteadOfBurstingOldFrames() {
        val cadence=VoiceCaptureCadence()
        cadence.deadline(0)
        assertEquals(110_000_000,cadence.deadline(100_000_000))
        assertEquals(120_000_000,cadence.deadline(110_500_000))
        cadence.reset()
        assertEquals(140_500_000,cadence.deadline(130_500_000))
    }
}
