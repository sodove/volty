package ru.sodovaya.volty.domain.video

/** RGB is fully opaque; ARGB stores opacity separately from the three color channels. */
fun overlayColorAlpha(color: String): Int =
    if (color.length == 9) color.substring(1, 3).toIntOrNull(16) ?: 255 else 255

fun overlayColorWithAlpha(color: String, alpha: Int): String {
    val rgb = when (color.length) { 7 -> color.drop(1); 9 -> color.drop(3); else -> return color }
    return "#${alpha.coerceIn(0, 255).toString(16).padStart(2, '0')}$rgb".uppercase()
}

/** A metric tile's two lines: the name, and the source it is read from in small type (mock D: «Ток» over «батарея»). */
data class OverlayMetricTileText(val title: String, val source: String?)

/**
 * Splits an editor metric name at its « · » source separator. **Retraction:** the tile used to print the whole string
 * on one or two wrapped lines, «Ток батареи · контроллер», where the mock has a title and a muted source line.
 */
fun overlayMetricTileText(name: String): OverlayMetricTileText {
    val at = name.indexOf(TILE_SOURCE_SEPARATOR)
    if (at < 0) return OverlayMetricTileText(name, null)
    val source = name.substring(at + TILE_SOURCE_SEPARATOR.length).trim()
    return OverlayMetricTileText(name.substring(0, at).trim(), source.ifEmpty { null })
}

private const val TILE_SOURCE_SEPARATOR = " · "

/** Numeric model echoes ("1" -> "1.0") must not replace the user's unfinished text. */
data class OverlayTextDraft(
    val text: String,
    val modelText: String = text,
    val focused: Boolean = false,
    val invalid: Boolean = false,
) {
    fun edited(candidate: String, accepted: Boolean): OverlayTextDraft = copy(text = candidate, invalid = !accepted)

    fun synced(value: String): OverlayTextDraft =
        if (!focused && value != modelText) copy(text = value, modelText = value, invalid = false)
        else copy(modelText = value)
}

/**
 * Switches [widget] to [metric] and takes the label with it.
 *
 * **Retraction.** The editor used to change only the metric and the unit, so a card could read «Скорость · контроллер»
 * over a charge percentage, burned into the file for good. A label that is the old metric's own default (or blank)
 * becomes blank, which means "follow the metric"; text the rider typed is theirs and stays. An icon that was only
 * the old metric's automatic one goes back to AUTO; a deliberate choice stays.
 */
fun overlayWidgetWithMetric(widget: OverlayWidget, metric: VideoMetric): OverlayWidget {
    if (metric == widget.metric) return widget
    val icon = if (widget.icon == OverlayIcon.AUTO || widget.icon == autoOverlayIcon(widget.metric)) {
        OverlayIcon.AUTO
    } else {
        widget.icon
    }
    val iconName = widget.iconName?.takeIf { it != autoOverlayIconName(widget.metric) }
    return widget.copy(
        metric = metric,
        label = if (isAutoOverlayLabel(widget)) "" else widget.label,
        icon = icon,
        iconName = iconName,
        unit = metric.unit,
    )
}
