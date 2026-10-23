package ru.sodovaya.volty.domain.voice

import ru.sodovaya.volty.domain.audio.MicProcessingSettings
import ru.sodovaya.volty.domain.audio.dbToGain
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

class MicGainStageTest {
    private val rate = 48_000

    private fun sine(dbfs: Float, samples: Int, hz: Double = 440.0): ShortArray {
        val amp = dbToGain(dbfs) * 32767.0
        return ShortArray(samples) { (amp * sin(2 * PI * hz * it / rate)).roundToInt().toShort() }
    }

    private fun peakDbfs(s: ShortArray, count: Int = s.size): Double {
        var peak = 0
        for (i in 0 until count) peak = maxOf(peak, abs(s[i].toInt()))
        return 20 * log10(peak / 32768.0)
    }

    @Test
    fun zeroSensitivityAndAHighCeilingLeaveSpeechUntouched() {
        val stage = MicGainStage(MicProcessingSettings(sensitivityDb = 0f, limiterCeilingDbfs = -1f), rate)
        val speech = sine(-12f, 4_800)
        val copy = speech.copyOf()
        stage.process(copy, copy.size)
        assertContentEquals(speech, copy)
    }

    @Test
    fun aBoostedFrameNeverExceedsTheCeiling() {
        val stage = MicGainStage(MicProcessingSettings(sensitivityDb = 12f, limiterCeilingDbfs = -3f), rate)
        val frame = sine(-6f, 9_600)
        stage.process(frame, frame.size)
        val peak = peakDbfs(frame)
        assertTrue(peak <= -3.0 + 0.1, "peak $peak dBFS")
        // and it is actually limited, not simply silenced
        assertTrue(peak > -3.5, "peak $peak dBFS")
    }

    @Test
    fun aShortBurstIsHeldDownThenReleases() {
        val stage = MicGainStage(MicProcessingSettings(sensitivityDb = 0f, limiterCeilingDbfs = -9f), rate)
        val burst = sine(0f, rate / 50) // 20 ms at 0 dBFS
        stage.process(burst, burst.size)
        assertTrue(peakDbfs(burst) <= -9.0 + 0.1)

        val speech = sine(-20f, rate / 10) // 100 ms
        val reference = speech.copyOf()
        stage.process(speech, speech.size)
        // Right after the burst the speech is held down ...
        val head = 48
        val headRatio = rms(speech, 0, 480) / rms(reference, 0, 480)
        assertTrue(headRatio < 0.95, "held down: $headRatio")
        // ... and 80 ms later the gain is back to >= 0.9 of nominal.
        val from = rate * 80 / 1000 - 480
        val tailRatio = rms(speech, from, 480) / rms(reference, from, 480)
        assertTrue(tailRatio >= 0.9, "released: $tailRatio")
        assertTrue(head > 0)
    }

    @Test
    fun theStateIsPerStageNotShared() {
        val settings = MicProcessingSettings(sensitivityDb = 0f, limiterCeilingDbfs = -9f)
        val hit = MicGainStage(settings, rate)
        val fresh = MicGainStage(settings, rate)
        val burst = sine(0f, rate / 50)
        hit.process(burst, burst.size)
        val a = sine(-20f, 480)
        val b = a.copyOf()
        fresh.process(b, b.size)
        assertContentEquals(a, b) // the untouched stage is not held down by the other's burst
    }

    @Test
    fun onlyTheFirstCountSamplesAreTouched() {
        val stage = MicGainStage(MicProcessingSettings(sensitivityDb = 12f, limiterCeilingDbfs = -3f), rate)
        val buf = sine(-6f, 100)
        val tail = buf.copyOfRange(50, 100)
        stage.process(buf, 50)
        assertContentEquals(tail, buf.copyOfRange(50, 100))
    }

    private fun rms(s: ShortArray, from: Int, n: Int): Double {
        var sum = 0.0
        for (i in from until from + n) sum += s[i].toDouble() * s[i]
        return kotlin.math.sqrt(sum / n)
    }
}
