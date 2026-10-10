package ru.sodovaya.volty.domain.voice

/** Serialized by the native capture owner; a route request must complete before PCM can pass. */
internal class VoiceCaptureHandoff {
    private var ready = false
    private var settlingFrames = 0
    fun request(path: VoiceAudioCapturePath) {
        ready = false
        // One read may already be in flight; PHONE also waits for SDK retirement.
        settlingFrames = if (path == VoiceAudioCapturePath.PHONE_ISOLATED) 2 else 1
    }
    fun applied() { ready = true }
    fun permitsFrame(): Boolean {
        if (!ready) return false
        if (settlingFrames > 0) { settlingFrames--; return false }
        return true
    }
}
