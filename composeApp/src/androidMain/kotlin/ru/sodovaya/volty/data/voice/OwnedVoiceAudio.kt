package ru.sodovaya.volty.data.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.MediaRecorder
import android.os.Build
import android.os.Process
import android.os.SystemClock
import java.nio.ByteBuffer
import livekit.org.webrtc.audio.JavaAudioDeviceModule
import livekit.org.webrtc.audio.resetVoltyPreferredInput
import ru.sodovaya.volty.domain.voice.*

/** Owns the injected ADM; LiveKit explicitly does not release injected modules. */
internal class OwnedVoiceAudio(
    context: Context,
    private val tag: VoiceSessionTag,
    private val diagnostics: VoiceDiagnostics,
    private val emit: (VoiceSessionEvent) -> Unit,
    private val keeper: VoiceCommunicationKeeper,
    /** Settings at session start: the phone's noise suppressor is fixed when the audio module is built. */
    initialMic: ru.sodovaya.volty.domain.audio.MicProcessingSettings = ru.sodovaya.volty.domain.audio.MicProcessingSettings(),
) {
    private var released = false
    private val evidenceLock = Any()
    private val mediaEvidence = VoiceNativeAudioEvidence(tag)
    private val capture = OwnedVoiceCapture(diagnostics, ::captureState, ::fail, initialMic.phoneNoiseSuppressor)
    private val captureLock = Any()
    private var sdkInput: AudioDeviceInfo? = null
    val preferredInputId: Int? get() = synchronized(captureLock) { if (phoneCapture) capture.preferredInputId else sdkInput?.id }
    private val captureHandoff = VoiceCaptureHandoff()
    private var callbackMaxMicros = 0L
    private var nextCaptureSample = 0L
    private var levelBytes = ByteArray(0)
    private var nativeStopped = false
    @Volatile var phoneCapture = false
        private set
    /** Built off the audio thread by [setMicProcessing]; the callback reads the reference once per frame. */
    @Volatile private var micStage: MicGainStage? = null
    @Volatile private var playbackMuted = false
    @Volatile private var sdkRendering = false
    private val playback = PhoneVoicePlayback(context.applicationContext, context.getSystemService(Context.AUDIO_SERVICE) as AudioManager,
        diagnostics, ::renderState, { fail(VoiceDiagnosticCode.TRACK_RUNTIME_ERROR) })
    val module: JavaAudioDeviceModule = JavaAudioDeviceModule.builder(context)
        .setInputSampleRate(48000)
        .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        // Retraction: earlier revisions replaced capture in every mode. Only PHONE
        // needs a separate recorder; ordinary calls retain SDK pacing and shared render.
        // Software WebRTC processing remains enabled for both paths.
        .setUseHardwareAcousticEchoCanceler(false)
        // The vendor suppressor is opt-in (quality varies by phone); WebRTC's own stays on either way.
        .setUseHardwareNoiseSuppressor(initialMic.phoneNoiseSuppressor && JavaAudioDeviceModule.isBuiltInNoiseSuppressorSupported())
        .setAudioBufferCallback { buffer, format, channels, rate, bytesRead, timestamp ->
            synchronized(captureLock) {
                val started = System.nanoTime()
                val result = when {
                    nativeStopped -> {
                        silence(buffer)
                        if (bytesRead != buffer.capacity()) SystemClock.sleep(10)
                        timestamp
                    }
                    !captureHandoff.permitsFrame() -> {
                        // One callback may finish an in-flight SDK read. The next SDK
                        // loop releases its AudioRecord before our recorder may start.
                        silence(buffer)
                        SystemClock.sleep(10)
                        started
                    }
                    phoneCapture -> capture.fill(buffer, format, channels, rate).also { applyMic(buffer, format, channels, rate) }
                    else -> {
                        // bytesRead is the SDK's actual native read result, unlike buffer
                        // capacity (the disabled SDK also supplies a full zero buffer).
                        if (bytesRead == buffer.capacity()) {
                            applyMic(buffer, format, channels, rate)
                            diagnostics.addCaptureBytes(bytesRead)
                            if (diagnostics.state.value.captureRunning != true) captureState(true)
                        } else {
                            // An SDK init/start failure can leave its callback alive with
                            // AudioRecord disabled until session cleanup catches up.
                            silence(buffer)
                            SystemClock.sleep(10)
                        }
                        timestamp
                    }
                }
                sampleCapture(buffer, format, started)
                result
            }
        }
        .setPlaybackSamplesReadyCallback { samples -> if (!phoneCapture) diagnostics.addRenderBytes(samples.data.size) }
        .setAudioRecordStateCallback(object : JavaAudioDeviceModule.AudioRecordStateCallback {
            override fun onWebRtcAudioRecordStart() {
                // Native reads in the buffer callback also cover dynamic SDK re-enable.
            }
            override fun onWebRtcAudioRecordStop() {
                synchronized(captureLock) { capture.stop(); captureState(false) }
            }
        })
        .setAudioTrackStateCallback(object : JavaAudioDeviceModule.AudioTrackStateCallback {
            override fun onWebRtcAudioTrackStart() {
                sdkRendering = true
                if (!phoneCapture) renderState(true)
            }
            override fun onWebRtcAudioTrackStop() {
                sdkRendering = false
                if (!phoneCapture) renderState(false)
            }
        })
        .setAudioRecordErrorCallback(object : JavaAudioDeviceModule.AudioRecordErrorCallback {
            override fun onWebRtcAudioRecordInitError(errorMessage: String?) = fail(VoiceDiagnosticCode.RECORD_INIT_ERROR)
            override fun onWebRtcAudioRecordStartError(errorCode: JavaAudioDeviceModule.AudioRecordStartErrorCode?, errorMessage: String?) = fail(VoiceDiagnosticCode.RECORD_START_ERROR)
            override fun onWebRtcAudioRecordError(errorMessage: String?) = fail(VoiceDiagnosticCode.RECORD_RUNTIME_ERROR)
        })
        .setAudioTrackErrorCallback(object : JavaAudioDeviceModule.AudioTrackErrorCallback {
            override fun onWebRtcAudioTrackInitError(errorMessage: String?) = fail(VoiceDiagnosticCode.TRACK_INIT_ERROR)
            override fun onWebRtcAudioTrackStartError(errorCode: JavaAudioDeviceModule.AudioTrackStartErrorCode?, errorMessage: String?) = fail(VoiceDiagnosticCode.TRACK_START_ERROR)
            override fun onWebRtcAudioTrackError(errorMessage: String?) = fail(VoiceDiagnosticCode.TRACK_RUNTIME_ERROR)
        })
        .createAudioDeviceModule()

    /** Installs a fresh stage (new limiter state); safe from any thread. */
    fun setMicProcessing(settings: ru.sodovaya.volty.domain.audio.MicProcessingSettings) {
        micStage = MicGainStage(settings, MIC_SAMPLE_RATE)
        // The RNNoise state is built here, off the audio thread; the swap and the free of the old one
        // happen under captureLock, so the callback never runs on a freed native handle.
        val next = if (!settings.speechGate) null else RnnoiseVad.createOrNull()?.let { vad ->
            SpeechStage(vad, ru.sodovaya.volty.domain.voice.SpeechGate(settings.speechGateThreshold))
        }
        synchronized(captureLock) {
            // A change that lands after stopNative must not leave a native state nobody frees.
            if (nativeStopped) { next?.vad?.close(); return }
            speech?.vad?.close()
            speech = next
        }
    }

    private class SpeechStage(val vad: RnnoiseVad, val gate: ru.sodovaya.volty.domain.voice.SpeechGate) {
        val frame = ShortArray(RnnoiseVad.FRAME_SIZE)
    }
    /** Read and replaced only under captureLock. */
    private var speech: SpeechStage? = null

    /** Audio thread, under captureLock, real captured frames only. Allocation-free. */
    private fun applyMic(buffer: ByteBuffer, format: Int, channels: Int, rate: Int) {
        if (format != AudioFormat.ENCODING_PCM_16BIT) return
        applyGain(buffer)
        applySpeechGate(buffer, channels, rate)
    }

    private fun applyGain(buffer: ByteBuffer) {
        val stage = micStage ?: return
        var i = 0
        val end = buffer.capacity() - 1
        while (i < end) {
            // Little-endian PCM16; the high byte is read signed, the low byte unsigned.
            val v = (buffer.get(i).toInt() and 0xFF) or (buffer.get(i + 1).toInt() shl 8)
            val out = stage.processSample(v)
            buffer.put(i, out.toByte())
            buffer.put(i + 1, (out shr 8).toByte())
            i += 2
        }
    }

    /** RNNoise only judges the frame; the gate mutes non-speech, 20 ms behind. 10 ms mono 48 kHz frames only. */
    private fun applySpeechGate(buffer: ByteBuffer, channels: Int, rate: Int) {
        val stage = speech ?: return
        if (channels != 1 || rate != MIC_SAMPLE_RATE || buffer.capacity() != RnnoiseVad.FRAME_SIZE * 2) return
        val frame = stage.frame
        for (n in 0 until RnnoiseVad.FRAME_SIZE) {
            frame[n] = ((buffer.get(2 * n).toInt() and 0xFF) or (buffer.get(2 * n + 1).toInt() shl 8)).toShort()
        }
        stage.gate.process(frame, stage.vad.probability(frame))
        for (n in 0 until RnnoiseVad.FRAME_SIZE) {
            val v = frame[n].toInt()
            buffer.put(2 * n, v.toByte())
            buffer.put(2 * n + 1, (v shr 8).toByte())
        }
    }

    fun muteMicrophone(value: Boolean) {
        capture.mute(value); module.setMicrophoneMute(value)
        diagnostics.update { it.copy(microphoneMuted = value) }
    }
    fun mutePlayback(value: Boolean) {
        playbackMuted = value; playback.mute(value); module.setSpeakerMute(phoneCapture || value)
        diagnostics.update { it.copy(playbackMuted = value) }
    }
    fun remoteTracks(tracks: List<io.livekit.android.room.track.RemoteAudioTrack>,
                     gains: Map<io.livekit.android.room.track.RemoteAudioTrack, Double> = emptyMap()) =
        playback.reconcile(if (phoneCapture) tracks else emptyList(), gains)
    fun samplePlayback() = playback.sampleDiagnostics()
    fun playbackAvailable(preference: VoiceAudioPreferences): Boolean =
        playback.available(preference.input == VoiceInputPreference.PHONE, preference.output)

    fun configurePlayback(preference: VoiceAudioPreferences): VoiceAudioChangeResult {
        val phone = preference.input == VoiceInputPreference.PHONE
        // Fence SDK playback before an owned sink can start rendering PHONE PCM.
        module.setSpeakerMute(true)
        try {
            val result = playback.configure(phone, preference.output)
            if (result == VoiceAudioChangeResult.REQUESTED) {
                val changed = phone != phoneCapture
                synchronized(captureLock) {
                    if (changed) {
                        capture.stop()
                        captureState(false)
                        nextCaptureSample = 0L
                    }
                    // Do not enable native capture until requestInput applies the
                    // new preference. Gate also discards a possible in-flight read.
                    module.setAudioRecordEnabled(false)
                    captureHandoff.request(voiceAudioCapturePath(preference.input))
                    phoneCapture = phone
                    diagnostics.update { it.copy(capturePath = voiceAudioCapturePath(preference.input),
                        ownedCaptureDeviceType = null, captureRms = null, capturePeak = null) }
                }
                if (changed && !phone) renderState(sdkRendering)
            }
            return result
        } finally {
            module.setSpeakerMute(phoneCapture || playbackMuted)
        }
    }

    /** Stop owned callbacks before SDK release waits for its capture/RTC threads. */
    fun stopNative() {
        synchronized(captureLock) { nativeStopped = true; capture.close(); speech?.vad?.close(); speech = null }
        playback.close()
        playback.sampleDiagnostics()
    }

    /** Reset and enqueue under the same boundary as native start/stop callbacks. */
    fun reconnecting(announce: () -> Unit) = synchronized(evidenceLock) {
        mediaEvidence.reconnecting(diagnostics.state.value)
        announce()
    }

    fun refreshMediaEvidence() = synchronized(evidenceLock) {
        for (event in mediaEvidence.observe(diagnostics.state.value)) emitMediaEvent(event)
    }

    private fun captureState(started: Boolean) = synchronized(evidenceLock) {
        // Actual hardware callbacks remain diagnostic facts during release,
        // even after this owner is fenced from emitting session evidence.
        diagnostics.update { it.copy(captureRunning = started) }
        if (mediaEvidence.captureState(started, diagnostics.state.value)) {
            emitMediaEvent(if (started) VoiceSessionEvent.CaptureStarted(tag) else VoiceSessionEvent.CaptureStopped(tag))
        } else record(if (started) VoiceDiagnosticCode.CAPTURE_STARTED else VoiceDiagnosticCode.CAPTURE_STOPPED)
    }

    private fun renderState(started: Boolean) = synchronized(evidenceLock) {
        diagnostics.update { it.copy(renderRunning = started) }
        if (mediaEvidence.renderState(started, diagnostics.state.value)) {
            emitMediaEvent(if (started) VoiceSessionEvent.RenderStarted(tag) else VoiceSessionEvent.RenderStopped(tag))
        } else record(if (started) VoiceDiagnosticCode.RENDER_STARTED else VoiceDiagnosticCode.RENDER_STOPPED)
    }

    /** Called only inside evidenceLock, including nonblocking event enqueue. */
    private fun emitMediaEvent(event: VoiceSessionEvent) {
        val code = when (event) {
            is VoiceSessionEvent.CaptureStarted -> VoiceDiagnosticCode.CAPTURE_STARTED
            is VoiceSessionEvent.CaptureStopped -> VoiceDiagnosticCode.CAPTURE_STOPPED
            is VoiceSessionEvent.RenderStarted -> { keeper.onRender(true); VoiceDiagnosticCode.RENDER_STARTED }
            is VoiceSessionEvent.RenderStopped -> { keeper.onRender(false); VoiceDiagnosticCode.RENDER_STOPPED }
            else -> error("Unexpected native audio evidence")
        }
        record(code)
        emit(event)
    }

    fun applyInput(manager: AudioManager, preference: VoiceInputPreference) {
        if (requestInput(manager, preference) != VoiceAudioChangeResult.REQUESTED) {
            fail(VoiceDiagnosticCode.INPUT_UNAVAILABLE)
            throw VoiceEngineException(VoiceEngineError.CONNECTION)
        }
    }

    /** PHONE checks its owned routedDevice every frame; stock SDK input remains a request. */
    @Synchronized fun requestInput(manager: AudioManager, preference: VoiceInputPreference): VoiceAudioChangeResult {
        if (released) return VoiceAudioChangeResult.STALE
        return try {
            synchronized(captureLock) {
                if (preference == VoiceInputPreference.PHONE) {
                    val result = capture.request(manager, preference)
                    if (result == VoiceAudioChangeResult.REQUESTED) captureHandoff.applied()
                    return@synchronized result
                }
                val selected = if (preference == VoiceInputPreference.AUTO) null else
                    manager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
                    }
                if (preference != VoiceInputPreference.AUTO && selected == null) return@synchronized VoiceAudioChangeResult.UNAVAILABLE
                if (selected == null) module.resetVoltyPreferredInput() else module.setPreferredInputDevice(selected)
                sdkInput = selected
                captureHandoff.applied()
                module.setAudioRecordEnabled(true)
                VoiceAudioChangeResult.REQUESTED
            }
        } catch (_: Exception) { VoiceAudioChangeResult.FAILED }
    }

    /** One numeric PCM sample per second; no PCM retention, file I/O or routing queries here. */
    private fun sampleCapture(buffer: ByteBuffer, format: Int, started: Long) {
        callbackMaxMicros = maxOf(callbackMaxMicros, (System.nanoTime() - started) / 1000)
        if (started < nextCaptureSample || nativeStopped) return
        nextCaptureSample = started + 1_000_000_000L
        val level = if (format == AudioFormat.ENCODING_PCM_16BIT && buffer.capacity() % 2 == 0) {
            if (levelBytes.size != buffer.capacity()) levelBytes = ByteArray(buffer.capacity())
            buffer.duplicate().apply { clear() }.get(levelBytes)
            voicePcm16Level(levelBytes).also { levelBytes.fill(0) }
        } else null
        diagnostics.update { it.copy(captureThreadPriority = Process.getThreadPriority(Process.myTid()),
            captureCallbackMaxMicros = callbackMaxMicros, captureRms = level?.rms, capturePeak = level?.peak) }
    }

    private fun silence(buffer: ByteBuffer) {
        buffer.clear()
        while (buffer.hasRemaining()) buffer.put(0.toByte())
        buffer.rewind()
    }

    @Synchronized fun close() {
        if (released) return
        // Never hold evidenceLock through release: native release can join a
        // callback thread which must acquire that lock before it can retire.
        synchronized(evidenceLock) { mediaEvidence.close() }
        stopNative()
        module.release()
        released = true
    }
    private fun record(code: VoiceDiagnosticCode) = diagnostics.record(SystemClock.elapsedRealtime(), code)
    private fun fail(code: VoiceDiagnosticCode) { record(code); emit(VoiceSessionEvent.EngineFailed(tag)) }
    private companion object { const val MIC_SAMPLE_RATE = 48_000 }
}
