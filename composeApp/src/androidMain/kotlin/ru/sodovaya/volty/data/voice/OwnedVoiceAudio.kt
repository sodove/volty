package ru.sodovaya.volty.data.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.SystemClock
import livekit.org.webrtc.audio.JavaAudioDeviceModule
import ru.sodovaya.volty.domain.voice.*

/** Owns the injected ADM; LiveKit explicitly does not release injected modules. */
internal class OwnedVoiceAudio(
    context: Context,
    private val tag: VoiceSessionTag,
    private val diagnostics: VoiceDiagnostics,
    private val emit: (VoiceSessionEvent) -> Unit,
    private val keeper: VoiceCommunicationKeeper,
) {
    private var released = false
    private val evidenceLock = Any()
    private val mediaEvidence = VoiceNativeAudioEvidence(tag)
    private val capture = OwnedVoiceCapture(diagnostics, ::captureState, ::fail)
    val preferredInputId: Int? get() = capture.preferredInputId
    @Volatile var phoneCapture = false
        private set
    @Volatile private var playbackMuted = false
    @Volatile private var sdkRendering = false
    private val playback = PhoneVoicePlayback(context.applicationContext, context.getSystemService(Context.AUDIO_SERVICE) as AudioManager,
        diagnostics, ::renderState, { fail(VoiceDiagnosticCode.TRACK_RUNTIME_ERROR) })
    val module: JavaAudioDeviceModule = JavaAudioDeviceModule.builder(context)
        .setInputSampleRate(48000)
        .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        // SDK has no AudioRecord to install hardware effects on; retain WebRTC software processing.
        .setUseHardwareAcousticEchoCanceler(false)
        .setUseHardwareNoiseSuppressor(false)
        .setAudioBufferCallback { buffer, format, channels, rate, _, _ -> capture.fill(buffer, format, channels, rate) }
        .setPlaybackSamplesReadyCallback { samples -> if (!phoneCapture) diagnostics.addRenderBytes(samples.data.size) }
        .setAudioRecordStateCallback(object : JavaAudioDeviceModule.AudioRecordStateCallback {
            override fun onWebRtcAudioRecordStart() {
                // Internal SDK recording is disabled; only our AudioRecord proves capture.
            }
            override fun onWebRtcAudioRecordStop() {
                capture.stop()
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
        .createAudioDeviceModule().also { it.setAudioRecordEnabled(false) }

    fun muteMicrophone(value: Boolean) { capture.mute(value); module.setMicrophoneMute(value) }
    fun mutePlayback(value: Boolean) { playbackMuted = value; playback.mute(value); module.setSpeakerMute(phoneCapture || value) }
    fun remoteTracks(tracks: List<io.livekit.android.room.track.RemoteAudioTrack>) = playback.reconcile(tracks)
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
                phoneCapture = phone
                if (changed && !phone) renderState(sdkRendering)
            }
            return result
        } finally {
            module.setSpeakerMute(phoneCapture || playbackMuted)
        }
    }

    /** Stop owned callbacks before SDK release waits for its capture/RTC threads. */
    fun stopNative() { capture.close(); playback.close() }

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

    /** Request on the owned AudioRecord. Actual routedDevice is checked on every PCM frame. */
    @Synchronized fun requestInput(manager: AudioManager, preference: VoiceInputPreference): VoiceAudioChangeResult {
        if (released) return VoiceAudioChangeResult.STALE
        return try { capture.request(manager, preference) } catch (_: Exception) { VoiceAudioChangeResult.FAILED }
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
}
