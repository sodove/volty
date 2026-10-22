package ru.sodovaya.volty.presentation.history

import androidx.compose.foundation.gestures.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import ru.sodovaya.volty.domain.history.HistoryRange
import kotlin.math.abs

/** Input routing only. The component owns preview, cancellation and read commit.
 * Vertical one-finger motion remains available to the parent lazy chart stack.
 */
internal suspend fun PointerInputScope.detectHistoryChartGestures(owner: HistoryComponent, range: HistoryRange,
    padding: Float, onSelectTime: (Long?) -> Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val width = size.width - 2 * padding
        if (width <= 0) return@awaitEachGesture
        var token: HistoryWindowGestureToken? = null
        var completed = false
        var totalPan = Offset.Zero
        var totalZoom = 1f
        var multiple = false
        val slop = viewConfiguration.touchSlop
        try {
            while (true) {
                val event = awaitPointerEvent()
                // Android cancellation is delivered as consumed pointer-up changes.
                if (event.changes.any { it.isConsumed }) break
                val pressed = event.changes.any { it.pressed }
                multiple = multiple || event.changes.count { it.pressed } > 1
                if (!pressed) {
                    val active = token
                    if (active != null) owner.finishWindowGesture(active)
                    else if (!multiple && totalPan.getDistance() < slop)
                        onSelectTime(historyTimeAtFraction(range, (down.position.x - padding) / width))
                    completed = true
                    break
                }
                val zoom = event.calculateZoom()
                val pan = event.calculatePan()
                val centroid = event.calculateCentroid(useCurrent = false)
                var zoomChange = zoom
                var panChange = pan.x
                if (token == null) {
                    totalPan += pan; totalZoom *= zoom
                    val zoomMotion = abs(1 - totalZoom) * event.calculateCentroidSize(useCurrent = false)
                    val horizontal = abs(totalPan.x) > slop && (multiple || abs(totalPan.x) > abs(totalPan.y))
                    if (zoomMotion > slop || horizontal) {
                        token = owner.beginWindowGesture() ?: break
                        zoomChange = totalZoom; panChange = totalPan.x
                    }
                }
                val active = token
                if (active != null) {
                    owner.transformWindowGesture(active, zoomChange.toDouble(),
                        ((centroid.x - padding) / width).toDouble(), (panChange / width).toDouble())
                    event.changes.forEach { it.consume() }
                } else {
                    val final = awaitPointerEvent(PointerEventPass.Final)
                    if (final.changes.any { it.isConsumed }) break
                }
            }
        } finally {
            if (!completed) token?.let(owner::cancelWindowGesture)
        }
    }
}
