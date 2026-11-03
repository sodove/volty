package ru.sodovaya.volty.domain.video

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Applies matching thresholds in order; a later rule replaces fields it supplies. */
fun overlayStyle(widget: OverlayWidget, reading: VideoReading?): OverlayStyle {
    val value = reading?.value?.takeIf(Double::isFinite) ?: return widget.style
    var result = widget.style

    widget.rules.forEach { rule ->
        if (!rule.threshold.isFinite() || !ruleMatches(value, rule)) return@forEach
        result = result.copy(
            foreground = rule.foreground?.takeIf(::isOverlayColor) ?: result.foreground,
            background = rule.background?.takeIf(::isOverlayColor) ?: result.background,
        )
    }
    return result
}

/** True while at least one of [widget]'s rules matches a known value. Unknown never fires a rule. */
fun overlayRuleFired(widget: OverlayWidget, reading: VideoReading?): Boolean {
    val value = reading?.value?.takeIf(Double::isFinite) ?: return false
    return widget.rules.any { it.threshold.isFinite() && ruleMatches(value, it) }
}

/**
 * The one widget worth a chip of its own: the first whose every rule has fired, so only a top threshold (duty 85,
 * temperature 80, charge 15) speaks up. The lower tiers already recolour their value, and a chip on each would talk
 * over the whole ride.
 */
fun overlayWarning(preset: OverlayPreset, snapshot: VideoSnapshot?): OverlayWidget? = preset.widgets.firstOrNull { widget ->
    val value = snapshot?.readings?.get(widget.metric)?.value?.takeIf(Double::isFinite)
    value != null && widget.rules.isNotEmpty() && widget.rules.all { it.threshold.isFinite() && ruleMatches(value, it) }
}

private fun ruleMatches(value: Double, rule: OverlayRule): Boolean = when (rule.comparison) {
    OverlayComparison.GT -> value > rule.threshold
    OverlayComparison.GTE -> value >= rule.threshold
    OverlayComparison.LT -> value < rule.threshold
    OverlayComparison.LTE -> value <= rule.threshold
    OverlayComparison.EQ -> value == rule.threshold
    OverlayComparison.NE -> value != rule.threshold
}

/** Returns true only for bounded, render-safe user presets. */
fun validOverlayPreset(preset: OverlayPreset): Boolean {
    if (!preset.id.isBoundedText(MAX_ID_LENGTH) || !preset.name.isBoundedText(MAX_NAME_LENGTH)) return false
    if (preset.widgets.isEmpty() || preset.widgets.size > MAX_WIDGETS) return false
    if (preset.widgets.map(OverlayWidget::id).toSet().size != preset.widgets.size) return false
    if (preset.layout?.let { isValidFrameSpec(it.portrait) && isValidFrameSpec(it.landscape) } == false) return false

    return preset.widgets.all { widget ->
        widget.id.isBoundedText(MAX_ID_LENGTH) &&
            widget.label.length <= MAX_LABEL_LENGTH &&
            widget.unit.length <= MAX_UNIT_LENGTH &&
            // An unknown name is not an error (a newer TS list, say): it falls back to the metric's icon.
            (widget.iconName?.length ?: 0) <= MAX_ID_LENGTH &&
            isValidStyle(widget.style) &&
            isValidArc(widget.arc) &&
            isValidBar(widget.bar) &&
            (widget.placement?.let { isValidBox(it.portrait) && isValidBox(it.landscape) } != false) &&
            widget.rules.size <= MAX_RULES_PER_WIDGET &&
            widget.rules.all(::isValidRule)
    }
}

private fun isValidStyle(style: OverlayStyle): Boolean =
    isOverlayColor(style.background) &&
        isOverlayColor(style.foreground) &&
        style.fontSize.isFinite() && style.fontSize in MIN_FONT_SIZE..MAX_FONT_SIZE &&
        style.cornerRadius.isFinite() && style.cornerRadius in 0f..MAX_CORNER_RADIUS &&
        style.padding.isFinite() && style.padding in 0f..MAX_PADDING &&
        (style.accent == null || isOverlayColor(style.accent))

private fun isValidBar(bar: OverlayBar): Boolean =
    bar.height.isFinite() && bar.height in 1f..MAX_BAR_HEIGHT && bar.segments in 0..MAX_BAR_SEGMENTS

/** A hidden side is valid. Lengths are bounded so a hand-edited document cannot ask for a frame-sized glyph. */
private fun isValidBox(box: OverlayBox?): Boolean = box == null || (
    listOf(box.dx, box.dy).all { it.isFinite() && it in -MAX_BOX_LENGTH..MAX_BOX_LENGTH } &&
        listOf(box.width, box.height).all { it.isFinite() && it in 1f..MAX_BOX_LENGTH } &&
        listOf(box.valueSize, box.unitSize, box.labelSize).all { it.isFinite() && it in 0f..MAX_BOX_TEXT }
    )

private fun isValidFrameSpec(spec: OverlayFrameSpec): Boolean =
    listOf(spec.scrimTop, spec.scrimBottom, spec.chipY).all { it.isFinite() && it in 0f..MAX_BOX_LENGTH }

private fun isValidArc(arc: OverlayArc): Boolean =
    arc.x.isFinite() && arc.x in 0f..100f &&
        arc.y.isFinite() && arc.y in 0f..100f &&
        arc.radius.isFinite() && arc.radius in MIN_ARC_RADIUS..MAX_ARC_RADIUS &&
        arc.min.isFinite() && arc.min >= -MAX_ARC_VALUE &&
        arc.max.isFinite() && arc.max <= MAX_ARC_VALUE &&
        arc.max > arc.min &&
        arc.thickness.isFinite() && arc.thickness in MIN_ARC_THICKNESS..MAX_ARC_THICKNESS

private fun isValidRule(rule: OverlayRule): Boolean =
    rule.threshold.isFinite() && rule.threshold in -MAX_RULE_THRESHOLD..MAX_RULE_THRESHOLD &&
        (rule.foreground == null || isOverlayColor(rule.foreground)) &&
        (rule.background == null || isOverlayColor(rule.background))

private fun String.isBoundedText(maxLength: Int): Boolean = isNotBlank() && length <= maxLength

private fun isOverlayColor(color: String): Boolean {
    if ((color.length != 7 && color.length != 9) || color.firstOrNull() != '#') return false
    return color.drop(1).all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
}

object OverlayCodec {
    /** Raised from 64 K: placements made a full preset about 10 K, and 32 saved ones must still fit. */
    private const val MAX_DOCUMENT_CHARS = 512 * 1024
    private const val MAX_SAVED_PRESETS = 32
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun encode(document: OverlayDocument): String {
        require(isValidDocument(document)) { "Overlay document is invalid" }
        val encoded = json.encodeToString(document)
        require(encoded.length <= MAX_DOCUMENT_CHARS) { "Overlay document is too large" }
        return encoded
    }

    fun decode(text: String): OverlayDocument {
        if (text.length > MAX_DOCUMENT_CHARS) return OverlayDocument()
        val document = runCatching { json.decodeFromString<OverlayDocument>(text) }.getOrNull()
            ?: return OverlayDocument()
        val valid = document.takeIf(::isValidDocument) ?: return OverlayDocument()
        return valid.copy(selected = OverlayLegacyTemplates.upgraded(valid.selected), saved = valid.saved.map(OverlayLegacyTemplates::upgraded))
    }

    private fun isValidDocument(document: OverlayDocument): Boolean =
        document.version == 1 &&
            validOverlayPreset(document.selected) &&
            document.saved.size <= MAX_SAVED_PRESETS &&
            document.saved.map(OverlayPreset::id).toSet().size == document.saved.size &&
            document.saved.all(::validOverlayPreset)
}

private const val MAX_WIDGETS = 24
private const val MAX_RULES_PER_WIDGET = 16
private const val MAX_ID_LENGTH = 64
private const val MAX_NAME_LENGTH = 64
private const val MAX_LABEL_LENGTH = 64
private const val MAX_UNIT_LENGTH = 16
private const val MIN_FONT_SIZE = 8f
private const val MAX_FONT_SIZE = 96f
private const val MAX_CORNER_RADIUS = 64f
private const val MAX_PADDING = 100f
private const val MIN_ARC_RADIUS = 1f
private const val MAX_ARC_RADIUS = 960f
private const val MAX_ARC_VALUE = 100_000f
private const val MIN_ARC_THICKNESS = 1f
private const val MAX_ARC_THICKNESS = 50f
private const val MAX_RULE_THRESHOLD = 1_000_000_000.0
private const val MAX_BOX_LENGTH = 4_000f
private const val MAX_BOX_TEXT = 400f
private const val MAX_BAR_HEIGHT = 200f
private const val MAX_BAR_SEGMENTS = 50

fun canEditVideoOverlay(phase: VideoRecordingPhase): Boolean = phase == VideoRecordingPhase.IDLE
