package ru.sodovaya.volty.presentation.graph

import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.time.Duration.Companion.ZERO

/** The stream that owns a metric. Repository samples are already vehicle-level. */
enum class GraphSource { BATTERY, MOTION }

/** Stable graph identifiers. Labels belong to the Compose resource layer. */
enum class GraphMetric(
    val unit: String,
    val source: GraphSource,
    /** Display multiplier, kept with the metric so summaries and points agree. */
    val displaySign: Float = 1f,
    val isRate: Boolean = false
) {
    // Existing battery metrics, kept source-compatible with the original graph.
    SOC("%", GraphSource.BATTERY),
    POWER("W", GraphSource.BATTERY, displaySign = -1f, isRate = true),
    CURRENT("A", GraphSource.BATTERY, displaySign = -1f, isRate = true),
    VOLTAGE("V", GraphSource.BATTERY),
    TEMPERATURE("°C", GraphSource.BATTERY),

    CELL_MIN_V("V", GraphSource.BATTERY),
    CELL_MAX_V("V", GraphSource.BATTERY),
    CELL_DELTA_MV("mV", GraphSource.BATTERY),

    SPEED("km/h", GraphSource.MOTION),
    DUTY("%", GraphSource.MOTION),
    MOTOR_CURRENT("A", GraphSource.MOTION),
    INPUT_VOLTAGE("V", GraphSource.MOTION),
    MOTOR_POWER("W", GraphSource.MOTION, isRate = true),
    ERPM("eRPM", GraphSource.MOTION),
    ESC_TEMPERATURE("°C", GraphSource.MOTION),
    MOTOR_TEMPERATURE("°C", GraphSource.MOTION)
}

@OptIn(ExperimentalTime::class)
data class GraphPoint(
    val timestamp: Instant,
    val value: Float
)

data class GraphSeries(
    val metric: GraphMetric,
    val points: List<GraphPoint>
)

data class GraphPair(
    val x: GraphPoint,
    val y: GraphPoint
)

@OptIn(ExperimentalTime::class)
data class ComparisonState(
    val xMetric: GraphMetric,
    val yMetric: GraphMetric,
    val pairs: List<GraphPair>,
    val selectedPair: GraphPair? = null
)

fun nearestPair(pairs: List<GraphPair>, xValue: Float): GraphPair? =
    pairs.minWithOrNull(compareBy<GraphPair> { kotlin.math.abs(it.x.value - xValue) })

/** Returns the nearest point; an equal-distance tie deliberately chooses earlier time. */
@OptIn(ExperimentalTime::class)
fun nearestPoint(points: List<GraphPoint>, target: Instant): GraphPoint? {
    return points.minWithOrNull(
        compareBy<GraphPoint> { distanceFrom(it.timestamp, target) }
            .thenBy { it.timestamp }
    )
}

/** Pair samples by time without presenting an old value as simultaneous. */
@OptIn(ExperimentalTime::class)
fun pairByNearestTimestamp(
    x: List<GraphPoint>,
    y: List<GraphPoint>,
    maxGap: kotlin.time.Duration
): List<GraphPair> {
    if (x.isEmpty() || y.isEmpty() || maxGap.isNegative()) return emptyList()
    return x.mapNotNull { xPoint ->
        val yPoint = nearestPoint(y, xPoint.timestamp) ?: return@mapNotNull null
        if (distanceFrom(yPoint.timestamp, xPoint.timestamp) <= maxGap) {
            GraphPair(xPoint, yPoint)
        } else {
            null
        }
    }
}

/** Converts a chart x fraction to a timestamp on the series' time axis. */
@OptIn(ExperimentalTime::class)
fun timestampAtFraction(points: List<GraphPoint>, fraction: Float): Instant? {
    if (points.isEmpty()) return null
    val first = points.first().timestamp
    val last = points.last().timestamp
    if (first == last) return first
    val clamped = fraction.coerceIn(0f, 1f).toDouble()
    return first + (last - first) * clamped
}

/** Converts a timestamp back to the chart x fraction for a marker. */
@OptIn(ExperimentalTime::class)
fun timestampFraction(points: List<GraphPoint>, timestamp: Instant): Float {
    if (points.size < 2) return 0f
    val first = points.first().timestamp
    val span = points.last().timestamp - first
    if (span == ZERO) return 0f
    return ((timestamp - first) / span).toFloat().coerceIn(0f, 1f)
}

@OptIn(ExperimentalTime::class)
private fun distanceFrom(a: Instant, b: Instant): kotlin.time.Duration {
    val delta = a - b
    return if (delta.isNegative()) -delta else delta
}
