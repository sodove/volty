package ru.sodovaya.volty.data.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
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
    var preferredInputId: Int? = null
        private set
    val module: JavaAudioDeviceModule = JavaAudioDeviceModule.builder(context)
        .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setUseHardwareAcousticEchoCanceler(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        .setUseHardwareNoiseSuppressor(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        .setSamplesReadyCallback { samples -> diagnostics.addCaptureBytes(samples.data.size) }
        .setPlaybackSamplesReadyCallback { samples -> diagnostics.addRenderBytes(samples.data.size) }
        .setAudioRecordStateCallback(object : JavaAudioDeviceModule.AudioRecordStateCallback {
            override fun onWebRtcAudioRecordStart() {
                captureState(true)
            }
            override fun onWebRtcAudioRecordStop() {
                captureState(false)
            }
        })
        .setAudioTrackStateCallback(object : JavaAudioDeviceModule.AudioTrackStateCallback {
            override fun onWebRtcAudioTrackStart() {
                renderState(true)
            }
            override fun onWebRtcAudioTrackStop() {
                renderState(false)
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

    /** A void ADM preference request cannot prove actual capture routing. No null public setter. */
    @Synchronized fun requestInput(manager: AudioManager, preference: VoiceInputPreference): VoiceAudioChangeResult {
        if (released) return VoiceAudioChangeResult.STALE
        if (preference == VoiceInputPreference.AUTO) return if (preferredInputId == null)
            VoiceAudioChangeResult.REQUESTED else VoiceAudioChangeResult.REQUIRES_RECONNECT
        val device = manager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull {
            when (preference) {
                VoiceInputPreference.PHONE -> it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC
                VoiceInputPreference.HEADSET -> isHeadsetInput(it.type)
                VoiceInputPreference.AUTO -> false
            }
        } ?: return VoiceAudioChangeResult.UNAVAILABLE
        return try {
            module.setPreferredInputDevice(device)
            preferredInputId = device.id
            VoiceAudioChangeResult.REQUESTED
        } catch (_: Exception) { VoiceAudioChangeResult.FAILED }
    }

    @Synchronized fun close() {
        if (released) return
        // Never hold evidenceLock through release: native release can join a
        // callback thread which must acquire that lock before it can retire.
        synchronized(evidenceLock) { mediaEvidence.close() }
        module.release()
        released = true
    }
    private fun record(code: VoiceDiagnosticCode) = diagnostics.record(SystemClock.elapsedRealtime(), code)
    private fun fail(code: VoiceDiagnosticCode) { record(code); emit(VoiceSessionEvent.EngineFailed(tag)) }
    private fun isHeadsetInput(type: Int): Boolean = type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
        type == AudioDeviceInfo.TYPE_USB_HEADSET ||
        type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && type == AudioDeviceInfo.TYPE_BLE_HEADSET)
}
