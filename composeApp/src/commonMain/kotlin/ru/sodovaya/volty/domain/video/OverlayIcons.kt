package ru.sodovaya.volty.domain.video

/** The groups of telemetry-studio's icon map, in its order. */
enum class OverlayIconGroup { POWER, BATTERY, TEMPERATURE, SPEED, GPS, TRANSPORT, DATA, GENERAL }

/** One Font Awesome Free 6.7.2 Solid glyph: its FA name, the codepoint it is drawn from, and its picker group. */
data class OverlayIconInfo(val name: String, val codepoint: Char, val group: OverlayIconGroup)

/**
 * The overlay's icons: telemetry-studio's `packages/engine/src/widgets/icons.ts`, name for name and codepoint for
 * codepoint, so a TS template's `config.icon` maps 1:1 and a later import needs no table. The glyphs come from the
 * bundled `fa-solid-900.ttf` (FA Free 6.7.2, SIL OFL 1.1; icons CC BY 4.0, see THIRD_PARTY_NOTICES.md).
 *
 * **Retraction:** the overlay used to draw six hand-picked paths, and its AUTO entry was a bare circle; the rider had
 * no icon to choose. TS's own quirk is kept on purpose: its `wave` is codepoint U+F0C9, which Font Awesome draws as
 * three bars, so a TS template that says `wave` looks here exactly as it did there.
 */
object OverlayIcons {
    val all: List<OverlayIconInfo> = listOf(
        icon("bolt", '', OverlayIconGroup.POWER),
        icon("plug", '', OverlayIconGroup.POWER),
        icon("car-battery", '', OverlayIconGroup.POWER),
        icon("charging-station", '', OverlayIconGroup.POWER),
        icon("battery-full", '', OverlayIconGroup.BATTERY),
        icon("battery-three-quarters", '', OverlayIconGroup.BATTERY),
        icon("battery-half", '', OverlayIconGroup.BATTERY),
        icon("battery-quarter", '', OverlayIconGroup.BATTERY),
        icon("battery-empty", '', OverlayIconGroup.BATTERY),
        icon("temperature-high", '', OverlayIconGroup.TEMPERATURE),
        icon("temperature-half", '', OverlayIconGroup.TEMPERATURE),
        icon("temperature-low", '', OverlayIconGroup.TEMPERATURE),
        icon("fire", '', OverlayIconGroup.TEMPERATURE),
        icon("snowflake", '', OverlayIconGroup.TEMPERATURE),
        icon("gauge", '', OverlayIconGroup.SPEED),
        icon("gauge-simple", '', OverlayIconGroup.SPEED),
        icon("arrow-up", '', OverlayIconGroup.SPEED),
        icon("arrow-down", '', OverlayIconGroup.SPEED),
        icon("arrow-right", '', OverlayIconGroup.SPEED),
        icon("arrows-up-down", '', OverlayIconGroup.SPEED),
        icon("rocket", '', OverlayIconGroup.SPEED),
        icon("location-dot", '', OverlayIconGroup.GPS),
        icon("satellite", '', OverlayIconGroup.GPS),
        icon("compass", '', OverlayIconGroup.GPS),
        icon("map-pin", '', OverlayIconGroup.GPS),
        icon("car", '', OverlayIconGroup.TRANSPORT),
        icon("motorcycle", '', OverlayIconGroup.TRANSPORT),
        icon("bicycle", '', OverlayIconGroup.TRANSPORT),
        icon("road", '', OverlayIconGroup.TRANSPORT),
        icon("chart-line", '', OverlayIconGroup.DATA),
        icon("chart-bar", '', OverlayIconGroup.DATA),
        icon("signal", '', OverlayIconGroup.DATA),
        icon("wave", '', OverlayIconGroup.DATA),
        icon("percent", '', OverlayIconGroup.DATA),
        icon("gear", '', OverlayIconGroup.GENERAL),
        icon("clock", '', OverlayIconGroup.GENERAL),
        icon("stopwatch", '', OverlayIconGroup.GENERAL),
        icon("weight", '', OverlayIconGroup.GENERAL),
        icon("circle-info", '', OverlayIconGroup.GENERAL),
        icon("triangle-exclamation", '', OverlayIconGroup.GENERAL),
        icon("shield", '', OverlayIconGroup.GENERAL),
        icon("eye", '', OverlayIconGroup.GENERAL),
        icon("star", '', OverlayIconGroup.GENERAL),
        icon("heart", '', OverlayIconGroup.GENERAL),
        icon("flag", '', OverlayIconGroup.GENERAL),
        icon("bell", '', OverlayIconGroup.GENERAL),
        icon("hashtag", '', OverlayIconGroup.GENERAL),
    )

    private val byName: Map<String, OverlayIconInfo> = all.associateBy { it.name }

    fun byName(name: String): OverlayIconInfo? = byName[name]

    const val WARNING = "triangle-exclamation"

    private fun icon(name: String, codepoint: Char, group: OverlayIconGroup) = OverlayIconInfo(name, codepoint, group)
}

/**
 * The FA name an older [OverlayIcon] stands for. The enum stays so that every saved document decodes; its values are
 * read through this table. [OverlayIcon.AUTO] has no name of its own: it is the metric's, see [autoOverlayIconName].
 */
fun overlayIconName(icon: OverlayIcon): String? = when (icon) {
    OverlayIcon.AUTO -> null
    OverlayIcon.BATTERY -> "battery-full"
    OverlayIcon.BOLT -> "bolt"
    OverlayIcon.TEMPERATURE -> "temperature-half"
    OverlayIcon.GAUGE -> "gauge"
    OverlayIcon.WARNING -> OverlayIcons.WARNING
    OverlayIcon.CURRENT -> "plug"
}

/** The icon a widget gets when it has none of its own. Never the warning sign, which is earned; see [overlayIconShown]. */
fun autoOverlayIconName(metric: VideoMetric): String = when (metric) {
    VideoMetric.SPEED -> "gauge"
    VideoMetric.DUTY -> "gauge-simple"
    VideoMetric.ELAPSED -> "stopwatch"
    VideoMetric.DISTANCE -> "road"
    VideoMetric.SOC -> "battery-full"
    VideoMetric.VOLTAGE -> "car-battery"
    VideoMetric.BATTERY_POWER, VideoMetric.CONTROLLER_POWER -> "bolt"
    VideoMetric.BATTERY_CURRENT, VideoMetric.CONTROLLER_CURRENT, VideoMetric.INPUT_CURRENT, VideoMetric.MOTOR_CURRENT -> "plug"
    VideoMetric.ESC_TEMPERATURE, VideoMetric.MOTOR_TEMPERATURE, VideoMetric.BATTERY_TEMPERATURE -> "temperature-half"
}

/** The widget's chosen icon by FA name: [OverlayWidget.iconName] first, then the older enum, then the metric's. */
fun overlayIconChosen(widget: OverlayWidget): String =
    widget.iconName?.takeIf { OverlayIcons.byName(it) != null }
        ?: overlayIconName(widget.icon)
        ?: autoOverlayIconName(widget.metric)

/** True when the widget follows its metric's icon (nothing chosen, or only the old enum's AUTO). */
fun overlayIconIsAuto(widget: OverlayWidget): Boolean =
    widget.iconName?.takeIf { OverlayIcons.byName(it) != null } == null && widget.icon == OverlayIcon.AUTO
