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
                diagnostics.update { it.copy(captureRunning = true) }
                record(VoiceDiagnosticCode.CAPTURE_STARTED)
                emit(VoiceSessionEvent.CaptureStarted(tag))
            }
            override fun onWebRtcAudioRecordStop() {
                diagnostics.update { it.copy(captureRunning = false) }
                record(VoiceDiagnosticCode.CAPTURE_STOPPED)
                emit(VoiceSessionEvent.CaptureStopped(tag))
            }
        })
        .setAudioTrackStateCallback(object : JavaAudioDeviceModule.AudioTrackStateCallback {
            override fun onWebRtcAudioTrackStart() {
                diagnostics.update { it.copy(renderRunning = true) }
                keeper.onRender(true)
                record(VoiceDiagnosticCode.RENDER_STARTED)
                emit(VoiceSessionEvent.RenderStarted(tag))
            }
            override fun onWebRtcAudioTrackStop() {
                diagnostics.update { it.copy(renderRunning = false) }
                keeper.onRender(false)
                record(VoiceDiagnosticCode.RENDER_STOPPED)
                emit(VoiceSessionEvent.RenderStopped(tag))
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

    fun applyInput(manager: AudioManager, preference: VoiceInputPreference) {
        if (preference == VoiceInputPreference.AUTO) return // Fresh ADM, never null setter.
        val device = manager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull {
            when (preference) {
                VoiceInputPreference.PHONE -> it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC
                VoiceInputPreference.HEADSET -> isHeadsetInput(it.type)
                VoiceInputPreference.AUTO -> false
            }
        }
        if (device == null) {
            fail(VoiceDiagnosticCode.INPUT_UNAVAILABLE)
            throw VoiceEngineException(VoiceEngineError.CONNECTION)
        }
        module.setPreferredInputDevice(device)
        preferredInputId = device.id
    }

    @Synchronized fun close() {
        if (released) return
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
