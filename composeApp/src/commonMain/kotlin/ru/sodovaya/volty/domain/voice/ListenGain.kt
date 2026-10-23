package ru.sodovaya.volty.domain.voice

import ru.sodovaya.volty.domain.audio.MAX_LISTEN_GAIN_DB
import ru.sodovaya.volty.domain.audio.MIN_LISTEN_GAIN_DB
import ru.sodovaya.volty.domain.audio.VoiceListenSettings
import ru.sodovaya.volty.domain.audio.dbToGain

/**
 * Linear gain for one remote participant: master and per-participant decibels add, the sum is
 * clamped to [MIN_LISTEN_GAIN_DB]..[MAX_LISTEN_GAIN_DB] (two +12 dB sliders must not clip), then
 * converted once. An unknown [actorId] gets only the master gain.
 */
fun listenGain(settings: VoiceListenSettings, actorId: String?): Double {
    val participant = actorId?.let { settings.participantGainDb[it] } ?: 0f
    val total = (settings.masterGainDb + participant).coerceIn(MIN_LISTEN_GAIN_DB, MAX_LISTEN_GAIN_DB)
    return dbToGain(total)
}

/** In place; samples saturate at the 16-bit range instead of wrapping. */
fun scalePcm16(samples: ShortArray, gain: Double) {
    for (i in samples.indices) {
        val scaled = (samples[i] * gain).let { if (it >= 0) it + 0.5 else it - 0.5 }.toInt()
        samples[i] = scaled.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }
}

/** Same as [scalePcm16] over little-endian 16-bit PCM bytes (the layout WebRTC hands to sinks). */
fun scalePcm16Le(bytes: ByteArray, count: Int, gain: Double) {
    var i = 0
    while (i + 1 < count) {
        val sample = ((bytes[i].toInt() and 0xFF) or (bytes[i + 1].toInt() shl 8)).toShort()
        val scaled = (sample * gain).let { if (it >= 0) it + 0.5 else it - 0.5 }.toInt()
            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        bytes[i] = scaled.toByte()
        bytes[i + 1] = (scaled shr 8).toByte()
        i += 2
    }
}

/**
 * LiveKit identifies a channel participant by its voice session id; the person is the actor.
 * The roster publishes its rows here so the audio session can resolve identity -> actor.
 */
class VoiceIdentityDirectory {
    @Volatile private var actors: Map<String, String> = emptyMap()

    fun publish(peers: List<VoiceRosterPeer>?) {
        actors = peers.orEmpty().associate { it.sessionId.value to it.actorId }
    }

    fun actorFor(identity: String): String? = actors[identity]
}
