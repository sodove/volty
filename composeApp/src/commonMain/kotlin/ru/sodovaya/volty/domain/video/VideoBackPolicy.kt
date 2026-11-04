package ru.sodovaya.volty.domain.video

/** What a Back press (system gesture or the on-screen button) does on the camera screen. */
enum class VideoBackAction {
    /** The stop question is up: Back answers it with "keep recording", which can only be the safe answer. */
    DISMISS_CONFIRM,

    /** Leave the overlay editor; the camera is idle there by construction. */
    CLOSE_EDITOR,

    /** A recording is live or starting: ask first. One stray glove tap must not end the ride's video. */
    CONFIRM_STOP,

    /** Nothing is recording; leave. */
    CLOSE_SCREEN,

}

/**
 * **Retraction.** Back used to stop, save and close in one tap with no question asked (the audit's
 * "one-tap Back ends the recording"). Only an idle camera may be left without confirmation.
 */
fun videoBackAction(editing: Boolean, phase: VideoRecordingPhase, confirming: Boolean = false): VideoBackAction = when {
    confirming -> VideoBackAction.DISMISS_CONFIRM
    editing -> VideoBackAction.CLOSE_EDITOR
    phase == VideoRecordingPhase.IDLE -> VideoBackAction.CLOSE_SCREEN
    // Retraction (2026-11-05): waiting for Finalize used to trap Back indefinitely after a camera failure.
    // The controller's finalization gate retains resources independently of the Activity's lifetime.
    phase == VideoRecordingPhase.STOPPING -> VideoBackAction.CLOSE_SCREEN
    else -> VideoBackAction.CONFIRM_STOP
}
