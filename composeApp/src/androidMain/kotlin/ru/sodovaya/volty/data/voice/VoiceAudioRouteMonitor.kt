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
import kotlinx.coroutines.NonCancellable
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
    private val preferredInputId: () -> Int?,
    private val outputAllowed: (Boolean) -> Unit,
    private val inputLost: () -> Unit,
) : AudioHandler {
    private val thread = HandlerThread("VoltyVoiceRouting").apply { start() }
    private val handler = Handler(thread.looper)
    private var audioSwitch: AbstractAudioSwitch? = null
    private var deviceRegistered = false
    private var recordingRegistered = false
    private var closed = false
    @Volatile private var desiredOutput = preferences.output
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = observe()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            observe()
            val requestedInput = preferredInputId()
            if (requestedInput != null && removedDevices.any { it.id == requestedInput }) {
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
                val kind = outputKind(selected)
                diagnostics.update { it.copy(selectedOutput = kind) }
                val allowed = VoiceAudioPreferences(output=desiredOutput).permitsOutput(kind)
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

    /** All native requests finish on the owned worker before their producer can retire. */
    suspend fun configureOutputAndInput(output: VoiceOutputPreference, requestInput: () -> VoiceAudioChangeResult,
                                      currentRequest: () -> Boolean): VoiceAudioChangeResult {
        val completion = CompletableDeferred<VoiceAudioChangeResult>()
        if (!handler.post {
            if (closed || !currentRequest()) { completion.complete(VoiceAudioChangeResult.STALE); return@post }
            try {
                val switch = audioSwitch
                if (switch == null) { completion.complete(VoiceAudioChangeResult.UNAVAILABLE); return@post }
                val selected = when (output) {
                    VoiceOutputPreference.HEADSET -> switch.availableAudioDevices.firstOrNull {
                        it is AudioDevice.BluetoothHeadset || it is AudioDevice.WiredHeadset
                    }
                    VoiceOutputPreference.SPEAKER -> switch.availableAudioDevices.firstOrNull { it is AudioDevice.Speakerphone }
                    VoiceOutputPreference.AUTO -> null
                }
                if (output != VoiceOutputPreference.AUTO && selected == null) {
                    completion.complete(VoiceAudioChangeResult.UNAVAILABLE); return@post
                }
                // Validate output availability before touching input; input never picks speaker.
                val inputResult = requestInput()
                if (inputResult != VoiceAudioChangeResult.REQUESTED) { completion.complete(inputResult); return@post }
                if (desiredOutput != output) {
                    desiredOutput = output
                    outputAllowed(VoiceAudioPreferences(output=output).permitsOutput(outputKind(switch.selectedAudioDevice)))
                    switch.setPreferredDeviceList(VoiceAudioPreferences(output=output).outputPriority().map { kind -> when(kind) {
                        VoiceDeviceKind.BLUETOOTH -> AudioDevice.BluetoothHeadset::class.java
                        VoiceDeviceKind.WIRED -> AudioDevice.WiredHeadset::class.java
                        VoiceDeviceKind.SPEAKER -> AudioDevice.Speakerphone::class.java
                        VoiceDeviceKind.EARPIECE -> AudioDevice.Earpiece::class.java
                        VoiceDeviceKind.UNKNOWN -> error("Unknown output priority")
                    } })
                    // Local AAR selectDevice(null) clears user output selection; unlike ADM input reset.
                    switch.selectDevice(selected)
                }
                observe()
                completion.complete(VoiceAudioChangeResult.REQUESTED)
            } catch (_: Exception) {
                record(VoiceDiagnosticCode.ROUTE_OBSERVATION_ERROR)
                completion.complete(VoiceAudioChangeResult.FAILED)
            }
        }) return VoiceAudioChangeResult.FAILED
        // A cancelled caller does not prove its posted native action has finished. Keep barriers honest.
        return withContext(NonCancellable) { completion.await() }
    }

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
    private fun outputKind(device: AudioDevice?): VoiceDeviceKind = when(device) {
        is AudioDevice.BluetoothHeadset -> VoiceDeviceKind.BLUETOOTH
        is AudioDevice.WiredHeadset -> VoiceDeviceKind.WIRED
        is AudioDevice.Speakerphone -> VoiceDeviceKind.SPEAKER
        is AudioDevice.Earpiece -> VoiceDeviceKind.EARPIECE
        else -> VoiceDeviceKind.UNKNOWN
    }
    private fun record(code: VoiceDiagnosticCode) = diagnostics.record(SystemClock.elapsedRealtime(), code)
}
