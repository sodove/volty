package ru.sodovaya.volty.domain.video

enum class VideoStabilizationMode {
    OFF,
    VIDEO,
    PREVIEW,
}

fun supportedVideoStabilizationModes(
    videoSupported: Boolean,
    previewSupported: Boolean,
): List<VideoStabilizationMode> = buildList {
    add(VideoStabilizationMode.OFF)
    if (videoSupported) add(VideoStabilizationMode.VIDEO)
    if (previewSupported) add(VideoStabilizationMode.PREVIEW)
}

fun selectVideoStabilizationMode(
    preferred: VideoStabilizationMode?,
    videoSupported: Boolean,
    previewSupported: Boolean,
): VideoStabilizationMode = when {
    preferred == VideoStabilizationMode.OFF -> VideoStabilizationMode.OFF
    preferred == VideoStabilizationMode.VIDEO && videoSupported -> VideoStabilizationMode.VIDEO
    preferred == VideoStabilizationMode.PREVIEW && previewSupported -> VideoStabilizationMode.PREVIEW
    videoSupported -> VideoStabilizationMode.VIDEO
    previewSupported -> VideoStabilizationMode.PREVIEW
    else -> VideoStabilizationMode.OFF
}
