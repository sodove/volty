package ru.sodovaya.volty.domain.video

import ru.sodovaya.volty.util.UnitSystem
import kotlin.math.roundToInt

/** Frame geometry the overlay depends on; any change of it needs a fresh drawing. */
data class OverlayGeometry(
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val mirrored: Boolean,
    val cropLeft: Int,
    val cropTop: Int,
    val cropRight: Int,
    val cropBottom: Int,
)

/**
 * What one widget will look like: the rendered text, the colours the rules picked, and the quantized sweep of its dial
 * or bar. [scaleMax] is there because a dial that widens redraws its numbers even if the fraction happens to match.
 */
data class OverlayWidgetFacet(
    val text: String,
    val foreground: String,
    val background: String,
    val arcStep: Int?,
    val secondaryText: String? = null,
    val scaleMax: Float? = null,
)

/**
 * Everything that decides what the overlay bitmap looks like. Two frames with equal keys draw identical overlays,
 * so the second need not be drawn at all: CameraX keeps the previous overlay texture when the canvas is not
 * touched (and renders the frame as usual as long as the draw listener returns true). The key is built from the
 * RENDERED facets, not the raw readings, because telemetry changes far more often than its rounded text does (a
 * voltage drifting in the third decimal must not repaint 30 times a second).
 *
 * [clockText] is for an on-video clock if one is ever drawn; there is none today, so it is always null.
 */
data class OverlayDrawKey(
    val preset: OverlayPreset,
    val facets: List<OverlayWidgetFacet>,
    val demo: Boolean,
    val strings: VideoOverlayStrings,
    val system: UnitSystem,
    val geometry: OverlayGeometry,
    val clockText: String? = null,
    val noLink: Boolean = false,
)

/**
 * Sweep resolution: 1.5 degrees of a 270 degree dial. It was 540 steps; a smoothed dial would then repaint on nearly
 * every frame of any change, and each repaint is a full software raster on the camera's GL thread.
 */
private const val ARC_STEPS = 180

fun overlayDrawKey(
    preset: OverlayPreset,
    snapshot: VideoSnapshot?,
    strings: VideoOverlayStrings,
    system: UnitSystem,
    geometry: OverlayGeometry,
    clockText: String? = null,
    trip: OverlayTrip = OverlayTrip(),
    scales: Map<VideoMetric, Float> = emptyMap(),
    dials: Map<VideoMetric, Double> = emptyMap(),
): OverlayDrawKey {
    val facets = preset.widgets.map { widget ->
        val reading = overlayReading(widget.metric, snapshot, trip)
        val style = overlayStyle(widget, reading)
        val swept = widget.kind in SWEPT_KINDS
        val arcStep = if (swept) overlayFraction(widget, reading, system, scales, dials)?.let { (it * ARC_STEPS).roundToInt() } else null
        OverlayWidgetFacet(
            text = videoValueText(widget.metric, reading, system),
            foreground = style.foreground,
            background = style.background,
            arcStep = arcStep,
            secondaryText = widget.bar.secondary?.let { videoValueText(it, overlayReading(it, snapshot, trip), system) },
            scaleMax = if (swept && widget.arc.autoScale) overlayScaleMax(widget, scales) else null,
        )
    }
    return OverlayDrawKey(preset, facets, snapshot?.demo == true, strings, system, geometry, clockText, overlayNoLink(snapshot))
}

private val SWEPT_KINDS = setOf(
    OverlayWidgetKind.ARC, OverlayWidgetKind.ARC_LEFT, OverlayWidgetKind.ARC_RIGHT, OverlayWidgetKind.BAR,
)

/**
 * Remembers the key of the last overlay drawn into the current overlay surface. [shouldDraw] is false for an
 * identical key. A new effect (rebind) has a new, empty surface, so a new gate (or [invalidate]) is required
 * there; a failed draw also invalidates so the next frame retries instead of trusting a half-painted surface.
 */
class OverlayDrawGate {
    private var last: OverlayDrawKey? = null
    var drawn: Long = 0
        private set
    var skipped: Long = 0
        private set

    /** Whether [shouldDraw] would draw, without recording anything: lets a caller hold a change back for later. */
    fun wouldDraw(key: OverlayDrawKey): Boolean = key != last

    fun shouldDraw(key: OverlayDrawKey): Boolean {
        if (key == last) {
            skipped++
            return false
        }
        last = key
        drawn++
        return true
    }

    fun invalidate() {
        last = null
    }
}

/**
 * Keeps an exception thrown while drawing one overlay frame from becoming a dead process (and with it the whole
 * recording). A failed frame skips its overlay and is counted; [onFirstFailure] runs exactly once so the rider is
 * told once instead of 30 times a second.
 */
class OverlayDrawGuard(private val onFirstFailure: (Throwable) -> Unit) {
    var failures: Int = 0
        private set

    /** Runs [block]; false when it threw. */
    fun attempt(block: () -> Unit): Boolean = try {
        block()
        true
    } catch (error: Exception) {
        failures++
        if (failures == 1) onFirstFailure(error)
        false
    }
}
