package ru.sodovaya.volty.domain.audio

import kotlin.math.pow

const val MAX_LISTEN_GAIN_DB = 12f
const val MIN_LISTEN_GAIN_DB = -30f
const val MAX_ALERT_GAIN_DB = 12f
const val MAX_MIC_SENSITIVITY_DB = 12f

/** Which sound announces an alert. */
enum class AlertSoundKind { NAVIGATION, ALARM }

data class AlertSoundSettings(
    val kind: AlertSoundKind = AlertSoundKind.NAVIGATION,
    /** 0..[MAX_ALERT_GAIN_DB]; an alert is never made quieter than its source. */
    val gainDb: Float = 0f,
)

data class VoiceListenSettings(
    val duckMusicWhileSpeaking: Boolean = true,
    val masterGainDb: Float = 0f,
    /** Keyed by `actorId`. */
    val participantGainDb: Map<String, Float> = emptyMap(),
)

data class MicProcessingSettings(
    val autoGain: Boolean = true,
    val sensitivityDb: Float = 0f,
    val limiterCeilingDbfs: Float = -3f,
    /** The phone's built-in (vendor) noise suppressor; off by default, quality varies by device. */
    val phoneNoiseSuppressor: Boolean = false,
    /** Mute frames RNNoise does not hear speech in; the voice itself is never altered. Off by default. */
    val speechGate: Boolean = false,
    /** RNNoise speech probability that opens the gate, [MIN_SPEECH_GATE_THRESHOLD]..[MAX_SPEECH_GATE_THRESHOLD]. */
    val speechGateThreshold: Float = DEFAULT_SPEECH_GATE_THRESHOLD,
)

const val DEFAULT_SPEECH_GATE_THRESHOLD = 0.5f
const val MIN_SPEECH_GATE_THRESHOLD = 0.1f
const val MAX_SPEECH_GATE_THRESHOLD = 0.9f

fun dbToGain(db: Float): Double = 10.0.pow(db / 20.0)

internal fun clampListenGainDb(db: Float): Float = db.coerceIn(MIN_LISTEN_GAIN_DB, MAX_LISTEN_GAIN_DB)

/** `actorId=dB;actorId=dB`. */
fun VoiceListenSettings.encodeParticipants(): String =
    participantGainDb.entries.joinToString(";") { (id, db) -> "$id=$db" }

/** Corrupt pairs are skipped, values clamped to the listen range. */
fun decodeParticipantGains(raw: String?): Map<String, Float> {
    if (raw.isNullOrEmpty()) return emptyMap()
    val out = LinkedHashMap<String, Float>()
    for (pair in raw.split(';')) {
        val at = pair.indexOf('=')
        if (at <= 0) continue
        val id = pair.substring(0, at)
        val db = pair.substring(at + 1).toFloatOrNull()?.takeIf { it.isFinite() } ?: continue
        out[id] = clampListenGainDb(db)
    }
    return out
}
