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
    CONTROLLER_POWER("W"),
    SOC("%"),
    VOLTAGE("V"),
    BATTERY_CURRENT("A"),
    BATTERY_POWER("W"),
    BATTERY_TEMPERATURE("°C"),
}

@Serializable
enum class OverlayWidgetKind {
    VALUE_CARD,
    ICON_VALUE,
    ARC,
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
)

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
)

@Serializable
data class OverlayPreset(
    val id: String,
    val name: String,
    val widgets: List<OverlayWidget>,
    val centerCards: Boolean = true,
)

@Serializable
data class OverlayDocument(
    val version: Int = 1,
    val selected: OverlayPreset = OverlayTemplates.hud(),
    val saved: List<OverlayPreset> = emptyList(),
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
        val latestCausalSample = snapshots
            .asSequence()
            .filter { it.vehicleId == currentVehicle && it.timeMillis <= frameTimeMillis }
            .maxByOrNull(VideoSnapshot::timeMillis)
            ?: return null

        if (isOlderThan(latestCausalSample.timeMillis, frameTimeMillis)) return null

        val expiredMetrics = latestCausalSample.expiresAtMillis
            .filter { (_, expiresAtMillis) -> expiresAtMillis < frameTimeMillis }
            .keys
            .filter { metric -> latestCausalSample.readings[metric]?.let { it != VideoReading(null) } == true }
        if (expiredMetrics.isEmpty()) return latestCausalSample

        val readings = latestCausalSample.readings.toMutableMap()
        expiredMetrics.forEach { metric -> readings[metric] = VideoReading(value = null) }
        return latestCausalSample.copy(readings = readings)
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
}

enum class VideoRecordingPhase {
    IDLE,
    STARTING,
    RECORDING,
    STOPPING,
}
