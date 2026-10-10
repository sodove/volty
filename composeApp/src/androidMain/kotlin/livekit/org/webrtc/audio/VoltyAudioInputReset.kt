package livekit.org.webrtc.audio

/**
 * WebRTC 144.7559.09's public ADM setter logs device.id and rejects null.
 * Its package-level AudioRecord setter explicitly supports null to restore
 * Android's automatic route. Keep this compile-checked bridge beside that API
 * instead of reflection or leaving a previously selected headset pinned.
 */
internal fun JavaAudioDeviceModule.resetVoltyPreferredInput() {
    audioInput.setPreferredDevice(null)
}
