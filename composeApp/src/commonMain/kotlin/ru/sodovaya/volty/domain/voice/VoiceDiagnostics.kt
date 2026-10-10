package ru.sodovaya.volty.domain.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class VoiceInputPreference { AUTO, PHONE, HEADSET }
enum class VoiceOutputPreference { AUTO, HEADSET, SPEAKER }
enum class VoiceDeviceKind { UNKNOWN, BLUETOOTH, WIRED, SPEAKER, EARPIECE }
data class VoiceAudioPreferences(
    val input: VoiceInputPreference = VoiceInputPreference.AUTO,
    val output: VoiceOutputPreference = VoiceOutputPreference.AUTO,
)

/** Routing policy consumes only output intent; input selection cannot rewrite it. */
fun VoiceAudioPreferences.outputPriority(): List<VoiceDeviceKind> = when (output) {
    VoiceOutputPreference.SPEAKER -> listOf(VoiceDeviceKind.SPEAKER, VoiceDeviceKind.BLUETOOTH, VoiceDeviceKind.WIRED, VoiceDeviceKind.EARPIECE)
    else -> listOf(VoiceDeviceKind.BLUETOOTH, VoiceDeviceKind.WIRED, VoiceDeviceKind.SPEAKER, VoiceDeviceKind.EARPIECE)
}

/** Output acceptance never consumes input intent or proves a measured stream route. */
fun VoiceAudioPreferences.permitsOutput(kind: VoiceDeviceKind): Boolean = when(output) {
    VoiceOutputPreference.HEADSET -> kind == VoiceDeviceKind.BLUETOOTH || kind == VoiceDeviceKind.WIRED
    VoiceOutputPreference.SPEAKER -> kind == VoiceDeviceKind.SPEAKER
    VoiceOutputPreference.AUTO -> true
}

/** Missing evidence in one receiver must not become an apparently complete total. */
fun sumCompleteVoiceCounters(counters: List<Long?>): Long? {
    if (counters.isEmpty() || counters.any { it == null || it < 0 }) return null
    var total = 0L
    for (value in counters) {
        val count = checkNotNull(value)
        if (Long.MAX_VALUE - total < count) return null
        total += count
    }
    return total
}

enum class VoiceDiagnosticCode {
    CONNECTING, CONNECTED, RECONNECTING, RECONNECTED, DISCONNECTED, CONNECT_FAILED, CONNECT_CANCELLED,
    CAPTURE_STARTED, CAPTURE_STOPPED, RENDER_STARTED, RENDER_STOPPED,
    RECORD_INIT_ERROR, RECORD_START_ERROR, RECORD_RUNTIME_ERROR,
    TRACK_INIT_ERROR, TRACK_START_ERROR, TRACK_RUNTIME_ERROR,
    INPUT_UNAVAILABLE, OUTPUT_UNAVAILABLE, ROUTE_CHANGED, ROUTE_OBSERVATION_ERROR, AUDIO_FOCUS,
    KEEPALIVE_STARTED, KEEPALIVE_STOPPED, KEEPALIVE_ERROR,
    PUBLISHED, PUBLICATION_FAILED, SUBSCRIBED, UNSUBSCRIBED, STATS, CLOSED, CLEANUP_ERROR, RECORDING_CONFIG_CHANGED,
}

data class VoiceDiagnosticEntry(val elapsedMillis: Long, val code: VoiceDiagnosticCode)
data class VoiceDiagnosticSnapshot(
    val generation: VoiceGeneration,
    val preferences: VoiceAudioPreferences = VoiceAudioPreferences(),
    val selectedOutput: VoiceDeviceKind = VoiceDeviceKind.UNKNOWN,
    // These are hints with explicitly separate provenance, not stream observations.
    val communicationDeviceType: Int? = null,
    val recordingDeviceTypes: List<Int> = emptyList(),
    val ownedCaptureDeviceType: Int? = null,
    val ownedPlaybackDeviceTypes: List<Int> = emptyList(),
    val audioMode: Int? = null,
    val audioFocus: Int? = null,
    val capturePath: VoiceAudioCapturePath? = null,
    val recordingClientSilenced: Boolean? = null,
    val recordingSources: List<Int> = emptyList(),
    val captureThreadPriority: Int? = null,
    val captureCallbackMaxMicros: Long? = null,
    val captureRms: Int? = null,
    val capturePeak: Int? = null,
    val playbackUnderruns: Long? = null,
    val playbackWriteMaxMicros: Long? = null,
    val inboundPacketsLost: Long? = null,
    val inboundJitterMaxMillis: Long? = null,
    val jitterBufferDelayMeanMillis: Long? = null,
    val remoteAudioTracks: Int? = null,
    val sdkDisconnectReason: VoiceDisconnectCause = VoiceDisconnectCause.UNKNOWN,
    val microphoneMuted: Boolean? = null,
    val playbackMuted: Boolean? = null,
    val captureRunning: Boolean? = null,
    val renderRunning: Boolean? = null,
    val captureBytes: Long = 0,
    val renderBytes: Long = 0,
    val outboundPackets: Long? = null,
    val inboundPackets: Long? = null,
    val trace: List<VoiceDiagnosticEntry> = emptyList(),
)

/** No arbitrary text, credentials, peer IDs or PCM enters the diagnostic model. */
class VoiceDiagnostics(generation: VoiceGeneration, private val capacity: Int = 128) {
    init { require(capacity > 0) }
    private val mutable = MutableStateFlow(VoiceDiagnosticSnapshot(generation))
    val state = mutable.asStateFlow()
    fun update(transform: (VoiceDiagnosticSnapshot) -> VoiceDiagnosticSnapshot) = mutable.update(transform)
    fun record(elapsedMillis: Long, code: VoiceDiagnosticCode) = update {
        it.copy(trace = (it.trace + VoiceDiagnosticEntry(elapsedMillis, code)).takeLast(capacity))
    }
    fun addCaptureBytes(count: Int) = update { it.copy(captureBytes = it.captureBytes + count.coerceAtLeast(0)) }
    fun addRenderBytes(count: Int) = update { it.copy(renderBytes = it.renderBytes + count.coerceAtLeast(0)) }
    fun export(): String = state.value.formatVoiceDiagnostics()
}

fun VoiceDiagnosticSnapshot.formatVoiceDiagnostics(): String = buildString {
    appendLine("generation=${generation.value}")
    appendLine("desired_input=${preferences.input} desired_output=${preferences.output}")
    appendLine("selected_output=$selectedOutput provenance=AUDIO_SWITCH_SELECTION")
    appendLine("actual_input=${ownedCaptureDeviceType ?: "UNKNOWN"} provenance=OWNED_AUDIO_RECORD actual_output=UNKNOWN")
    appendLine("owned_playback_device_types=${ownedPlaybackDeviceTypes.joinToString(",")} provenance=OWNED_AUDIO_TRACK")
    appendLine("communication_device_type=${communicationDeviceType ?: "UNKNOWN"} provenance=COMMUNICATION_DEVICE")
    appendLine("recording_device_types=${recordingDeviceTypes.joinToString(",")} provenance=UNCORRELATED_RECORDING_CONFIGURATION")
    appendLine("audio_mode=${audioMode ?: "UNKNOWN"} audio_focus=${audioFocus ?: "UNKNOWN"}")
    appendLine("capture_path=${capturePath ?: "UNKNOWN"} microphone_muted=${microphoneMuted ?: "UNKNOWN"} playback_muted=${playbackMuted ?: "UNKNOWN"}")
    appendLine("recording_client_silenced=${recordingClientSilenced ?: "UNKNOWN"} recording_sources=${recordingSources.joinToString(",")} provenance=UNCORRELATED_RECORDING_CONFIGURATION")
    appendLine("capture_thread_priority=${captureThreadPriority ?: "UNKNOWN"} capture_callback_max_us=${captureCallbackMaxMicros ?: "UNKNOWN"}")
    appendLine("capture_rms=${captureRms ?: "UNKNOWN"} capture_peak=${capturePeak ?: "UNKNOWN"} level_scope=POST_MUTE_CALLBACK_SAMPLE")
    appendLine("playback_underruns=${playbackUnderruns ?: "UNKNOWN"} playback_write_max_us=${playbackWriteMaxMicros ?: "UNKNOWN"} provenance=OWNED_AUDIO_TRACK")
    appendLine("inbound_packets_lost=${inboundPacketsLost ?: "UNKNOWN"} inbound_jitter_max_ms=${inboundJitterMaxMillis ?: "UNKNOWN"} jitter_buffer_delay_mean_ms=${jitterBufferDelayMeanMillis ?: "UNKNOWN"}")
    appendLine("remote_audio_tracks=${remoteAudioTracks ?: "UNKNOWN"} disconnect_reason=$sdkDisconnectReason")
    appendLine("capture_running=${captureRunning ?: "UNKNOWN"} render_running=${renderRunning ?: "UNKNOWN"}")
    appendLine("capture_bytes=$captureBytes render_bytes=$renderBytes")
    appendLine("outbound_packets=${outboundPackets ?: "UNKNOWN"} inbound_packets=${inboundPackets ?: "UNKNOWN"}")
    trace.forEach { appendLine("${it.elapsedMillis} ${it.code}") }
}
