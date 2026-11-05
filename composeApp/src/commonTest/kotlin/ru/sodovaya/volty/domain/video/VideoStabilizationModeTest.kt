package ru.sodovaya.volty.domain.video

import kotlin.test.Test
import kotlin.test.assertEquals

class VideoStabilizationModeTest {
    @Test
    fun supportedModesAlwaysIncludeOffAndIncludeOnlySupportedModes() {
        assertEquals(
            listOf(VideoStabilizationMode.OFF),
            supportedVideoStabilizationModes(videoSupported = false, previewSupported = false),
        )
        assertEquals(
            listOf(VideoStabilizationMode.OFF, VideoStabilizationMode.VIDEO),
            supportedVideoStabilizationModes(videoSupported = true, previewSupported = false),
        )
        assertEquals(
            listOf(VideoStabilizationMode.OFF, VideoStabilizationMode.PREVIEW),
            supportedVideoStabilizationModes(videoSupported = false, previewSupported = true),
        )
        assertEquals(
            listOf(
                VideoStabilizationMode.OFF,
                VideoStabilizationMode.VIDEO,
                VideoStabilizationMode.PREVIEW,
            ),
            supportedVideoStabilizationModes(videoSupported = true, previewSupported = true),
        )
    }

    @Test
    fun selectionDefaultsToVideoThenPreviewThenOff() {
        assertEquals(
            VideoStabilizationMode.OFF,
            selectVideoStabilizationMode(
                preferred = null,
                videoSupported = false,
                previewSupported = false,
            ),
        )
        assertEquals(
            VideoStabilizationMode.VIDEO,
            selectVideoStabilizationMode(
                preferred = null,
                videoSupported = true,
                previewSupported = false,
            ),
        )
        assertEquals(
            VideoStabilizationMode.PREVIEW,
            selectVideoStabilizationMode(
                preferred = null,
                videoSupported = false,
                previewSupported = true,
            ),
        )
        assertEquals(
            VideoStabilizationMode.VIDEO,
            selectVideoStabilizationMode(
                preferred = null,
                videoSupported = true,
                previewSupported = true,
            ),
        )
    }

    @Test
    fun selectionHonorsPreferredModeWhenSupported() {
        assertEquals(
            VideoStabilizationMode.OFF,
            selectVideoStabilizationMode(
                preferred = VideoStabilizationMode.OFF,
                videoSupported = true,
                previewSupported = true,
            ),
        )
        assertEquals(
            VideoStabilizationMode.VIDEO,
            selectVideoStabilizationMode(
                preferred = VideoStabilizationMode.VIDEO,
                videoSupported = true,
                previewSupported = true,
            ),
        )
        assertEquals(
            VideoStabilizationMode.PREVIEW,
            selectVideoStabilizationMode(
                preferred = VideoStabilizationMode.PREVIEW,
                videoSupported = true,
                previewSupported = true,
            ),
        )
    }

    @Test
    fun unsupportedPreferredModeFallsBackByVideoThenPreviewPriority() {
        assertEquals(
            VideoStabilizationMode.PREVIEW,
            selectVideoStabilizationMode(
                preferred = VideoStabilizationMode.VIDEO,
                videoSupported = false,
                previewSupported = true,
            ),
        )
        assertEquals(
            VideoStabilizationMode.VIDEO,
            selectVideoStabilizationMode(
                preferred = VideoStabilizationMode.PREVIEW,
                videoSupported = true,
                previewSupported = false,
            ),
        )
        assertEquals(
            VideoStabilizationMode.OFF,
            selectVideoStabilizationMode(
                preferred = VideoStabilizationMode.VIDEO,
                videoSupported = false,
                previewSupported = false,
            ),
        )
        assertEquals(
            VideoStabilizationMode.OFF,
            selectVideoStabilizationMode(
                preferred = VideoStabilizationMode.PREVIEW,
                videoSupported = false,
                previewSupported = false,
            ),
        )
    }
}
