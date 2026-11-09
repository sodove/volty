package ru.sodovaya.volty.video

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.io.File

/** Decode a debug stress clip into [surface], preserving its source presentation cadence. */
internal fun feedStressVideo(file: File, surface: Surface, onFrame: (timestampNanos: Long) -> Unit): Int {
    var extractor: MediaExtractor? = null
    var codec: MediaCodec? = null
    var codecStarted = false
    var decodeStartedAtNanos: Long? = null
    var decoderName = "unknown"
    var frameCount = 0
    var lateCount = 0
    var firstOutputPtsUs: Long? = null
    var lastOutputPtsUs: Long? = null
    var ptsMonotonic = true
    var inputOriginPtsUs: Long? = null
    var lastActivityNanos = 0L

    try {
        val source = MediaExtractor().also { extractor = it }
        source.setDataSource(file.absolutePath)
        val track = (0 until source.trackCount).firstOrNull { index ->
            source.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
        } ?: error("No video track in ${file.name}")
        val inputFormat = source.getTrackFormat(track)
        val mime = inputFormat.getString(MediaFormat.KEY_MIME)
            ?: error("Video track has no MIME type in ${file.name}")
        require(mime == MediaFormat.MIMETYPE_VIDEO_AVC) { "Expected AVC video, got $mime" }
        source.selectTrack(track)

        val width = inputFormat.getInteger(MediaFormat.KEY_WIDTH)
        val height = inputFormat.getInteger(MediaFormat.KEY_HEIGHT)
        val fps = if (inputFormat.containsKey(MediaFormat.KEY_FRAME_RATE)) {
            inputFormat.getInteger(MediaFormat.KEY_FRAME_RATE)
        } else {
            -1
        }
        Log.i(TAG, "source=${file.name} mime=$mime dimensions=${width}x$height fps=$fps api=${Build.VERSION.SDK_INT}")

        val decoder = MediaCodec.createDecoderByType(mime).also { codec = it }
        decoderName = decoder.name
        decoder.configure(inputFormat, surface, null, 0)
        decoder.start()
        codecStarted = true
        val scheduleAnchorNanos = System.nanoTime()
        val surfaceAnchorNanos = SystemClock.elapsedRealtimeNanos()
        decodeStartedAtNanos = surfaceAnchorNanos
        lastActivityNanos = surfaceAnchorNanos
        inputOriginPtsUs = source.sampleTime.takeIf { it >= 0L }

        Log.i(TAG, "decoder=$decoderName configured=${width}x$height")

        val outputInfo = MediaCodec.BufferInfo()
        var inputEos = false
        var outputEos = false
        while (!outputEos) {
            var madeProgress = false

            if (!inputEos) {
                val inputIndex = decoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                if (inputIndex >= 0) {
                    val inputBuffer = checkNotNull(decoder.getInputBuffer(inputIndex)) { "Decoder input buffer unavailable" }
                    inputBuffer.clear()
                    val sampleSize = source.readSampleData(inputBuffer, 0)
                    if (sampleSize < 0) {
                        val eosPtsUs = lastOutputPtsUs ?: inputOriginPtsUs ?: 0L
                        decoder.queueInputBuffer(inputIndex, 0, 0, eosPtsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputEos = true
                    } else {
                        val ptsUs = source.sampleTime
                        if (inputOriginPtsUs == null && ptsUs >= 0L) inputOriginPtsUs = ptsUs
                        decoder.queueInputBuffer(inputIndex, 0, sampleSize, ptsUs, source.sampleFlags)
                        source.advance()
                    }
                    madeProgress = true
                    lastActivityNanos = SystemClock.elapsedRealtimeNanos()
                }
            }

            when (val outputIndex = decoder.dequeueOutputBuffer(outputInfo, DEQUEUE_TIMEOUT_US)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val outputFormat = decoder.outputFormat
                    val outputWidth = outputFormat.getInteger(MediaFormat.KEY_WIDTH)
                    val outputHeight = outputFormat.getInteger(MediaFormat.KEY_HEIGHT)
                    Log.i(TAG, "output format=${outputWidth}x$outputHeight")
                    madeProgress = true
                    lastActivityNanos = SystemClock.elapsedRealtimeNanos()
                }
                MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                else -> if (outputIndex >= 0) {
                    madeProgress = true
                    lastActivityNanos = SystemClock.elapsedRealtimeNanos()
                    val isEos = outputInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    val isCodecConfig = outputInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (outputInfo.size > 0 && !isCodecConfig) {
                        val ptsUs = outputInfo.presentationTimeUs
                        val previousPtsUs = lastOutputPtsUs
                        if (previousPtsUs != null && ptsUs < previousPtsUs) ptsMonotonic = false
                        if (firstOutputPtsUs == null) firstOutputPtsUs = ptsUs
                        lastOutputPtsUs = ptsUs

                        val sourceOriginUs = checkNotNull(inputOriginPtsUs) { "No source presentation timestamp" }
                        val relativeNanos = (ptsUs - sourceOriginUs) * NANOS_PER_MICROSECOND
                        val deadlineNanos = scheduleAnchorNanos + relativeNanos
                        awaitDeadline(deadlineNanos)

                        val nowNanos = System.nanoTime()
                        if (nowNanos - deadlineNanos > FRAME_INTERVAL_NANOS) lateCount++
                        val surfaceTimestampNanos = surfaceAnchorNanos + relativeNanos
                        try {
                            onFrame(surfaceTimestampNanos)
                        } finally {
                            decoder.releaseOutputBuffer(outputIndex, surfaceTimestampNanos)
                        }
                        frameCount++
                    } else {
                        decoder.releaseOutputBuffer(outputIndex, false)
                    }
                    if (isEos) outputEos = true
                }
            }

            if (!madeProgress && SystemClock.elapsedRealtimeNanos() - lastActivityNanos > STALL_TIMEOUT_NANOS) {
                error("Decoder stalled for ${STALL_TIMEOUT_NANOS / NANOS_PER_MILLISECOND} ms")
            }
        }

        return frameCount
    } finally {
        try {
            val activeCodec = codec
            if (activeCodec != null) {
                if (codecStarted) runCatching { activeCodec.stop() }
                runCatching { activeCodec.release() }
            }
        } finally {
            runCatching { extractor?.release() }
            val startedAt = decodeStartedAtNanos
            val elapsedMs = if (startedAt == null) 0L else (SystemClock.elapsedRealtimeNanos() - startedAt) / NANOS_PER_MILLISECOND
            Log.i(TAG, "decoder=$decoderName frames=$frameCount late=$lateCount elapsedMs=$elapsedMs " +
                "ptsMonotonic=$ptsMonotonic firstPtsUs=${firstOutputPtsUs ?: "none"} lastPtsUs=${lastOutputPtsUs ?: "none"} " +
                "api=${Build.VERSION.SDK_INT}")
        }
    }
}

private fun awaitDeadline(deadlineNanos: Long) {
    while (true) {
        val remainingNanos = deadlineNanos - System.nanoTime()
        if (remainingNanos <= 0L) return
        try {
            Thread.sleep(remainingNanos / NANOS_PER_MILLISECOND, (remainingNanos % NANOS_PER_MILLISECOND).toInt())
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw interrupted
        }
    }
}

private const val TAG = "RideHudStress"
private const val DEQUEUE_TIMEOUT_US = 10_000L
private const val FRAME_INTERVAL_NANOS = 1_000_000_000L / 60L
private const val NANOS_PER_MICROSECOND = 1_000L
private const val NANOS_PER_MILLISECOND = 1_000_000L
private const val STALL_TIMEOUT_NANOS = 15_000_000_000L
