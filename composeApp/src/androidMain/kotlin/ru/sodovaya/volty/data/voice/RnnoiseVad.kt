package ru.sodovaya.volty.data.voice

import android.util.Log

/**
 * RNNoise used as a speech detector only: [probability] runs one 10 ms frame (480 samples, 48 kHz mono)
 * and returns how likely it is speech; the denoised output is discarded, the voice is never altered.
 * Built from xiph/rnnoise sources in src/androidMain/cpp. Not thread-safe; [close] frees the native state.
 */
internal class RnnoiseVad private constructor(private var handle: Long) : AutoCloseable {
    private val input = FloatArray(FRAME_SIZE)
    private val scratch = FloatArray(FRAME_SIZE)

    /** [frame] must hold [FRAME_SIZE] PCM16 samples; RNNoise takes them in the int16 range, as floats. */
    fun probability(frame: ShortArray): Float {
        if (handle == 0L || frame.size != FRAME_SIZE) return 0f
        for (i in 0 until FRAME_SIZE) input[i] = frame[i].toFloat()
        return nativeProcess(handle, input, scratch)
    }

    override fun close() {
        val owned = handle
        handle = 0L
        if (owned != 0L) nativeDestroy(owned)
    }

    companion object {
        const val FRAME_SIZE = 480
        private const val TAG = "RnnoiseVad"

        /** False when the library is missing or built for another frame size: the gate then stays off. */
        private val available: Boolean by lazy {
            runCatching { System.loadLibrary("volty_rnnoise"); nativeFrameSize() == FRAME_SIZE }
                .onFailure { Log.w(TAG, "RNNoise unavailable; the speech gate is disabled", it) }
                .getOrDefault(false)
        }

        fun createOrNull(): RnnoiseVad? =
            if (!available) null else nativeCreate().takeIf { it != 0L }?.let(::RnnoiseVad)

        @JvmStatic private external fun nativeCreate(): Long
        @JvmStatic private external fun nativeFrameSize(): Int
        @JvmStatic private external fun nativeProcess(handle: Long, input: FloatArray, scratch: FloatArray): Float
        @JvmStatic private external fun nativeDestroy(handle: Long)
    }
}
