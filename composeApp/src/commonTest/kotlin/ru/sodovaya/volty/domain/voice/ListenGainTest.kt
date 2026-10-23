package ru.sodovaya.volty.domain.voice

import ru.sodovaya.volty.domain.audio.VoiceListenSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ListenGainTest {
    @Test fun unknownParticipantGetsOnlyTheMasterGain() {
        val s = VoiceListenSettings(masterGainDb = 6f, participantGainDb = mapOf("a" to 9f))
        assertEquals(1.995, listenGain(s, "stranger"), 0.001)
        assertEquals(1.995, listenGain(s, null), 0.001)
    }

    @Test fun participantAndMasterAddInDecibels() {
        val s = VoiceListenSettings(masterGainDb = 3f, participantGainDb = mapOf("a" to 3f))
        assertEquals(1.995, listenGain(s, "a"), 0.001)
    }

    @Test fun combinedGainIsCappedAtTwelveDecibels() {
        val s = VoiceListenSettings(masterGainDb = 12f, participantGainDb = mapOf("a" to 12f))
        assertEquals(3.981, listenGain(s, "a"), 0.001)
    }

    @Test fun mutingAParticipantToTheFloorIsNearSilent() {
        val s = VoiceListenSettings(participantGainDb = mapOf("a" to -30f))
        assertEquals(0.0316, listenGain(s, "a"), 0.0001)
        val both = VoiceListenSettings(masterGainDb = -10f, participantGainDb = mapOf("a" to -30f))
        assertEquals(0.0316, listenGain(both, "a"), 0.0001) // floor holds below -30 dB
    }

    @Test fun phonePlaybackScalesAndClampsSamples() {
        val samples = shortArrayOf(10000, -20000)
        scalePcm16(samples, 2.0)
        assertEquals(listOf<Short>(20000, -32768), samples.toList())
    }

    @Test fun phonePlaybackBytesScaleTheSameWay() {
        val bytes = byteArrayOf(0x10, 0x27, 0xE0.toByte(), 0xB1.toByte()) // 10000, -20000 LE
        scalePcm16Le(bytes, 4, 2.0)
        assertEquals(byteArrayOf(0x20, 0x4E, 0x00, 0x80.toByte()).toList(), bytes.toList())
    }

    @Test fun identityDirectoryResolvesSessionToActorAndForgetsOnClear() {
        val dir = VoiceIdentityDirectory()
        val owner = Any()
        dir.publish(owner, mapOf("sess-1" to "actor-1"))
        assertEquals("actor-1", dir.actorFor("sess-1"))
        dir.clear(owner)
        assertNull(dir.actorFor("sess-1"))
    }

    @Test fun aRetiringRosterDoesNotEraseAnotherRostersMapping() {
        val dir = VoiceIdentityDirectory()
        val a = Any(); val b = Any()
        dir.publish(a, mapOf("sess-a" to "actor-a"))
        dir.publish(b, mapOf("sess-b" to "actor-b"))
        dir.clear(a)
        assertEquals("actor-b", dir.actorFor("sess-b"))
        assertNull(dir.actorFor("sess-a"))
    }
}
