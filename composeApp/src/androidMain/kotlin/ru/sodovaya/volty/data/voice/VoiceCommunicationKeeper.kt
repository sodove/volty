package ru.sodovaya.volty.data.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.SystemClock
import ru.sodovaya.volty.domain.voice.VoiceDiagnosticCode
import ru.sodovaya.volty.domain.voice.VoiceDiagnostics

/** Silent mode keeper, never counted as WebRTC render evidence. */
internal class VoiceCommunicationKeeper(private val diagnostics: VoiceDiagnostics) {
    private var active = false
    private var rendering = false
    private var disposed = false
    private var track: AudioTrack? = null

    @Synchronized fun start() { if (!disposed) { active = true; reconcile() } }
    @Synchronized fun stop() { active = false; reconcile() }
    @Synchronized fun onRender(running: Boolean) {
        rendering = running
        try { reconcile() } catch (_: Exception) {
            diagnostics.record(SystemClock.elapsedRealtime(), VoiceDiagnosticCode.KEEPALIVE_ERROR)
        }
    }
    @Synchronized fun close() {
        disposed = true
        active = false
        releaseTrack()
    }

    private fun reconcile() {
        if (disposed || Build.VERSION.SDK_INT < Build.VERSION_CODES.R || !active || rendering) {
            releaseTrack()
            return
        }
        if (track != null) return
        var allocated: AudioTrack? = null
        try {
            val silence = ByteArray(320) // 160 mono PCM16 frames at 16 kHz.
            allocated = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(16000)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(silence.size)
                .setTransferMode(AudioTrack.MODE_STATIC).build()
            check(allocated.state == AudioTrack.STATE_INITIALIZED)
            check(allocated.write(silence, 0, silence.size) == silence.size)
            check(allocated.setLoopPoints(0, 160, -1) == AudioTrack.SUCCESS)
            allocated.play()
            track = allocated
            diagnostics.record(SystemClock.elapsedRealtime(), VoiceDiagnosticCode.KEEPALIVE_STARTED)
        } catch (_: Exception) {
            try { allocated?.release() } catch (_: Exception) { track = allocated }
            diagnostics.record(SystemClock.elapsedRealtime(), VoiceDiagnosticCode.KEEPALIVE_ERROR)
        }
    }

    private fun releaseTrack() {
        val owned = track ?: return
        // Retain the reference if release throws, so cleanup can be retried.
        owned.release()
        track = null
        diagnostics.record(SystemClock.elapsedRealtime(), VoiceDiagnosticCode.KEEPALIVE_STOPPED)
    }
}
