package ru.sodovaya.volty.domain.audio

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RideAudioSettingsTest {

    @Test
    fun dbToGainMatchesKnownPoints() {
        assertEquals(1.0, dbToGain(0f), 1e-9)
        assertTrue(abs(dbToGain(6f) - 1.995) < 0.001)
        assertEquals(0.1, dbToGain(-20f), 1e-6)
    }

    @Test
    fun participantGainsSurviveARoundTrip() {
        val settings = VoiceListenSettings(participantGainDb = mapOf("alice" to 3f, "bob" to -12.5f))
        assertEquals(settings.participantGainDb, decodeParticipantGains(settings.encodeParticipants()))
    }

    @Test
    fun aCorruptParticipantEntryIsSkippedNotFatal() {
        assertEquals(mapOf("a" to 3f, "c" to 12f), decodeParticipantGains("a=3;b=x;=2;c=99"))
        assertEquals(emptyMap(), decodeParticipantGains(null))
        assertEquals(emptyMap(), decodeParticipantGains(""))
    }

    @Test
    fun defaultsMatchTheAgreedDecisions() {
        assertEquals(AlertSoundKind.NAVIGATION, AlertSoundSettings().kind)
        assertEquals(true, VoiceListenSettings().duckMusicWhileSpeaking)
        assertEquals(true, MicProcessingSettings().autoGain)
        assertEquals(-3f, MicProcessingSettings().limiterCeilingDbfs)
    }
}
