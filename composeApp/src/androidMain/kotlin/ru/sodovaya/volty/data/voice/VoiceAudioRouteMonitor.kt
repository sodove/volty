package ru.sodovaya.volty.data.voice

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.twilio.audioswitch.AbstractAudioSwitch
import com.twilio.audioswitch.AudioDevice
import com.twilio.audioswitch.AudioSwitch
import com.twilio.audioswitch.CommDeviceAudioSwitch
import io.livekit.android.audio.AudioHandler
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.sodovaya.volty.domain.voice.*

/** Own AudioSwitch worker allows close to await actual focus/routing cleanup. */
internal class VoiceAudioRouteMonitor(
    private val context: Context,
    private val manager: AudioManager,
    private val preferences: VoiceAudioPreferences,
    private val diagnostics: VoiceDiagnostics,
    private val keeper: VoiceCommunicationKeeper,
    private val preferredInputId: Int?,
    private val outputAllowed: (Boolean) -> Unit,
    private val inputLost: () -> Unit,
) : AudioHandler {
    private val thread = HandlerThread("VoltyVoiceRouting").apply { start() }
    private val handler = Handler(thread.looper)
    private var audioSwitch: AbstractAudioSwitch? = null
    private var deviceRegistered = false
    private var recordingRegistered = false
    private var closed = false
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = observe()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            observe()
            if (preferredInputId != null && removedDevices.any { it.id == preferredInputId }) {
                // Never silently substitute another explicit microphone after unplugging.
                inputLost()
            }
        }
    }
    private val recordingCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) = observe()
    }

    override fun start() { handler.post {
        if (closed || audioSwitch != null) return@post
        try {
            val focus = AudioManager.OnAudioFocusChangeListener {
                diagnostics.update { snapshot -> snapshot.copy(audioFocus = it) }
                record(VoiceDiagnosticCode.AUDIO_FOCUS)
            }
            val priority = preferences.outputPriority().map { kind -> when (kind) {
                VoiceDeviceKind.SPEAKER -> AudioDevice.Speakerphone::class.java
                VoiceDeviceKind.BLUETOOTH -> AudioDevice.BluetoothHeadset::class.java
                VoiceDeviceKind.WIRED -> AudioDevice.WiredHeadset::class.java
                VoiceDeviceKind.EARPIECE -> AudioDevice.Earpiece::class.java
                VoiceDeviceKind.UNKNOWN -> error("Unknown priority kind")
            }
            }
            val switch: AbstractAudioSwitch = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                CommDeviceAudioSwitch(context, loggingEnabled = false, audioFocusChangeListener = focus, preferredDeviceList = priority)
            else AudioSwitch(context, loggingEnabled = false, audioFocusChangeListener = focus, preferredDeviceList = priority)
            audioSwitch = switch // Own it before any initialization can fail.
            switch.audioMode = AudioManager.MODE_IN_COMMUNICATION
            switch.audioAttributeUsageType = android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION
            switch.audioAttributeContentType = android.media.AudioAttributes.CONTENT_TYPE_SPEECH
            switch.start { _, selected ->
                val kind = when (selected) {
                    is AudioDevice.BluetoothHeadset -> VoiceDeviceKind.BLUETOOTH
                    is AudioDevice.WiredHeadset -> VoiceDeviceKind.WIRED
                    is AudioDevice.Speakerphone -> VoiceDeviceKind.SPEAKER
                    is AudioDevice.Earpiece -> VoiceDeviceKind.EARPIECE
                    else -> VoiceDeviceKind.UNKNOWN
                }
                diagnostics.update { it.copy(selectedOutput = kind) }
                val allowed = when (preferences.output) {
                    VoiceOutputPreference.HEADSET -> kind == VoiceDeviceKind.BLUETOOTH || kind == VoiceDeviceKind.WIRED
                    VoiceOutputPreference.SPEAKER -> kind == VoiceDeviceKind.SPEAKER
                    VoiceOutputPreference.AUTO -> true
                }
                outputAllowed(allowed)
                record(if (allowed) VoiceDiagnosticCode.ROUTE_CHANGED else VoiceDiagnosticCode.OUTPUT_UNAVAILABLE)
                observe()
            }
            switch.activate()
            manager.registerAudioDeviceCallback(deviceCallback, handler)
            deviceRegistered = true
            manager.registerAudioRecordingCallback(recordingCallback, handler)
            recordingRegistered = true
            keeper.start()
            observe()
        } catch (_: Exception) {
            outputAllowed(false)
            record(VoiceDiagnosticCode.OUTPUT_UNAVAILABLE)
        }
    } }

    override fun stop() { handler.post {
        try { stopOwned() } catch (_: Exception) { record(VoiceDiagnosticCode.CLEANUP_ERROR) }
    } }

    suspend fun close() {
        if (!thread.isAlive) {
            check(closed && !deviceRegistered && !recordingRegistered && audioSwitch == null)
            return
        }
        val completion = CompletableDeferred<Unit>()
        check(handler.post {
            try {
                stopOwned()
                keeper.close()
                closed = true
                completion.complete(Unit)
            } catch (error: Exception) { completion.completeExceptionally(VoiceEngineException(VoiceEngineError.CLEANUP)) }
        })
        completion.await()
        thread.quitSafely()
        withContext(Dispatchers.IO) { thread.join(1000) }
        check(!thread.isAlive)
    }

    private fun stopOwned() {
        keeper.stop()
        if (deviceRegistered) {
            manager.unregisterAudioDeviceCallback(deviceCallback)
            deviceRegistered = false
        }
        if (recordingRegistered) {
            manager.unregisterAudioRecordingCallback(recordingCallback)
            recordingRegistered = false
        }
        audioSwitch?.stop()
        audioSwitch = null
    }

    private fun observe() {
        try {
        diagnostics.update { it.copy(audioMode = manager.mode,
            communicationDeviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) manager.communicationDevice?.type else null,
            recordingDeviceTypes = manager.activeRecordingConfigurations.mapNotNull { config -> config.audioDevice?.type }.distinct()) }
        } catch (_: Exception) {
            diagnostics.update { it.copy(audioMode = null, communicationDeviceType = null, recordingDeviceTypes = emptyList()) }
            record(VoiceDiagnosticCode.ROUTE_OBSERVATION_ERROR)
        }
    }
    private fun record(code: VoiceDiagnosticCode) = diagnostics.record(SystemClock.elapsedRealtime(), code)
}
