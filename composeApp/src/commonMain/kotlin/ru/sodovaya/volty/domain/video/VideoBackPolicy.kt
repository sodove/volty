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

    /** The file is being finalized; leaving now would race the muxer, and the screen closes by itself soon. */
    IGNORE,
}

/**
 * **Retraction.** Back used to stop, save and close in one tap with no question asked (the audit's
 * "one-tap Back ends the recording"). Only an idle camera may be left without confirmation.
 */
fun videoBackAction(editing: Boolean, phase: VideoRecordingPhase, confirming: Boolean = false): VideoBackAction = when {
    confirming -> VideoBackAction.DISMISS_CONFIRM
    editing -> VideoBackAction.CLOSE_EDITOR
    phase == VideoRecordingPhase.IDLE -> VideoBackAction.CLOSE_SCREEN
    phase == VideoRecordingPhase.STOPPING -> VideoBackAction.IGNORE
    else -> VideoBackAction.CONFIRM_STOP
}
