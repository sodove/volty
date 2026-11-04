package ru.sodovaya.volty.domain.video

import kotlin.math.abs
import kotlin.math.round

/** Supplies the per-widget horizontal position used while drawing overlay content. */
fun interface OverlayContentPosition {
    fun position(widgetId: String, targetX: Float): Float
}

/** Moves flowing overlay content toward its scene position once per frame, matching Telemetry Studio's 15% easing. */
class OverlayContentMotion : OverlayContentPosition {
    private val exactPositions = mutableMapOf<String, Float>()
    private val quantizedPositions = mutableMapOf<String, Float>()
    private val framePositions = mutableMapOf<String, Float>()

    private var hasContext = false
    private var context: Any? = null
    private var lastFrameMillis: Long? = null

    /** A stable snapshot for draw-key construction; returned positions themselves are never quantized. */
    val positions: Map<String, Float>
        get() = quantizedPositions.toMap()

    /** Starts a frame and resets motion when its scene context or frame clock is discontinuous. */
    fun beginFrame(context: Any, frameMillis: Long) {
        val previousTime = lastFrameMillis
        if (hasContext && this.context == context && previousTime == frameMillis) return
        val timeReset = previousTime != null &&
            (frameMillis < previousTime || exceedsFrameGap(previousTime, frameMillis))
        if (!hasContext || this.context != context || timeReset) {
            exactPositions.clear()
            quantizedPositions.clear()
        }
        this.context = context
        hasContext = true
        lastFrameMillis = frameMillis
        framePositions.clear()
    }

    override fun position(widgetId: String, targetX: Float): Float {
        framePositions[widgetId]?.let { return it }

        val previous = exactPositions[widgetId]
        if (!targetX.isFinite()) {
            val held = previous ?: 0f
            framePositions[widgetId] = held
            return held
        }

        val next = when {
            previous == null -> targetX
            abs(targetX.toDouble() - previous.toDouble()) <= SETTLE_DISTANCE_PX -> targetX
            else -> (previous.toDouble() + (targetX.toDouble() - previous.toDouble()) * STEP).toFloat()
        }
        exactPositions[widgetId] = next
        quantizedPositions[widgetId] = quantize(next)
        framePositions[widgetId] = next
        return next
    }

    private fun exceedsFrameGap(previous: Long, current: Long): Boolean =
        current > previous && previous <= Long.MAX_VALUE - MAX_FRAME_GAP_MILLIS &&
            current > previous + MAX_FRAME_GAP_MILLIS

    private fun quantize(value: Float): Float = (round(value.toDouble() * DRAW_KEY_STEPS_PER_PIXEL) /
        DRAW_KEY_STEPS_PER_PIXEL).toFloat()

    private companion object {
        const val STEP = 0.15
        const val SETTLE_DISTANCE_PX = 0.05
        const val MAX_FRAME_GAP_MILLIS = 250L
        const val DRAW_KEY_STEPS_PER_PIXEL = 8.0
    }
}
