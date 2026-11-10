package ru.sodovaya.volty.domain.video

import kotlinx.serialization.Serializable

@Serializable
enum class VideoMetric(val unit: String) {
    SPEED("km/h"),
    DUTY("%"),
    ESC_TEMPERATURE("°C"),
    MOTOR_TEMPERATURE("°C"),
    MOTOR_CURRENT("A"),
    CONTROLLER_CURRENT("A"),
    INPUT_CURRENT("A"),
    CONTROLLER_POWER("W"),
    SOC("%"),
    VOLTAGE("V"),
    BATTERY_CURRENT("A"),
    BATTERY_POWER("W"),
    BATTERY_TEMPERATURE("°C"),

    /** Recording or ride time; never in a snapshot, see [OverlayTrip]. */
    ELAPSED(""),

    /** Distance in km. A snapshot carries the controller's connection-session counter; see [OverlayTrip]. */
    DISTANCE("km"),
}

@Serializable
enum class OverlayWidgetKind {
    /** A labelled card: the word above, the number below. */
    VALUE_CARD,

    /**
     * The older labelled card with an icon beside the number. Kept exactly as it draws, because it is the model's
     * default kind and every saved 0.7.63 preset uses it. **Retraction:** 307706b0 redrew it as a label-less chip,
     * which silently changed every rider's saved overlay; the chip is now its own kind, [ICON_CHIP].
     */
    ICON_VALUE,

    /** A chip: icon and number on one line, no word, as telemetry-studio's `icon_value` draws. */
    ICON_CHIP,
    ARC,

    /** The dashboard's quarter dial on the left of an [OverlayBox]: filled from the top, downwards. */
    ARC_LEFT,

    /** The same on the right. */
    ARC_RIGHT,
    BAR,

    /** A bare number on the frame, for a hero speed or a time. */
    TEXT,
}

@Serializable
enum class OverlayIcon {
    AUTO,
    BATTERY,
    BOLT,
    TEMPERATURE,
    GAUGE,
    WARNING,
    CURRENT,
}

@Serializable
data class OverlayStyle(
    val background: String = "#99000000",
    val foreground: String = "#FFFFFFFF",
    val fontSize: Float = 28f,
    val cornerRadius: Float = 10f,
    val padding: Float = 12f,
    val shadow: Boolean = true,
    /** The label, dial and bar colour. Null falls back to [foreground]. A fired rule overrides it. */
    val accent: String? = null,
    /** False leaves a card or dial without its word, as telemetry-studio's dials draw. Icon chips never have one. */
    val showLabel: Boolean = true,
)

@Serializable
enum class OverlayComparison {
    GT,
    GTE,
    LT,
    LTE,
    EQ,
    NE,
}

@Serializable
data class OverlayRule(
    val comparison: OverlayComparison,
    val threshold: Double,
    val foreground: String? = null,
    val background: String? = null,
)

@Serializable
data class OverlayArc(
    val x: Float = 50f,
    val y: Float = 82f,
    val radius: Float = 110f,
    val min: Float = 0f,
    val max: Float = 100f,
    val thickness: Float = 9f,
    /** Numbers around the dial. */
    val ticks: Boolean = false,
    /** Take the range from what the vehicle reports ([OverlayScaleTracker]), not from [max]. */
    val autoScale: Boolean = false,
)

@Serializable
data class OverlayBar(
    val height: Float = 30f,
    /** Separators across the bar; 0 draws none. */
    val segments: Int = 0,
    /** True puts the numbers under the bar, false above it. */
    val valueBelow: Boolean = false,
    /** A second number under the bar, on the other edge. */
    val secondary: VideoMetric? = null,
)

/** The editor's S to XL, relative to the size a preset was drawn at. */
@Serializable
enum class OverlayWidgetSize(val scale: Float) { S(0.75f), M(1f), L(1.25f), XL(1.5f) }

/** One of nine points of a frame. Keeping an anchor rather than a position is what survives a change of aspect. */
@Serializable
enum class OverlayAnchor(val horizontal: Int, val vertical: Int) {
    TOP_START(-1, -1),
    TOP_CENTER(0, -1),
    TOP_END(1, -1),
    CENTER_START(-1, 0),
    CENTER(0, 0),
    CENTER_END(1, 0),
    BOTTOM_START(-1, 1),
    BOTTOM_CENTER(0, 1),
    BOTTOM_END(1, 1),
}

/**
 * A widget's place, all lengths in `u`: one u is the frame's short edge / 1080, so a preset is physically the same
 * size in portrait, landscape, 720p and 4K. [dx] and [dy] run inwards from the anchored edge; on a centre anchor they
 * are a signed offset from the centre.
 */
@Serializable
data class OverlayBox(
    val anchor: OverlayAnchor,
    val dx: Float,
    val dy: Float,
    val width: Float,
    val height: Float,
    val valueSize: Float = 64f,
    val unitSize: Float = 30f,
    val labelSize: Float = 24f,
)

/** Null on one side hides the widget in that orientation. */
@Serializable
data class OverlayPlacement(val portrait: OverlayBox?, val landscape: OverlayBox?)

/** The frame-wide part of a layout, in u: gradient scrims at both edges and where the state chip sits. */
@Serializable
data class OverlayFrameSpec(val scrimTop: Float = 0f, val scrimBottom: Float = 0f, val chipY: Float = 62f)

@Serializable
data class OverlayPresetLayout(val portrait: OverlayFrameSpec, val landscape: OverlayFrameSpec)

/**
 * [label] empty means "follow the metric" (the localized word from [VideoOverlayStrings]); text the rider typed is
 * burned in as typed. An earlier revision stored English defaults here ("U batt"); [isAutoOverlayLabel] still
 * reads those as automatic so saved presets keep working.
 *
 * [unit] is kept only so documents saved earlier still decode (and the format needs no version bump). It is NOT
 * rendered: the burned-in unit comes from [VideoOverlayStrings.unit] and the app's unit system, because a free-text
 * unit could say `mph` over a km/h number and nothing converted anything.
 */
@Serializable
data class OverlayWidget(
    val id: String,
    val metric: VideoMetric,
    val kind: OverlayWidgetKind = OverlayWidgetKind.ICON_VALUE,
    val icon: OverlayIcon = OverlayIcon.AUTO,
    val label: String = "",
    val unit: String = metric.unit,
    val style: OverlayStyle = OverlayStyle(),
    val rules: List<OverlayRule> = emptyList(),
    val arc: OverlayArc = OverlayArc(),
    /** Null keeps the older auto-flowing row (cards) or percent position (arcs): how saved presets are drawn. */
    val placement: OverlayPlacement? = null,
    val bar: OverlayBar = OverlayBar(),
    val size: OverlayWidgetSize = OverlayWidgetSize.M,
    /**
     * The icon by Font Awesome name ([OverlayIcons]), as telemetry-studio stores it. Null follows [icon], the older
     * enum kept so saved documents decode, and AUTO there means the metric's own icon ([overlayIconChosen]).
     */
    val iconName: String? = null,
)

@Serializable
data class OverlayPreset(
    val id: String,
    val name: String,
    val widgets: List<OverlayWidget>,
    val centerCards: Boolean = true,
    val layout: OverlayPresetLayout? = null,
)

/** What the [VideoMetric.ELAPSED] and [VideoMetric.DISTANCE] widgets count. */
@Serializable
enum class OverlayTripScope { RECORDING, RIDE }

@Serializable
data class OverlayDocument(
    val version: Int = 1,
    val selected: OverlayPreset = OverlayTemplates.hud(),
    val saved: List<OverlayPreset> = emptyList(),
    val tripScope: OverlayTripScope = OverlayTripScope.RECORDING,
)

@Serializable
data class VideoReading(
    val value: Double?,
    val estimated: Boolean = false,
    val atCeiling: Boolean = false,
)

@Serializable
data class VideoSnapshot(
    val timeMillis: Long,
    val vehicleId: String,
    val readings: Map<VideoMetric, VideoReading>,
    val demo: Boolean = false,
    val expiresAtMillis: Map<VideoMetric, Long> = emptyMap(),
    /** Measurement time on the receipt clock; repeated folds retain this timestamp. */
    val sampledAtMillis: Map<VideoMetric, Long> = emptyMap(),
)

/**
 * A short, bounded history keyed to the elapsed-realtime frame clock. Samples
 * from a previous vehicle are discarded as soon as a new vehicle is offered.
 */
class VideoTimeline(
    private val maxAgeMillis: Long = 2_000L,
    private val capacity: Int = 256,
) {
    private val snapshots = mutableListOf<VideoSnapshot>()
    private var vehicleId: String? = null

    init {
        require(maxAgeMillis >= 0L) { "maxAgeMillis must be non-negative" }
        require(capacity > 0) { "capacity must be positive" }
    }

    fun offer(snapshot: VideoSnapshot) {
        if (vehicleId != snapshot.vehicleId) {
            snapshots.clear()
            vehicleId = snapshot.vehicleId
        }
        snapshots += snapshot
        while (snapshots.size > capacity) snapshots.removeAt(0)
    }

    /** Returns the latest sample at or before the frame time, while it is fresh. */
    fun sample(frameTimeMillis: Long): VideoSnapshot? {
        val currentVehicle = vehicleId ?: return null
        var latestCausalSample: VideoSnapshot? = null
        for (candidate in snapshots) {
            if (candidate.vehicleId != currentVehicle || candidate.timeMillis > frameTimeMillis) continue
            val latest = latestCausalSample
            if (latest == null || candidate.timeMillis >= latest.timeMillis) latestCausalSample = candidate
        }
        val causalSample = latestCausalSample ?: return null

        if (isOlderThan(causalSample.timeMillis, frameTimeMillis)) return null

        val expiredMetrics = causalSample.readings.keys
            .filter { metric ->
                causalSample.expiresAtMillis[metric]?.let { it < frameTimeMillis } == true ||
                    causalSample.sampledAtMillis[metric]?.let { isOlderThan(it, frameTimeMillis) || it > causalSample.timeMillis } == true
            }
            .filter { metric -> causalSample.readings[metric]?.let { it != VideoReading(null) } == true }
        if (expiredMetrics.isEmpty()) return causalSample

        val readings = causalSample.readings.toMutableMap()
        expiredMetrics.forEach { metric -> readings[metric] = VideoReading(value = null) }
        return causalSample.copy(readings = readings)
    }

    /**
     * Numeric telemetry at a delayed video-frame time. Samples are linearly interpolated between the adjacent readings
     * that bracket `frameTimeMillis - delayMillis`; if the buffer has no upper sample yet, the newest causal sample is
     * held. A first sample is never moved back to a time before it was recorded.
     *
     * The unshifted frame time remains the authority for link freshness and every endpoint expiry. The latest raw
     * sample must also still know each metric, so an unknown reading clears immediately instead of being filled from
     * values still present in the interpolation buffer. Counters belong to the trip clock and are omitted.
     */
    fun interpolatedValues(frameMillis: Long, delayMillis: Long = DEFAULT_INTERPOLATION_DELAY_MILLIS): Map<VideoMetric, Double> {
        return interpolatedValuesAt(delayedFrameTime(frameMillis, delayMillis), frameMillis, frameMillis)
    }

    /**
     * A buffered camera frame keeps its capture time. Later samples may bracket that time only if they
     * have arrived by [availableAtMillis]. Unlike a delayed live readout, a historical frame's presence
     * and expiry are judged at capture, not when the encoder finally receives it.
     */
    fun interpolatedValuesAt(
        frameMillis: Long,
        availableAtMillis: Long,
        authorityMillis: Long = frameMillis,
    ): Map<VideoMetric, Double> {
        if (availableAtMillis < frameMillis) return emptyMap()
        val raw = sample(authorityMillis) ?: return emptyMap()
        val result = linkedMapOf<VideoMetric, Double>()
        for (metric in VideoMetric.entries) {
            if (metric == VideoMetric.ELAPSED || metric == VideoMetric.DISTANCE) continue
            if (raw.readings[metric]?.value?.isFinite() != true) continue
            val (lower, upper) = brackets(metric, frameMillis, availableAtMillis)
            val beforeValue = lower?.freshValue(metric, authorityMillis) ?: continue
            var shown = beforeValue
            if (upper != null) {
                val afterValue = upper.freshValue(metric, authorityMillis)
                if (afterValue == null) {
                    // At capture this value was known. A later unknown is a barrier, not a reason to
                    // erase earlier history. Delayed live readouts retain their stricter reentry gate.
                    if (authorityMillis != frameMillis) continue
                } else {
                    val gapMillis = upper.measuredAt(metric) - lower.measuredAt(metric)
                    if (gapMillis in 1L..MAX_INTERPOLATION_GAP_MILLIS) {
                        val fraction = (frameMillis - lower.measuredAt(metric)).toDouble() / gapMillis
                        shown = interpolate(beforeValue, afterValue, fraction)
                    }
                }
            }
            result[metric] = shown
        }
        return result
    }

    /** Recent source cadence, not cadence of repeated combined battery/controller folds. */
    fun interpolationDelayMillis(): Long {
        var largestGap = 0L
        for (metric in VideoMetric.entries) {
            if (metric == VideoMetric.ELAPSED || metric == VideoMetric.DISTANCE) continue
            var previous: Long? = null
            for (snapshot in snapshots.takeLast(32)) {
                if (snapshot.readings[metric]?.value?.isFinite() != true) { previous = null; continue }
                val stamp = snapshot.measuredAt(metric)
                previous?.let { prior ->
                    if (stamp > prior) largestGap = maxOf(largestGap, (stamp - prior).coerceAtMost(VIDEO_TELEMETRY_LOOKAHEAD_MILLIS))
                }
                previous = stamp
            }
        }
        return (largestGap + 20).coerceIn(DEFAULT_INTERPOLATION_DELAY_MILLIS, VIDEO_TELEMETRY_LOOKAHEAD_MILLIS)
    }

    private fun brackets(metric: VideoMetric, targetMillis: Long, availableAtMillis: Long): Pair<VideoSnapshot?, VideoSnapshot?> {
        var before: VideoSnapshot? = null
        var after: VideoSnapshot? = null
        for (candidate in snapshots) {
            if (candidate.vehicleId != vehicleId || candidate.timeMillis > availableAtMillis) continue
            val stamp = candidate.measuredAt(metric)
            if (stamp > candidate.timeMillis) continue // An incoherent source cannot prove a future measurement.
            if (stamp <= targetMillis) {
                if (before == null || stamp >= before.measuredAt(metric)) before = candidate
            } else {
                if (after == null || stamp <= after.measuredAt(metric)) after = candidate
            }
        }
        return before to after
    }

    private fun VideoSnapshot.measuredAt(metric: VideoMetric): Long =
        if (readings[metric]?.value?.isFinite() != true) timeMillis else sampledAtMillis[metric] ?: timeMillis

    private fun VideoSnapshot.freshValue(metric: VideoMetric, frameMillis: Long): Double? {
        if (isOlderThan(timeMillis, frameMillis)) return null
        if (isOlderThan(measuredAt(metric), frameMillis)) return null
        if (expiresAtMillis[metric]?.let { it < frameMillis } == true) return null
        return readings[metric]?.value?.takeIf(Double::isFinite)
    }

    private fun interpolate(before: Double, after: Double, fraction: Double): Double =
        (before * (1.0 - fraction) + after * fraction)
            .coerceIn(minOf(before, after), maxOf(before, after))

    private fun delayedFrameTime(frameMillis: Long, delayMillis: Long): Long {
        val delay = delayMillis.coerceAtLeast(0L)
        return if (frameMillis < Long.MIN_VALUE + delay) Long.MIN_VALUE else frameMillis - delay
    }

    fun clear() {
        snapshots.clear()
        vehicleId = null
    }

    private fun isOlderThan(sampleTimeMillis: Long, frameTimeMillis: Long): Boolean {
        // Compute a lower bound rather than subtracting the timestamps, which
        // could overflow for synthetic values near Long.MIN_VALUE.
        val oldestAllowed = if (frameTimeMillis < Long.MIN_VALUE + maxAgeMillis) {
            Long.MIN_VALUE
        } else {
            frameTimeMillis - maxAgeMillis
        }
        return sampleTimeMillis < oldestAllowed
    }

    private companion object {
        const val DEFAULT_INTERPOLATION_DELAY_MILLIS = 80L
        const val MAX_INTERPOLATION_GAP_MILLIS = 2_000L
    }
}

enum class VideoRecordingPhase {
    IDLE,
    STARTING,
    RECORDING,
    STOPPING,
}
