package ru.sodovaya.volty.domain.voice

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VoiceCaptureHandoffTest {
    @Test fun sdkFramesWaitForRouteApplicationAndDiscardOneInFlightRead() {
        val handoff = VoiceCaptureHandoff()
        handoff.request(VoiceAudioCapturePath.SDK)
        repeat(5) { assertFalse(handoff.permitsFrame()) }
        handoff.applied()
        assertFalse(handoff.permitsFrame())
        assertTrue(handoff.permitsFrame())
        assertTrue(handoff.permitsFrame())
    }

    @Test fun phoneFramesWaitForRouteApplicationAndSdkRecorderRetirement() {
        val handoff = VoiceCaptureHandoff()
        handoff.request(VoiceAudioCapturePath.PHONE_ISOLATED)
        repeat(5) { assertFalse(handoff.permitsFrame()) }
        handoff.applied()
        repeat(2) { assertFalse(handoff.permitsFrame()) }
        assertTrue(handoff.permitsFrame())
    }

    @Test fun newRequestClosesGateAgainEvenDuringPreviousSettlingWindow() {
        val handoff = VoiceCaptureHandoff()
        handoff.request(VoiceAudioCapturePath.PHONE_ISOLATED)
        handoff.applied()
        assertFalse(handoff.permitsFrame())
        handoff.request(VoiceAudioCapturePath.SDK)
        repeat(5) { assertFalse(handoff.permitsFrame()) }
        handoff.applied()
        assertFalse(handoff.permitsFrame())
        assertTrue(handoff.permitsFrame())
    }
}
