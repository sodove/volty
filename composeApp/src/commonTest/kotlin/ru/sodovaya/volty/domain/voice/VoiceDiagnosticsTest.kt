package ru.sodovaya.volty.domain.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VoiceDiagnosticsTest {
    @Test fun missingCounterCannotBecomePartialTotal() {
        assertEquals(null, sumCompleteVoiceCounters(listOf(17L, null)))
        assertEquals(null, sumCompleteVoiceCounters(emptyList()))
        assertEquals(null, sumCompleteVoiceCounters(listOf(17L, -1L)))
        assertEquals(28L, sumCompleteVoiceCounters(listOf(17L, 11L)))
    }
    @Test fun phoneInputPreservesHeadphoneOutput() {
        val routes = VoiceAudioPreferences(output = VoiceOutputPreference.HEADSET)
        assertEquals(routes.outputPriority(), routes.copy(input = VoiceInputPreference.PHONE).outputPriority())
        assertEquals(listOf(VoiceDeviceKind.BLUETOOTH, VoiceDeviceKind.WIRED),
            routes.copy(input = VoiceInputPreference.PHONE).outputPriority().take(2))
    }

    @Test fun explicitHeadsetOutputRejectsSpeakerAndUnknownEvenWithPhoneMicrophone() {
        for(input in VoiceInputPreference.entries) {
            val requested=VoiceAudioPreferences(input,VoiceOutputPreference.HEADSET)
            assertTrue(requested.permitsOutput(VoiceDeviceKind.BLUETOOTH));assertTrue(requested.permitsOutput(VoiceDeviceKind.WIRED))
            for(kind in listOf(VoiceDeviceKind.SPEAKER,VoiceDeviceKind.EARPIECE,VoiceDeviceKind.UNKNOWN)) assertFalse(requested.permitsOutput(kind))
        }
    }
    @Test fun explicitSpeakerDoesNotAcceptHeadsetAndAutoRemainsAnIndependentChoice() {
        for(input in VoiceInputPreference.entries) {
            val requested=VoiceAudioPreferences(input,VoiceOutputPreference.SPEAKER)
            assertTrue(requested.permitsOutput(VoiceDeviceKind.SPEAKER))
            for(kind in listOf(VoiceDeviceKind.BLUETOOTH,VoiceDeviceKind.WIRED,VoiceDeviceKind.EARPIECE,VoiceDeviceKind.UNKNOWN)) assertFalse(requested.permitsOutput(kind))
            for(kind in VoiceDeviceKind.entries) assertTrue(requested.copy(output=VoiceOutputPreference.AUTO).permitsOutput(kind))
        }
    }

    @Test fun selectionAndSamplesDoNotInventActualStreamRoute() {
        val diagnostics = VoiceDiagnostics(VoiceGeneration(7))
        diagnostics.update { it.copy(selectedOutput = VoiceDeviceKind.WIRED, captureBytes = 960) }
        val export = diagnostics.export()
        assertTrue(export.contains("actual_input=UNKNOWN"))
        assertTrue(export.contains("actual_output=UNKNOWN"))
        assertTrue(export.contains("capture_bytes=960"))
        assertTrue(export.contains("outbound_packets=UNKNOWN"))
    }

    @Test fun traceEvictsOldestAndExportsOnlyTypedFields() {
        val diagnostics = VoiceDiagnostics(VoiceGeneration(9), capacity = 2)
        diagnostics.record(1, VoiceDiagnosticCode.CONNECTING)
        diagnostics.record(2, VoiceDiagnosticCode.CAPTURE_STARTED)
        diagnostics.record(3, VoiceDiagnosticCode.RECORD_START_ERROR)
        assertEquals(2, diagnostics.state.value.trace.size)
        val export = diagnostics.export()
        assertFalse(export.contains("CONNECTING"))
        assertTrue(export.contains("2 CAPTURE_STARTED"))
        assertTrue(export.contains("3 RECORD_START_ERROR"))
        assertTrue(export.contains("generation=9"))
    }

    @Test fun samplesUpdateCountersWithoutFillingTrace() {
        val diagnostics = VoiceDiagnostics(VoiceGeneration(1))
        repeat(100) { diagnostics.addCaptureBytes(960) }
        diagnostics.addRenderBytes(1920)
        assertEquals(96000, diagnostics.state.value.captureBytes)
        assertEquals(1920, diagnostics.state.value.renderBytes)
        assertEquals(0, diagnostics.state.value.trace.size)
    }

    @Test fun negativeSamplesCannotCorruptEvidence() {
        val diagnostics = VoiceDiagnostics(VoiceGeneration(1))
        diagnostics.addCaptureBytes(-1)
        diagnostics.addRenderBytes(-10)
        assertEquals(0, diagnostics.state.value.captureBytes)
        assertEquals(0, diagnostics.state.value.renderBytes)
    }
}
