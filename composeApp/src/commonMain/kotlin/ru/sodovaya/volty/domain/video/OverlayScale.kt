package ru.sodovaya.volty.domain.video

import ru.sodovaya.volty.domain.stats.GaugeScale
import ru.sodovaya.volty.util.UnitFormatter
import ru.sodovaya.volty.util.UnitSystem

/** Round tops for a speed dial, in the displayed unit: a 25 km/h bicycle and a 120 km/h wheel both use the sweep. */
val OVERLAY_SPEED_RUNGS: List<Float> = listOf(30f, 40f, 60f, 80f, 100f, 120f, 150f, 200f)

private fun rungsFor(metric: VideoMetric): List<Float>? = when (metric) {
    VideoMetric.SPEED -> OVERLAY_SPEED_RUNGS
    VideoMetric.MOTOR_CURRENT, VideoMetric.CONTROLLER_CURRENT, VideoMetric.BATTERY_CURRENT -> GaugeScale.CURRENT_RUNGS_A
    VideoMetric.CONTROLLER_POWER, VideoMetric.BATTERY_POWER -> GaugeScale.POWER_RUNGS_W
    else -> null
}

/** True when [metric] has a ladder, so a dial of it can learn its range. */
fun overlayHasLadder(metric: VideoMetric): Boolean = rungsFor(metric) != null

/**
 * The live ranges of [OverlayArc.autoScale] dials, through [GaugeScale.displayRung]: widening is instant and a dial
 * never narrows, so a range steps a few times per ride and then holds. Nothing here is persisted: a recording starts
 * from the bottom rung of whatever the vehicle shows during it.
 */
class OverlayScaleTracker {
    private val rungs = mutableMapOf<VideoMetric, Float>()
    private var vehicleId: String? = null
    private var system: UnitSystem? = null

    /** A lost link keeps the ranges: [snapshot] is null then, and the dial must not jump when the data comes back. */
    fun update(preset: OverlayPreset, snapshot: VideoSnapshot?, system: UnitSystem): Map<VideoMetric, Float> {
        if (snapshot != null && snapshot.vehicleId != vehicleId || system != this.system) {
            rungs.clear()
            vehicleId = snapshot?.vehicleId ?: vehicleId
            this.system = system
        }
        for (widget in preset.widgets) {
            if (!widget.arc.autoScale) continue
            val ladder = rungsFor(widget.metric) ?: continue
            val sample = snapshot?.readings?.get(widget.metric)?.value?.takeIf(Double::isFinite)?.let { shownValue(widget.metric, it, system) }
            rungs[widget.metric] = GaugeScale.displayRung(rungs[widget.metric] ?: 0f, 0f, sample ?: 0f, ladder)
        }
        return rungs.toMap()
    }
}

private fun shownValue(metric: VideoMetric, value: Double, system: UnitSystem): Float =
    if (metric == VideoMetric.SPEED) UnitFormatter.speedValue(value.toFloat(), system) else value.toFloat()

/** The top of [widget]'s dial or bar: the learned range when it has one, otherwise the stored maximum. */
fun overlayScaleMax(widget: OverlayWidget, scales: Map<VideoMetric, Float>): Float =
    (if (widget.arc.autoScale) scales[widget.metric] else null) ?: widget.arc.max

/**
 * How full [widget]'s dial or bar is, or null when there is no value to show. Null draws an EMPTY dial: an unknown
 * must never look like a measured zero, or like the last value it had.
 */
fun overlayFraction(
    widget: OverlayWidget,
    reading: VideoReading?,
    system: UnitSystem,
    scales: Map<VideoMetric, Float>,
    dials: Map<VideoMetric, Double> = emptyMap(),
): Double? {
    // Unknown first: a smoothed value never brings back a reading that has gone.
    reading?.value?.takeIf(Double::isFinite) ?: return null
    val value = dials[widget.metric] ?: reading.value
    val shown = if (widget.arc.autoScale) shownValue(widget.metric, value, system).toDouble() else value
    val min = widget.arc.min.toDouble()
    val span = overlayScaleMax(widget, scales).toDouble() - min
    if (span <= 0.0) return null
    return ((shown - min) / span).coerceIn(0.0, 1.0)
}

/** The step of a dial's numbers: at most seven labels, always round. */
fun overlayTickStep(max: Float): Int = when {
    max <= 40f -> 10
    max <= 120f -> 20
    else -> (max / 5f).toInt().coerceAtLeast(1)
}

/**
 * True when nothing at all is coming from the vehicle, which is what the burned-in «НЕТ СВЯЗИ» claims. One live
 * reading anywhere is enough to withhold it: a battery-only vehicle under a speed preset has a link, and its dashes
 * say the rest. Non-finite values do not count as live.
 */
fun overlayNoLink(snapshot: VideoSnapshot?): Boolean =
    snapshot == null || snapshot.readings.values.none { it.value?.isFinite() == true }
