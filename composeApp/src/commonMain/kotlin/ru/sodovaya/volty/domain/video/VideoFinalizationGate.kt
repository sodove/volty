package ru.sodovaya.volty.domain.video

/** Owns the ordering between asynchronous recording finalization and camera resource release. */
class VideoFinalizationGate(private val requestStop: () -> Unit, private val release: () -> Unit) {
    private var recordingOwned = false
    private var closing = false
    private var released = false

    fun recordingStarted() {
        check(!closing && !recordingOwned)
        recordingOwned = true
    }

    fun finalized() {
        recordingOwned = false
        if (closing) releaseOnce()
    }

    fun close() {
        if (closing) return
        closing = true
        if (recordingOwned) requestStop() else releaseOnce()
    }

    private fun releaseOnce() {
        if (released) return
        released = true
        release()
    }
}
