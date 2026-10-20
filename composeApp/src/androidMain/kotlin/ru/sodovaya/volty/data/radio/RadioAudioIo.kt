package ru.sodovaya.volty.data.radio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Process
import android.util.Log
import ru.sodovaya.volty.domain.radio.RADIO_FRAME_SAMPLES

/**
 * Microphone and speaker for the radio: 16 kHz mono PCM16, 20 ms frames.
 *
 * One dedicated thread is paced by the blocking 20 ms [AudioRecord] read and calls `onFrame` with
 * each mic frame; `onFrame` runs the hub or client session tick on that same thread and calls
 * [play] with what to hear, so there are no extra timers and no cross-thread audio state.
 * [play] must only be called from inside `onFrame`.
 *
 * While running the [AudioManager] is in `MODE_IN_COMMUNICATION` (restored on [stop]) and, on
 * API 31+, a Bluetooth SCO / LE headset is selected as the communication device if one is present.
 * That is audio routing only; no Bluetooth connection is made or required.
 *
 * Framework audio cannot be unit-tested in this repo; verified only on a device.
 */
class RadioAudioIo(context: Context) {
    private val app = context.applicationContext ?: context
    private val audioManager = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private var echo: AcousticEchoCanceler? = null
    private var noise: NoiseSuppressor? = null
    private var thread: Thread? = null
    private var savedMode: Int? = null
    private var communicationDeviceSet = false
    private val silence = ShortArray(RADIO_FRAME_SAMPLES)

    @Volatile
    private var running = false

    /**
     * Opens the recorder and player and starts the audio thread. Returns false (with everything
     * released again) if the microphone is not granted or the recorder/player cannot be opened.
     */
    @Synchronized
    fun start(onFrame: (ShortArray) -> Unit): Boolean {
        if (running) return true
        if (app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
        return try {
            openAll()
            running = true
            thread = Thread({ loop(onFrame) }, "radio-audio").also { it.start() }
            true
        } catch (e: Exception) {
            Log.w(TAG, "audio start failed", e)
            releaseAll()
            false
        }
    }

    /** One 20 ms frame to the speaker, or silence for null. Audio thread only. */
    fun play(frame: ShortArray?) {
        val t = track ?: return
        val data = if (frame == null || frame.size != RADIO_FRAME_SAMPLES) silence else frame
        t.write(data, 0, data.size) // blocking; the track buffer is small, so this also keeps latency bounded
    }

    /** Stops the thread (joined with a timeout) and releases everything, restoring the audio mode. */
    @Synchronized
    fun stop() {
        running = false
        try {
            record?.stop() // unblocks a read in progress
        } catch (_: Exception) {
        }
        val t = thread
        thread = null
        if (t != null && t !== Thread.currentThread()) {
            try {
                t.join(JOIN_TIMEOUT_MILLIS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        releaseAll()
    }

    private fun openAll() {
        val format = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val minIn = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val recorder = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minIn, FRAME_BYTES * 4))
            .build()
        record = recorder
        check(recorder.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord not initialised" }

        if (AcousticEchoCanceler.isAvailable()) {
            echo = try { AcousticEchoCanceler.create(recorder.audioSessionId)?.also { it.enabled = true } } catch (_: Exception) { null }
        }
        if (NoiseSuppressor.isAvailable()) {
            noise = try { NoiseSuppressor.create(recorder.audioSessionId)?.also { it.enabled = true } } catch (_: Exception) { null }
        }

        val minOut = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val player = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(minOut, FRAME_BYTES * 2))
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        track = player
        check(player.state == AudioTrack.STATE_INITIALIZED) { "AudioTrack not initialised" }

        savedMode = audioManager.mode
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        routeToHeadset()

        recorder.startRecording()
        player.play()
    }

    private fun routeToHeadset() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        try {
            val device = audioManager.availableCommunicationDevices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
            }
            if (device != null) communicationDeviceSet = audioManager.setCommunicationDevice(device)
        } catch (e: Exception) {
            Log.w(TAG, "communication device not set", e)
        }
    }

    private fun loop(onFrame: (ShortArray) -> Unit) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val recorder = record ?: return
        while (running) {
            val frame = ShortArray(RADIO_FRAME_SAMPLES)
            var filled = 0
            while (filled < frame.size && running) {
                val n = recorder.read(frame, filled, frame.size - filled)
                if (n < 0) return // recorder stopped or failed
                filled += n
            }
            if (!running) return
            try {
                onFrame(frame)
            } catch (e: Exception) {
                // One bad tick must never end the audio thread.
                Log.w(TAG, "radio tick failed", e)
            }
        }
    }

    private fun releaseAll() {
        try { echo?.release() } catch (_: Exception) {}
        try { noise?.release() } catch (_: Exception) {}
        echo = null
        noise = null
        record?.let {
            try { it.stop() } catch (_: Exception) {}
            try { it.release() } catch (_: Exception) {}
        }
        record = null
        track?.let {
            try { it.stop() } catch (_: Exception) {}
            try { it.release() } catch (_: Exception) {}
        }
        track = null
        if (communicationDeviceSet && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try { audioManager.clearCommunicationDevice() } catch (_: Exception) {}
        }
        communicationDeviceSet = false
        savedMode?.let { try { audioManager.mode = it } catch (_: Exception) {} }
        savedMode = null
    }

    private companion object {
        const val TAG = "RadioAudio"
        const val SAMPLE_RATE = 16_000
        const val FRAME_BYTES = RADIO_FRAME_SAMPLES * 2
        const val JOIN_TIMEOUT_MILLIS = 1_000L
    }
}
