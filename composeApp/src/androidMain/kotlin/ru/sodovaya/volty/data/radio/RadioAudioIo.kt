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
import ru.sodovaya.volty.domain.radio.RadioDeviceKind
import ru.sodovaya.volty.domain.radio.RadioOutputChoice
import ru.sodovaya.volty.domain.radio.radioAudioPlan
import ru.sodovaya.volty.domain.voice.VoiceAudioPreferences

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
 * With the voice setting "Phone microphone" none of that happens: the built-in mic is recorded and
 * the radio plays as media, so a Bluetooth headset gets it over A2DP (see [radioAudioPlan]).
 *
 * Framework audio cannot be unit-tested in this repo; verified only on a device.
 */
class RadioAudioIo(
    context: Context,
    /** The rider's server-voice microphone/output settings, read at each start. */
    private val preferences: () -> VoiceAudioPreferences = { VoiceAudioPreferences() },
) {
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
    private val failed = java.util.concurrent.atomic.AtomicBoolean(false)
    private var speakerphoneWas: Boolean? = null

    /**
     * Opens the recorder and player and starts the audio thread. Returns false (with everything
     * released again) if the microphone is not granted or the recorder/player cannot be opened.
     */
    @Synchronized
    fun start(onFailure: () -> Unit = {}, onFrame: (ShortArray) -> Unit): Boolean {
        if (running) return true
        if (app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
        return try {
            openAll()
            running = true
            failed.set(false)
            thread = Thread({ loop(onFrame, onFailure) }, "radio-audio").also { it.start() }
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
        // Non-blocking (ruling R18): if the track is full the frame is dropped, so the mic read is the only clock.
        val n = t.write(data, 0, data.size, AudioTrack.WRITE_NON_BLOCKING)
        if (n < 0) throw IllegalStateException("AudioTrack write failed: $n")
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
        val plan = radioAudioPlan(preferences(), presentDevices())
        val format = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val minIn = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val recorder = AudioRecord.Builder()
            // Same source as server voice's phone capture: VOICE_COMMUNICATION may still be pulled to the SCO mic.
            .setAudioSource(if (plan.phoneMic) MediaRecorder.AudioSource.VOICE_RECOGNITION else MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minIn, FRAME_BYTES * 4))
            .build()
        record = recorder
        check(recorder.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord not initialised" }
        if (plan.phoneMic) {
            audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
                ?.let { recorder.setPreferredDevice(it) }
        }

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
                    .setUsage(if (plan.phoneMic) AudioAttributes.USAGE_MEDIA else AudioAttributes.USAGE_VOICE_COMMUNICATION)
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

        if (plan.phoneMic) {
            // Media follows the system route (the headset when connected); only the speaker needs asking for.
            if (plan.output == RadioOutputChoice.BUILT_IN_SPEAKER) {
                audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    ?.let { player.setPreferredDevice(it) }
            }
        } else {
            savedMode = audioManager.mode
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            routeTo(plan.output)
        }

        recorder.startRecording()
        check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "AudioRecord did not start" }
        player.play()
    }

    private fun presentDevices(): Set<RadioDeviceKind> {
        val present = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).mapTo(HashSet()) { kindOf(it.type) }
        // On API 31+ a connected Bluetooth headset may show up in the output list only as A2DP, while the
        // communication-device list names its SCO / LE endpoint: look at both so it is not mistaken for "no headset".
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                audioManager.availableCommunicationDevices.mapTo(present) { kindOf(it.type) }
            } catch (e: Exception) {
                Log.w(TAG, "communication devices unreadable", e)
            }
        }
        return present
    }

    /**
     * Applies the output picked by [radioAudioPlan] (rulings R17/R19): a Bluetooth headset when present,
     * a wired or USB headset left in charge, and the loudspeaker when nothing is plugged in or the
     * rider's voice setting asks for it.
     */
    private fun routeTo(choice: RadioOutputChoice) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            // No SCO is started below API 31, so a wired headset can only be kept by not forcing the speaker.
            if (choice == RadioOutputChoice.BUILT_IN_SPEAKER) {
                @Suppress("DEPRECATION")
                run {
                    speakerphoneWas = audioManager.isSpeakerphoneOn
                    audioManager.isSpeakerphoneOn = true
                }
            }
            return
        }
        try {
            val wanted: (AudioDeviceInfo) -> Boolean = when (choice) {
                RadioOutputChoice.BLUETOOTH_HEADSET -> { d -> kindOf(d.type).let { it == RadioDeviceKind.BLUETOOTH_SCO || it == RadioDeviceKind.BLE_HEADSET } }
                RadioOutputChoice.WIRED_HEADSET -> { d ->
                    kindOf(d.type).let { it == RadioDeviceKind.WIRED_HEADSET || it == RadioDeviceKind.WIRED_HEADPHONES || it == RadioDeviceKind.USB_HEADSET }
                }
                RadioOutputChoice.BUILT_IN_SPEAKER -> { d -> d.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER } // the default earpiece is useless on a bike
            }
            val target = audioManager.availableCommunicationDevices.firstOrNull(wanted)
            if (target != null) communicationDeviceSet = audioManager.setCommunicationDevice(target)
        } catch (e: Exception) {
            Log.w(TAG, "communication device not set", e)
        }
    }

    private fun kindOf(type: Int): RadioDeviceKind = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> RadioDeviceKind.BLUETOOTH_SCO
        AudioDeviceInfo.TYPE_BLE_HEADSET -> RadioDeviceKind.BLE_HEADSET
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> RadioDeviceKind.WIRED_HEADSET
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> RadioDeviceKind.WIRED_HEADPHONES
        AudioDeviceInfo.TYPE_USB_HEADSET -> RadioDeviceKind.USB_HEADSET
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> RadioDeviceKind.BUILTIN_SPEAKER
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> RadioDeviceKind.EARPIECE
        else -> RadioDeviceKind.OTHER
    }

    private fun reportFailure(onFailure: () -> Unit) {
        if (running && failed.compareAndSet(false, true)) onFailure()
    }

    private fun loop(onFrame: (ShortArray) -> Unit, onFailure: () -> Unit) {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val recorder = record ?: return
            var consecutiveErrors = 0
            while (running) {
                val frame = ShortArray(RADIO_FRAME_SAMPLES)
                var filled = 0
                while (filled < frame.size && running) {
                    val n = recorder.read(frame, filled, frame.size - filled)
                    if (n < 0) return reportFailure(onFailure) // dead object, bad value, ...
                    filled += n
                }
                if (!running) return
                try {
                    onFrame(frame)
                    consecutiveErrors = 0
                } catch (e: Exception) {
                    Log.w(TAG, "radio tick failed", e)
                    if (++consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) return reportFailure(onFailure)
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "radio audio thread died", t)
            reportFailure(onFailure)
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
        speakerphoneWas?.let {
            @Suppress("DEPRECATION")
            try { audioManager.isSpeakerphoneOn = it } catch (_: Exception) {}
        }
        speakerphoneWas = null
        savedMode?.let { try { audioManager.mode = it } catch (_: Exception) {} }
        savedMode = null
    }

    private companion object {
        const val TAG = "RadioAudio"
        const val SAMPLE_RATE = 16_000
        const val FRAME_BYTES = RADIO_FRAME_SAMPLES * 2
        const val JOIN_TIMEOUT_MILLIS = 1_000L
        const val MAX_CONSECUTIVE_ERRORS = 25
    }
}
