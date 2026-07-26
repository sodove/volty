package ru.sodovaya.volty.notification

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import ru.sodovaya.volty.domain.alert.AlarmTone
import ru.sodovaya.volty.domain.alert.AlarmToneRenderer
import kotlin.math.min

/**
 * One running tone: a thread that owns exactly one [AudioTrack] and streams
 * `burst, rest, burst, rest…` into it until told to stop.
 *
 * ### Ownership, stated plainly
 *
 * The `AudioTrack` is **created inside [run] and released inside [run]'s
 * `finally`**, and no reference to it ever leaves this thread. That is the whole
 * lifetime. There is no path — exception, error return, [quit], or the process
 * tearing the thread down — that reaches the end of [run] without releasing it,
 * because the only way out of [run] is through that `finally`. An `AudioTrack`
 * left open holds a mixer output alive on a phone the rider is carrying, so this
 * is not a detail to leave to a caller's discipline.
 *
 * [AudibleAlarm] drops its reference the moment it calls [quit]; a stopped player
 * is never reused, and a new step gets a new player.
 *
 * ### A thread rather than a coroutine
 *
 * `AudioTrack.write` in `MODE_STREAM` blocks until the buffer takes the data —
 * that blocking *is* the clock, and it is a far more accurate one than a
 * `delay()` loop would be, because it is paced by the audio hardware rather than
 * by a scheduler that Doze can starve. It also keeps the alarm entirely out of
 * the coroutine machinery, so no dispatcher, cancellation scope, or test's
 * virtual time can silence it by accident.
 */
internal class AlarmTonePlayer(
    initialTone: AlarmTone,
    private val attributes: AudioAttributes
) : Thread("volty-alarm-tone") {

    /** The tone to play from the next burst onward. Written by [retune] from other threads. */
    @Volatile
    private var tone: AlarmTone = initialTone

    /** Cleared by [quit]; checked between write chunks so stopping is prompt. */
    @Volatile
    private var running: Boolean = true

    init {
        isDaemon = true
        // Above default but below the audio-callback tiers: an underrun in a
        // safety alarm is heard as a stutter, and this loop is the only thing
        // feeding it.
        priority = MAX_PRIORITY - 1
    }

    /**
     * Change pitch/rest without restarting anything — the `RETUNE` half of
     * `AlarmTransition`. Takes effect at the **start of the next burst**, never
     * mid-pulse, because changing frequency inside a pulse is a phase
     * discontinuity, which is a click.
     */
    fun retune(next: AlarmTone) {
        tone = next
    }

    /** Ask the loop to finish. Returns immediately; the thread releases its own track. */
    fun quit() {
        running = false
    }

    override fun run() {
        val track = buildTrack() ?: return
        try {
            track.play()
            // Rendered lazily and cached: at step 3 the pattern repeats twice a
            // second, and re-synthesising ~40 kB of PCM each time to play the
            // identical thing would be pure garbage generation.
            var rendered: AlarmTone? = null
            var burst = ShortArray(0)
            var rest = ShortArray(0)
            while (running) {
                val current = tone
                if (current != rendered) {
                    rendered = current
                    burst = AlarmToneRenderer.burst(current)
                    rest = AlarmToneRenderer.silence(current.restMs)
                }
                if (!writeChunked(track, burst)) break
                if (!writeChunked(track, rest)) break
            }
        } catch (t: Throwable) {
            // A dead alarm must not take the monitoring service down with it.
            Log.e(TAG, "alarm tone loop failed", t)
        } finally {
            // pause + flush before stop: stop() lets whatever is already buffered
            // drain, which after a `quit()` is up to a buffer of tone playing
            // after the alarm was supposed to be over.
            runCatching { track.pause() }
            runCatching { track.flush() }
            runCatching { track.stop() }
            runCatching { track.release() }
        }
    }

    /**
     * Write [data] in small chunks, giving up as soon as [running] clears.
     *
     * The chunking is what makes [quit] prompt: a whole burst is up to 380 ms and
     * a rest up to 1.25 s, so writing either in one call would leave the alarm
     * sounding for that long after the condition cleared. Returns false when the
     * loop should end.
     */
    private fun writeChunked(track: AudioTrack, data: ShortArray): Boolean {
        var offset = 0
        while (offset < data.size) {
            if (!running) return false
            val count = min(CHUNK_SAMPLES, data.size - offset)
            val written = track.write(data, offset, count)
            if (written <= 0) {
                Log.w(TAG, "alarm tone write returned $written; ending loop")
                return false
            }
            offset += written
        }
        return true
    }

    private fun buildTrack(): AudioTrack? = try {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(AlarmToneRenderer.SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bufferSizeBytes())
            .build()
    } catch (e: Exception) {
        Log.e(TAG, "could not open an AudioTrack for the alarm", e)
        null
    }

    /**
     * The device minimum, or [BUFFER_MS] worth, whichever is larger.
     *
     * `getMinBufferSize` returns a negative error code on a bad configuration;
     * `maxOf` against a positive figure absorbs that without a separate branch.
     */
    private fun bufferSizeBytes(): Int {
        val minBytes = AudioTrack.getMinBufferSize(
            AlarmToneRenderer.SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val wantedBytes = AlarmToneRenderer.samplesFor(BUFFER_MS, AlarmToneRenderer.SAMPLE_RATE) * BYTES_PER_SAMPLE
        return maxOf(minBytes, wantedBytes)
    }

    private companion object {
        const val TAG = "AudibleAlarm"

        /** 16-bit mono. */
        const val BYTES_PER_SAMPLE = 2

        /** Buffer depth. Deep enough to ride out a scheduling hiccup, shallow enough that stopping is quick. */
        const val BUFFER_MS = 200

        /** Write granularity — the upper bound on how long [quit] takes to be noticed. */
        val CHUNK_SAMPLES = AlarmToneRenderer.samplesFor(40, AlarmToneRenderer.SAMPLE_RATE)
    }
}
