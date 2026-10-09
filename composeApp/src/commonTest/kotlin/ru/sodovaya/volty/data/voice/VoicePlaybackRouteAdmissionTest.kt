package ru.sodovaya.volty.data.voice

import ru.sodovaya.volty.domain.voice.VoiceOutputPreference
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VoicePlaybackRouteAdmissionTest {
    @Test fun acceptedHeadsetPreferenceCannotAdmitSpeakerOrUnknownRoute() {
        assertFalse(voicePlaybackFrameAllowed(VoiceOutputPreference.HEADSET, 7, null))
        assertFalse(voicePlaybackFrameAllowed(VoiceOutputPreference.HEADSET, 7, 9))
        assertTrue(voicePlaybackFrameAllowed(VoiceOutputPreference.HEADSET, 7, 7))
    }
    @Test fun disconnectedHeadsetCannotBeReplacedByANullPreference() {
        assertFalse(voicePlaybackFrameAllowed(VoiceOutputPreference.HEADSET, null, null))
        assertFalse(voicePlaybackFrameAllowed(VoiceOutputPreference.HEADSET, null, 9))
        assertTrue(voicePlaybackFrameAllowed(VoiceOutputPreference.AUTO, null, 9))
    }
    @Test fun speakerSelectionDoesNotAdmitAPreviousHeadset() {
        assertFalse(voicePlaybackFrameAllowed(VoiceOutputPreference.SPEAKER, 9, 7))
        assertTrue(voicePlaybackFrameAllowed(VoiceOutputPreference.SPEAKER, 9, 9))
    }
}
