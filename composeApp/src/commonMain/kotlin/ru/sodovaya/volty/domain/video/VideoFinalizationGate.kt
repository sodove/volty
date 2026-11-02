package ru.sodovaya.volty.domain.video

/**
 * Owns the ordering between asynchronous recording finalization and camera resource release.
 *
 * Counts outstanding recordings instead of tracking one flag: a segment rotation starts the next file while the
 * previous one is still finalizing, so two can be owned at once, and the camera must outlive both.
 */
class VideoFinalizationGate(private val requestStop: () -> Unit, private val release: () -> Unit) {
    private var outstanding = 0
    private var closing = false
    private var released = false

    fun recordingStarted() {
        check(!closing && outstanding == 0)
        outstanding = 1
    }

    /** The next segment of a ride that is already being recorded; the previous file may still be finalizing. */
    fun segmentStarted() {
        check(!closing && outstanding == 1)
        outstanding++
    }

    fun finalized() {
        if (outstanding > 0) outstanding--
        if (closing && outstanding == 0) releaseOnce()
    }

    fun close() {
        if (closing) return
        closing = true
        if (outstanding > 0) requestStop() else releaseOnce()
    }

    private fun releaseOnce() {
        if (released) return
        released = true
        release()
    }
}
