package ru.sodovaya.volty.domain.video

import ru.sodovaya.volty.domain.stats.MotionReadings
import ru.sodovaya.volty.util.UnitFormatter
import ru.sodovaya.volty.util.UnitSystem
import kotlin.math.roundToInt

/**
 * Every word and unit that is burned into a ride video, resolved by the caller from the localized resources
 * (the renderer runs on a camera thread and cannot read Compose resources itself). The defaults are English
 * and exist for tests and for the first frames before the screen has handed over the real strings.
 *
 * **Retraction.** An earlier revision burned `U batt`, `SPEED`, `km/h` and the like in as literals, so a Russian
 * rider's video said `SPEED`. Burned-in text cannot be fixed after the ride, so the literals are gone: the
 * template labels are now empty ("follow the metric") and this class supplies the words.
 */
data class VideoOverlayStrings(
    val labels: Map<VideoMetric, String> = DEFAULT_LABELS,
    val percent: String = "%",
    val celsius: String = "°C",
    val volt: String = "V",
    val amp: String = "A",
    val watt: String = "W",
    val kmh: String = "km/h",
    val demo: String = "DEMO",
    val km: String = "km",
    val noLink: String = "NO LINK",
) {
    fun label(metric: VideoMetric): String = labels[metric] ?: DEFAULT_LABELS.getValue(metric)

    /**
     * The unit for [metric] in [system]. Speed is the only metric the unit system changes, and its imperial
     * unit comes from [UnitFormatter] so the video can never disagree with the dashboard.
     */
    fun unit(metric: VideoMetric, system: UnitSystem): String = when (metric) {
        VideoMetric.SPEED -> if (system == UnitSystem.IMPERIAL) UnitFormatter.speedUnit(system) else kmh
        VideoMetric.DUTY, VideoMetric.SOC -> percent
        VideoMetric.ESC_TEMPERATURE, VideoMetric.MOTOR_TEMPERATURE, VideoMetric.BATTERY_TEMPERATURE -> celsius
        VideoMetric.MOTOR_CURRENT, VideoMetric.CONTROLLER_CURRENT, VideoMetric.INPUT_CURRENT, VideoMetric.BATTERY_CURRENT -> amp
        VideoMetric.CONTROLLER_POWER, VideoMetric.BATTERY_POWER -> watt
        VideoMetric.VOLTAGE -> volt
        VideoMetric.ELAPSED -> ""
        VideoMetric.DISTANCE -> if (system == UnitSystem.IMPERIAL) UnitFormatter.distanceUnit(system) else km
    }

    companion object {
        val DEFAULT_LABELS: Map<VideoMetric, String> = mapOf(
            VideoMetric.SPEED to "Speed",
            VideoMetric.DUTY to "Duty",
            VideoMetric.ESC_TEMPERATURE to "ESC temp",
            VideoMetric.MOTOR_TEMPERATURE to "Motor temp",
            VideoMetric.MOTOR_CURRENT to "Motor current",
            VideoMetric.CONTROLLER_CURRENT to "ESC current",
            VideoMetric.INPUT_CURRENT to "Input current",
            VideoMetric.CONTROLLER_POWER to "ESC power",
            VideoMetric.SOC to "Charge",
            VideoMetric.VOLTAGE to "Voltage",
            VideoMetric.BATTERY_CURRENT to "Batt current",
            VideoMetric.BATTERY_POWER to "Batt power",
            VideoMetric.BATTERY_TEMPERATURE to "Batt temp",
            VideoMetric.ELAPSED to "Time",
            VideoMetric.DISTANCE to "Distance",
        )
    }
}

/**
 * The text a widget's value shows: `—` for anything unearned, otherwise the rounded value in the display unit.
 * Speed is converted through [UnitFormatter] (miles honoured); an estimated duty is dressed by
 * [MotionReadings.dutyText] exactly as every dashboard does (`~` prefix, `≥100` at the ceiling).
 *
 * The number goes through [roundToInt] and then an Int, never `String.format("%.0f")`: that printed `-0` for
 * -0.3 (an ASCII hyphen over a value that is not negative at all). A real negative carries U+2212, as the mock asks,
 * and so does [videoValueExemplar]. An earlier revision kept an ASCII hyphen on both sides.
 */
fun videoValueText(metric: VideoMetric, reading: VideoReading?, system: UnitSystem): String {
    val value = reading?.value?.takeIf { it.isFinite() } ?: return NO_VALUE
    val text = when (metric) {
        VideoMetric.DUTY -> MotionReadings.dutyText(value.toFloat(), reading.estimated, reading.atCeiling)
        VideoMetric.ELAPSED -> return videoElapsedText(value.toLong())
        VideoMetric.DISTANCE -> tenths(UnitFormatter.speedValue(value.toFloat(), system).toDouble())
        VideoMetric.VOLTAGE -> estimatedPrefix(reading) + tenths(value)
        VideoMetric.SPEED -> estimatedPrefix(reading) + UnitFormatter.speedValue(value.toFloat(), system).toDouble().roundToInt()
        else -> estimatedPrefix(reading) + value.roundToInt()
    }
    return text.replace('-', MINUS)
}

private fun estimatedPrefix(reading: VideoReading) = if (reading.estimated) "~" else ""

/** One decimal without `String.format`, and no `-0.0`. */
private fun tenths(value: Double): String {
    val scaled = (value * 10).roundToInt()
    val magnitude = kotlin.math.abs(scaled)
    return (if (scaled < 0) "-" else "") + "${magnitude / 10}.${magnitude % 10}"
}

/** `12:48`, and `1:02:48` from the first hour on; recording length is unlimited. */
fun videoElapsedText(totalSeconds: Long): String {
    val seconds = totalSeconds.coerceAtLeast(0)
    val hours = seconds / 3600
    val minutes = ((seconds % 3600) / 60).toString().padStart(2, '0')
    val rest = (seconds % 60).toString().padStart(2, '0')
    return if (hours > 0) "$hours:$minutes:$rest" else "$minutes:$rest"
}

/** The widest plausible value text for [metric], so a card never changes size when 9 becomes 10. */
fun videoValueExemplar(metric: VideoMetric): String = when (metric) {
    VideoMetric.CONTROLLER_POWER, VideoMetric.BATTERY_POWER -> "${MINUS}99999"
    VideoMetric.MOTOR_CURRENT, VideoMetric.CONTROLLER_CURRENT, VideoMetric.INPUT_CURRENT, VideoMetric.BATTERY_CURRENT -> "${MINUS}999"
    VideoMetric.DUTY -> "≥100"
    VideoMetric.ELAPSED -> "9:59:59"
    VideoMetric.DISTANCE -> "999.9"
    VideoMetric.SOC, VideoMetric.SPEED -> "199"
    VideoMetric.VOLTAGE -> "199.9"
    else -> "${MINUS}99"
}

const val NO_VALUE = "—"
private const val MINUS = '−'

/** Each metric's default label before localization; a stored label equal to one of these is not the user's. */
private val LEGACY_DEFAULT_LABELS: Map<VideoMetric, Set<String>> = mapOf(
    VideoMetric.SPEED to setOf("Speed", "SPEED", "v"),
    VideoMetric.DUTY to setOf("PWM ESC", "PWM"),
    VideoMetric.ESC_TEMPERATURE to setOf("T ESC"),
    VideoMetric.MOTOR_TEMPERATURE to setOf("T motor"),
    VideoMetric.MOTOR_CURRENT to setOf("I motor"),
    VideoMetric.CONTROLLER_CURRENT to setOf("I ESC"),
    VideoMetric.INPUT_CURRENT to setOf("Input current"),
    VideoMetric.CONTROLLER_POWER to setOf("P ESC"),
    VideoMetric.SOC to setOf("SOC batt", "SOC"),
    VideoMetric.VOLTAGE to setOf("U batt"),
    VideoMetric.BATTERY_CURRENT to setOf("I batt"),
    VideoMetric.BATTERY_POWER to setOf("P batt"),
    VideoMetric.BATTERY_TEMPERATURE to setOf("T batt"),
    VideoMetric.ELAPSED to emptySet(),
    VideoMetric.DISTANCE to emptySet(),
)

/**
 * True when [widget]'s label is the metric's own default rather than something the rider typed: blank, or one
 * of the English literals earlier revisions wrote into saved presets for this same metric.
 */
fun isAutoOverlayLabel(widget: OverlayWidget): Boolean =
    widget.label.isBlank() || widget.label in LEGACY_DEFAULT_LABELS.getValue(widget.metric)

/** The label that is burned in: the rider's own text, else the localized default for the metric. */
fun overlayLabel(widget: OverlayWidget, strings: VideoOverlayStrings): String =
    if (isAutoOverlayLabel(widget)) strings.label(widget.metric) else widget.label

/**
 * The icon an [OverlayIcon.AUTO] widget draws for [metric]. **Retraction:** DUTY's automatic icon used to be
 * [OverlayIcon.WARNING], a warning sign on every normal ride. Only a fired rule may show one; see [overlayIconShown].
 */
fun autoOverlayIcon(metric: VideoMetric): OverlayIcon = when (metric) {
    VideoMetric.SPEED, VideoMetric.DUTY, VideoMetric.ELAPSED, VideoMetric.DISTANCE -> OverlayIcon.GAUGE
    VideoMetric.SOC, VideoMetric.VOLTAGE -> OverlayIcon.BATTERY
    VideoMetric.BATTERY_POWER, VideoMetric.CONTROLLER_POWER -> OverlayIcon.BOLT
    VideoMetric.BATTERY_CURRENT, VideoMetric.CONTROLLER_CURRENT, VideoMetric.INPUT_CURRENT, VideoMetric.MOTOR_CURRENT -> OverlayIcon.CURRENT
    else -> OverlayIcon.TEMPERATURE
}

/**
 * The FA name drawn right now. A warning sign means a warning: a chosen [OverlayIcons.WARNING] shows only while a rule
 * has fired, otherwise the metric's own icon. It is never AUTO and never a blank circle.
 */
fun overlayIconShown(widget: OverlayWidget, reading: VideoReading?): String {
    val icon = overlayIconChosen(widget)
    return if (icon == OverlayIcons.WARNING && !overlayRuleFired(widget, reading)) autoOverlayIconName(widget.metric) else icon
}
