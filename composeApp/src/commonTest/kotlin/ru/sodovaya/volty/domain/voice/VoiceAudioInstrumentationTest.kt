package ru.sodovaya.volty.domain.voice

import kotlin.test.*
class VoiceAudioInstrumentationTest {
    @Test fun ordinaryModesUseSdkCaptureOnlyPhoneNeedsIsolatedPath() {
        assertEquals(VoiceAudioCapturePath.SDK,voiceAudioCapturePath(VoiceInputPreference.AUTO))
        assertEquals(VoiceAudioCapturePath.SDK,voiceAudioCapturePath(VoiceInputPreference.HEADSET))
        assertEquals(VoiceAudioCapturePath.PHONE_ISOLATED,voiceAudioCapturePath(VoiceInputPreference.PHONE))
    }
    @Test fun pcmLevelMeasuresSignedLittleEndianSamplesWithoutReadingTail() {
        val result=voicePcm16Level(byteArrayOf(0,64,0,-64,127,127),4)
        assertEquals(16384,result.rms);assertEquals(16384,result.peak)
        assertEquals(VoicePcmLevel(32768,32768),voicePcm16Level(byteArrayOf(0,-128)))
        assertEquals(VoicePcmLevel(0,0),voicePcm16Level(byteArrayOf(0,0)))
        assertEquals(VoicePcmLevel(0,0),voicePcm16Level(byteArrayOf()))
    }
    @Test fun pcmLevelRejectsIncompleteSamples() {
        assertFailsWith<IllegalArgumentException> {voicePcm16Level(byteArrayOf(1))}
        assertFailsWith<IllegalArgumentException> {voicePcm16Level(byteArrayOf(0,0),4)}
    }
    @Test fun reportSeparatesSilencedCaptureFromTransportAndIncludesStalls() {
        val d=VoiceDiagnostics(VoiceGeneration(1));d.update {it.copy(capturePath=VoiceAudioCapturePath.SDK,
            recordingClientSilenced=true,recordingSources=listOf(7),captureRms=0,capturePeak=0,
            playbackUnderruns=9,inboundPacketsLost=3,inboundJitterMaxMillis=250,captureCallbackMaxMicros=24000)}
        val text=d.export();assertTrue(text.contains("capture_path=SDK"));assertTrue(text.contains("recording_client_silenced=true"))
        assertTrue(text.contains("playback_underruns=9"));assertTrue(text.contains("inbound_packets_lost=3"))
        assertTrue(text.contains("capture_callback_max_us=24000"));assertTrue(text.contains("capture_rms=0 capture_peak=0"))
    }
    @Test fun arbitraryDisconnectTextCannotEnterExport() {
        val d=VoiceDiagnostics(VoiceGeneration(1));d.update {it.copy(sdkDisconnectReason=voiceDisconnectCause("https://secret.invalid/token=private"))}
        assertFalse(d.export().contains("secret.invalid"));assertTrue(d.export().contains("disconnect_reason=UNKNOWN"))
        assertTrue(d.export().contains("capture_rms=UNKNOWN capture_peak=UNKNOWN"))
        assertTrue(d.export().contains("inbound_packets_lost=UNKNOWN"))
        assertEquals(VoiceDisconnectCause.CLIENT_INITIATED, voiceDisconnectCause("CLIENT_INITIATED"))
    }
}
