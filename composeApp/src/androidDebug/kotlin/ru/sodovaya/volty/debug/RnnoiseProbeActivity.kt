package ru.sodovaya.volty.debug

import android.app.Activity
import android.os.Bundle
import android.util.Log
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random
import ru.sodovaya.volty.data.voice.RnnoiseVad

/**
 * Debug-only check that the native RNNoise loads and runs on a device:
 * `adb shell am start -n ru.sodovaya.volty/.debug.RnnoiseProbeActivity`, then `adb logcat -s RnnoiseProbe`.
 * Logs the mean speech probability for silence, white noise and a synthetic voiced vowel, and ms per frame.
 */
class RnnoiseProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val vad = RnnoiseVad.createOrNull()
        if (vad == null) { Log.e(TAG, "RNNoise unavailable"); finish(); return }
        val frame = ShortArray(RnnoiseVad.FRAME_SIZE)
        val random = Random(1)
        var t = 0L
        fun measure(name: String, fill: () -> Unit) {
            var sum = 0f
            val started = System.nanoTime()
            repeat(FRAMES) { fill(); sum += vad.probability(frame) }
            val ms = (System.nanoTime() - started) / 1e6 / FRAMES
            Log.i(TAG, "$name: mean speech probability %.3f, %.3f ms/frame".format(sum / FRAMES, ms))
        }
        measure("silence") { frame.fill(0) }
        measure("white noise") { for (i in frame.indices) frame[i] = (random.nextInt(-6000, 6000)).toShort() }
        // A 120 Hz glottal-like pulse train shaped by two vowel formants (~700 and ~1200 Hz), syllables 200 ms on/off.
        measure("synthetic vowel") {
            for (i in frame.indices) {
                val s = t++ / 48_000.0
                val on = ((s * 2.5).toInt() % 2 == 0)
                var v = 0.0
                for (h in 1..30) {
                    val f = 120.0 * h
                    val gain = 1.0 / h * (1.5 / (1 + ((f - 700) / 150).let { it * it }) + 1.0 / (1 + ((f - 1200) / 200).let { it * it }) + 0.05)
                    v += gain * sin(2 * PI * f * s)
                }
                frame[i] = if (on) (v * 6000).toInt().coerceIn(-32768, 32767).toShort() else 0
            }
        }
        vad.close()
        Log.i(TAG, "done")
        finish()
    }

    private companion object {
        const val TAG = "RnnoiseProbe"
        const val FRAMES = 300
    }
}
