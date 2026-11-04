package ru.sodovaya.volty.video

import android.util.Range
import androidx.camera.core.CameraEffect
import androidx.camera.core.CameraInfo
import androidx.camera.core.ExperimentalSessionConfig
import androidx.camera.core.Preview
import androidx.camera.core.SessionConfig
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import ru.sodovaya.volty.domain.video.videoFrameRateChoices

/** Queries the actual resolution/use-case/effect combination, not the camera's global AE ranges. */
@OptIn(ExperimentalSessionConfig::class)
internal fun videoCameraSession(
    info: CameraInfo,
    preview: Preview,
    capture: VideoCapture<Recorder>,
    effect: CameraEffect,
    preferredFrameRate: Int,
): VideoSessionChoice {
    val base = SessionConfig(listOf(preview, capture), effects = listOf(effect))
    val choices = videoFrameRateChoices(info.getSupportedFrameRateRanges(base).map { it.lower to it.upper })
    val fps = preferredFrameRate.takeIf { it in choices } ?: choices.firstOrNull()
    val config = if (fps == null) base else SessionConfig(
        listOf(preview, capture), effects = listOf(effect), frameRateRange = Range(fps, fps),
    )
    return VideoSessionChoice(config, choices, fps)
}

@OptIn(ExperimentalSessionConfig::class)
internal data class VideoSessionChoice(val config: SessionConfig, val choices: List<Int>, val frameRate: Int?)
