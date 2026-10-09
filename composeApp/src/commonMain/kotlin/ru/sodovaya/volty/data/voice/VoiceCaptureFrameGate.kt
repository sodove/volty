package ru.sodovaya.volty.data.voice

/** Serialized by the native owner; a preference is never stream-route evidence. */
internal class VoiceCaptureFrameGate {
    private var revision = 0L
    private var muted = true
    private var closed = false
    private var requestedDevice: Int? = null

    fun request(device: Int?): Long { requestedDevice = device; return ++revision }
    fun mute(value: Boolean) { muted = value; revision++ }
    fun close() { closed = true; muted = true; revision++ }
    fun beginRead(): Long = revision

    fun permitsFrame(readRevision: Long, actualDevice: Int?, complete: Boolean): Boolean =
        !closed && !muted && complete && readRevision == revision &&
            (requestedDevice == null || actualDevice == requestedDevice)
}
