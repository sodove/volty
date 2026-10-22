package ru.sodovaya.volty.debug

import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View

internal fun dispatchHistoryProbePinch(
    view: View,
    centerX: Float,
    centerY: Float,
    startSpan: Float,
    endSpan: Float,
    cancel: Boolean = false,
) {
    check(Looper.myLooper() == Looper.getMainLooper()) {
        "History probe gestures must be dispatched on the main thread"
    }
    require(centerX.isFinite() && centerY.isFinite() && startSpan.isFinite() && endSpan.isFinite()) {
        "Pinch coordinates and spans must be finite"
    }
    require(startSpan > 0f && endSpan > 0f) { "Pinch spans must be positive" }
    require(view.width > 0 && view.height > 0) { "Pinch target must have a non-empty size" }

    fun requireSpanInsideView(span: Float) {
        val leftX = centerX - span / 2f
        val rightX = centerX + span / 2f
        val y = centerY
        require(
            leftX.isFinite() && rightX.isFinite() &&
                leftX >= 0f && rightX < view.width.toFloat() &&
                y >= 0f && y < view.height.toFloat()
        ) {
            "Both pinch endpoints must be inside the view"
        }
    }

    requireSpanInsideView(startSpan)
    requireSpanInsideView(endSpan)

    HistoryProbePinchRun(view, centerX, centerY, startSpan, endSpan, cancel).start()
}

private class HistoryProbePinchRun(
    private val view: View,
    private val centerX: Float,
    private val centerY: Float,
    private val startSpan: Float,
    private val endSpan: Float,
    private val cancelAtEnd: Boolean,
) : Runnable, View.OnAttachStateChangeListener {
    private var frame = 0
    private var downSent = false
    private var pointerDown = false
    private var terminal = false
    private var cleanedUp = false
    private var currentSpan = startSpan
    private val downTime = SystemClock.uptimeMillis()

    fun start() {
        if (!view.isAttachedToWindow) return

        view.addOnAttachStateChangeListener(this)
        if (!view.isAttachedToWindow) {
            cancel()
            return
        }

        try {
            downSent = true
            dispatch(MotionEvent.ACTION_DOWN, pointerCount = 1, span = currentSpan)
            if (terminal) return

            pointerDown = true
            dispatch(pointerAction(MotionEvent.ACTION_POINTER_DOWN), pointerCount = 2, span = currentSpan)
            if (terminal) return

            scheduleNextFrame()
        } catch (failure: Throwable) {
            try {
                cancel()
            } catch (cancelFailure: Throwable) {
                failure.addSuppressed(cancelFailure)
            }
            throw failure
        }
    }

    override fun run() {
        if (terminal) return
        if (!view.isAttachedToWindow) {
            cancel()
            return
        }

        frame += 1
        val progress = frame.toFloat() / MOVE_FRAME_COUNT
        currentSpan = startSpan + (endSpan - startSpan) * progress

        try {
            dispatch(MotionEvent.ACTION_MOVE, pointerCount = 2, span = currentSpan)
            if (terminal) return

            if (frame < MOVE_FRAME_COUNT) {
                scheduleNextFrame()
            } else if (cancelAtEnd) {
                cancel()
            } else {
                finishNormally()
            }
        } catch (failure: Throwable) {
            try {
                cancel()
            } catch (cancelFailure: Throwable) {
                failure.addSuppressed(cancelFailure)
            }
            throw failure
        }
    }

    override fun onViewDetachedFromWindow(detachedView: View) {
        cancel()
    }

    override fun onViewAttachedToWindow(attachedView: View) = Unit

    private fun scheduleNextFrame() {
        if (terminal) return
        if (!view.isAttachedToWindow || !view.postDelayed(this, FRAME_DELAY_MS)) {
            cancel()
        }
    }

    private fun finishNormally() {
        if (terminal) return
        try {
            dispatch(pointerAction(MotionEvent.ACTION_POINTER_UP), pointerCount = 2, span = currentSpan)
            if (terminal) return
            pointerDown = false

            terminal = true
            dispatch(MotionEvent.ACTION_UP, pointerCount = 1, span = currentSpan)
        } catch (failure: Throwable) {
            if (!terminal) {
                try {
                    cancel()
                } catch (cancelFailure: Throwable) {
                    failure.addSuppressed(cancelFailure)
                }
            }
            throw failure
        } finally {
            if (terminal) cleanup()
        }
    }

    private fun cancel() {
        if (terminal) return
        terminal = true
        view.removeCallbacks(this)
        try {
            if (downSent) {
                dispatch(
                    MotionEvent.ACTION_CANCEL,
                    pointerCount = if (pointerDown) 2 else 1,
                    span = currentSpan,
                )
            }
        } finally {
            cleanup()
        }
    }

    private fun cleanup() {
        if (cleanedUp) return
        cleanedUp = true
        view.removeCallbacks(this)
        view.removeOnAttachStateChangeListener(this)
    }

    private fun dispatch(action: Int, pointerCount: Int, span: Float) {
        val leftX = centerX - span / 2f
        val rightX = centerX + span / 2f
        val properties = Array(pointerCount) { index ->
            MotionEvent.PointerProperties().apply {
                id = index
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coordinates = Array(pointerCount) { index ->
            MotionEvent.PointerCoords().apply {
                x = if (index == 0) leftX else rightX
                y = centerY
                pressure = 1f
                size = 1f
                touchMajor = 1f
                touchMinor = 1f
                orientation = 0f
            }
        }
        val event = MotionEvent.obtain(
            downTime,
            SystemClock.uptimeMillis().coerceAtLeast(downTime),
            action,
            pointerCount,
            properties,
            coordinates,
            0,
            0,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_TOUCHSCREEN,
            0,
        )
        try {
            view.dispatchTouchEvent(event)
        } finally {
            event.recycle()
        }
    }

    private fun pointerAction(action: Int): Int =
        action or (POINTER_INDEX shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)

    private companion object {
        const val FRAME_DELAY_MS = 16L
        const val MOVE_FRAME_COUNT = 12
        const val POINTER_INDEX = 1
    }
}
