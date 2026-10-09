package ru.sodovaya.volty.data.voice

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VoiceCaptureFrameGateTest {
    @Test fun explicitPhoneRequiresTheOwnedRecordingDeviceNotAPreferenceReceipt() {
        val gate = VoiceCaptureFrameGate()
        gate.request(7); gate.mute(false)
        val read = gate.beginRead()
        assertFalse(gate.permitsFrame(read, null, true))
        assertFalse(gate.permitsFrame(read, 9, true))
        assertTrue(gate.permitsFrame(read, 7, true))
    }

    @Test fun muteThenUnmuteCannotReleaseAPreMuteFrame() {
        val gate = VoiceCaptureFrameGate()
        gate.request(7); gate.mute(false)
        val read = gate.beginRead()
        gate.mute(true)
        assertFalse(gate.permitsFrame(read, 7, true))
        gate.mute(false)
        assertFalse(gate.permitsFrame(read, 7, true))
        assertTrue(gate.permitsFrame(gate.beginRead(), 7, true))
    }

    @Test fun switchingAwayAndBackCannotResurrectAnOldRead() {
        val gate = VoiceCaptureFrameGate()
        gate.request(7); gate.mute(false)
        val read = gate.beginRead()
        gate.request(9); gate.request(7)
        assertFalse(gate.permitsFrame(read, 7, true))
        assertTrue(gate.permitsFrame(gate.beginRead(), 7, true))
    }

    @Test fun incompleteAndRetiredFramesNeverLeaveCapture() {
        val gate = VoiceCaptureFrameGate()
        gate.mute(false)
        assertFalse(gate.permitsFrame(gate.beginRead(), 7, false))
        assertTrue(gate.permitsFrame(gate.beginRead(), 7, true))
        gate.close(); gate.mute(false); gate.request(null)
        assertFalse(gate.permitsFrame(gate.beginRead(), 7, true))
    }
}
