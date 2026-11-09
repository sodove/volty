package ru.sodovaya.volty.video

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.util.Log
import android.view.Surface
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** AVC surface encoder used only by the debug HUD stress activity. */
internal class StressEncoder(
    width: Int,
    height: Int,
    fps: Int,
    file: File,
) : AutoCloseable {
    private val resources: Resources
    val surface: Surface

    private val codecLock = Any()
    private val lifecycleLock = Any()
    private val releaseStarted = AtomicBoolean(false)
    private val workerFailure = AtomicReference<Throwable?>(null)
    @Volatile private var abortRequested = false
    private var finishRequested = false
    private var finishCompleted = false
    private var muxerStarted = false
    private var muxerStopped = false
    private var outputTrack = -1
    private var reportedOutputFps: Int? = null
    private var encodedAccessUnits = 0L
    private var firstPresentationTimeUs: Long? = null
    private var lastPresentationTimeUs: Long? = null
    private val drainWorker: Thread

    init {
        require(width > 0) { "width must be positive" }
        require(height > 0) { "height must be positive" }
        require(fps > 0) { "fps must be positive" }
        resources = createResources(width, height, fps, file)
        surface = resources.inputSurface
        val worker = Thread(::drainOutput, "RideHudStressEncoderDrain").apply {
            isDaemon = true
        }
        drainWorker = worker
        try {
            worker.start()
        } catch (failure: Throwable) {
            releaseResources()
            throw failure
        }
    }

    /** Signals input EOS and waits up to [FINISH_TIMEOUT_MS] for the muxed MP4 to finalize. */
    fun finish() {
        synchronized(lifecycleLock) {
            if (finishCompleted) return
            if (!finishRequested) {
                finishRequested = true
                if (workerFailure.get() == null && drainWorker.isAlive) {
                    synchronized(codecLock) {
                        if (!abortRequested) resources.codec.signalEndOfInputStream()
                    }
                }
            }

            drainWorker.join(FINISH_TIMEOUT_MS)
            check(!drainWorker.isAlive) { "Timed out waiting for AVC encoder drain" }
            workerFailure.get()?.let { throw IllegalStateException("AVC encoder drain failed", it) }
            check(muxerStopped) { "AVC encoder reached EOS without finalizing the MP4 muxer" }
            finishCompleted = true
        }
    }

    override fun close() {
        var failure: Throwable? = null
        try {
            finish()
        } catch (cause: Throwable) {
            failure = cause
        }

        try {
            releaseResources()
        } catch (cause: Throwable) {
            if (failure == null) failure = cause else failure.addSuppressed(cause)
        }

        failure?.let { throw it }
    }

    private fun drainOutput() {
        val bufferInfo = MediaCodec.BufferInfo()
        try {
            while (!abortRequested) {
                val outputIndex = synchronized(codecLock) {
                    if (abortRequested) return
                    val index = resources.codec.dequeueOutputBuffer(bufferInfo, DRAIN_TIMEOUT_US)
                    when {
                        index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> onOutputFormatChanged()
                        index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                        index >= 0 -> onOutputBuffer(index, bufferInfo)
                    }
                    index
                }
                if (outputIndex >= 0 && bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    logOutputRate()
                    return
                }
            }
        } catch (cause: Throwable) {
            if (!abortRequested) {
                workerFailure.compareAndSet(null, cause)
                Log.e(TAG, "Encoder drain failed", cause)
            }
        }
    }

    private fun onOutputFormatChanged() {
        check(!muxerStarted && !muxerStopped) { "AVC output format changed more than once" }
        val format = resources.codec.outputFormat
        reportedOutputFps = format.integerOrNull(MediaFormat.KEY_FRAME_RATE)
        outputTrack = resources.muxer.addTrack(format)
        resources.muxer.start()
        muxerStarted = true
        Log.i(
            TAG,
            "Encoder output format reports fps=${reportedOutputFps ?: "unreported"}; $format",
        )
    }

    private fun onOutputBuffer(index: Int, info: MediaCodec.BufferInfo) {
        try {
            val isCodecConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
            if (info.size > 0 && !isCodecConfig) {
                check(muxerStarted) { "AVC sample arrived before output format" }
                val buffer = checkNotNull(resources.codec.getOutputBuffer(index)) { "Missing AVC output buffer" }
                buffer.position(info.offset)
                buffer.limit(info.offset + info.size)
                // Keep BufferInfo.presentationTimeUs unchanged so the source video PTS reaches MP4.
                resources.muxer.writeSampleData(outputTrack, buffer, info)
                encodedAccessUnits++
                if (firstPresentationTimeUs == null) firstPresentationTimeUs = info.presentationTimeUs
                lastPresentationTimeUs = info.presentationTimeUs
            }

            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                check(muxerStarted) { "AVC encoder ended before producing an output format" }
                resources.muxer.stop()
                muxerStarted = false
                muxerStopped = true
            }
        } finally {
            resources.codec.releaseOutputBuffer(index, false)
        }
    }

    private fun logOutputRate() {
        val firstPts = firstPresentationTimeUs
        val lastPts = lastPresentationTimeUs
        val measuredPtsFps = if (encodedAccessUnits > 1 && firstPts != null && lastPts != null && lastPts > firstPts) {
            (encodedAccessUnits - 1) * 1_000_000.0 / (lastPts - firstPts)
        } else {
            null
        }
        Log.i(
            TAG,
            "Encoded accessUnits=$encodedAccessUnits reportedFps=${reportedOutputFps ?: "unreported"} " +
                "presentationTimestampFps=${measuredPtsFps?.let { String.format(Locale.US, "%.2f", it) } ?: "unavailable"}",
        )
    }

    private fun releaseResources() {
        if (!releaseStarted.compareAndSet(false, true)) return
        abortRequested = true
        drainWorker.interrupt()

        synchronized(codecLock) {
            if (muxerStarted) {
                runCatching { resources.muxer.stop() }
                muxerStarted = false
            }
            runCatching { resources.muxer.release() }
            runCatching { surface.release() }
            runCatching { resources.codec.stop() }
            runCatching { resources.codec.release() }
        }
        if (drainWorker.isAlive) drainWorker.join(RELEASE_JOIN_TIMEOUT_MS)
    }

    private data class Resources(
        val codec: MediaCodec,
        val inputSurface: Surface,
        val muxer: MediaMuxer,
    )

    private fun createResources(width: Int, height: Int, fps: Int, file: File): Resources {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SECONDS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            }
        }

        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        var inputSurface: Surface? = null
        var muxer: MediaMuxer? = null
        var started = false
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = codec.createInputSurface()
            muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            codec.start()
            started = true
            Log.i(TAG, "Selected encoder=${codec.name} ${width}x$height requestedFps=$fps bitrate=$BIT_RATE")
            return Resources(codec, inputSurface, muxer)
        } catch (failure: Throwable) {
            runCatching { inputSurface?.release() }
            runCatching { muxer?.release() }
            if (started) runCatching { codec.stop() }
            runCatching { codec.release() }
            throw failure
        }
    }

    private fun MediaFormat.integerOrNull(key: String): Int? =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null

    private companion object {
        const val TAG = "RideHudStress"
        const val BIT_RATE = 20_000_000
        const val I_FRAME_INTERVAL_SECONDS = 1
        const val DRAIN_TIMEOUT_US = 10_000L
        const val FINISH_TIMEOUT_MS = 30_000L
        val RELEASE_JOIN_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(2)
    }
}
