package ru.sodovaya.volty.domain.voice

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MusicDuckGateTest {
    @Test fun speechDucksImmediately() {
        val gate = MusicDuckGate()
        assertFalse(gate.onTick(0))
        assertTrue(gate.onSpeakers(remoteSpeaking = true, nowMillis = 0))
    }

    @Test fun silenceShorterThanTheHoldKeepsTheDuck() {
        val gate = MusicDuckGate()
        gate.onSpeakers(true, 0)
        assertTrue(gate.onSpeakers(false, 100))
        assertTrue(gate.onTick(1_500))
    }

    @Test fun silenceLongerThanTheHoldReleases() {
        val gate = MusicDuckGate()
        gate.onSpeakers(true, 0)
        gate.onSpeakers(false, 100)
        assertFalse(gate.onTick(1_601))
    }

    @Test fun resumedSpeechInsideTheHoldRestartsIt() {
        val gate = MusicDuckGate()
        gate.onSpeakers(true, 0)
        gate.onSpeakers(false, 100)
        gate.onSpeakers(true, 1_000)
        gate.onSpeakers(false, 1_100)
        assertTrue(gate.onTick(2_500))
        assertFalse(gate.onTick(2_700))
    }

    @Test fun ownSpeechDoesNotDuck() {
        // The caller passes only remote participants; local-only speech arrives as false.
        val gate = MusicDuckGate()
        assertFalse(gate.onSpeakers(remoteSpeaking = false, nowMillis = 0))
        assertFalse(gate.onTick(10))
    }

    @Test fun aClosedSessionReleasesTheDuck() {
        val gate = MusicDuckGate()
        gate.onSpeakers(true, 0)
        assertFalse(gate.release())
        assertFalse(gate.onTick(1))
    }

    @Test fun turningTheSettingOffReleasesAtOnce() {
        val gate = MusicDuckGate()
        gate.onSpeakers(true, 0)
        assertFalse(gate.release())
        // A stale tick after release must not resurrect the duck.
        assertFalse(gate.onTick(100))
    }
}
