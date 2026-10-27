package ru.sodovaya.volty.presentation.history

import kotlin.math.*
import ru.sodovaya.volty.domain.history.*

enum class HistoryTotalKind {
    DISTANCE_KM, DURATION_MILLIS, MOVING_MILLIS, AVERAGE_KMH, MAX_KMH, ENERGY_WH, WH_PER_KM,
    BATTERY_USED_PERCENT, MAX_POWER_W, MOTOR_TEMP_C, CONTROLLER_TEMP_C, BATTERY_TEMP_C,
}

/** [approximate] when a contributing stat was incomplete; [HistoryTotalKind.BATTERY_USED_PERCENT] is SIGNED (positive = discharged); a missing input omits the tile instead. */
data class HistoryTotal(val kind: HistoryTotalKind, val value: Double, val approximate: Boolean = false)

internal val motionTiers = listOf(setOf(HistorySourceKind.VEHICLE_MOTION), setOf(HistorySourceKind.CONTROLLER))
internal val batteryTiers = listOf(setOf(HistorySourceKind.VEHICLE_BATTERY), setOf(HistorySourceKind.BATTERY))
private val batteryTemperatureTiers = listOf(setOf(HistorySourceKind.VEHICLE_BATTERY),
    setOf(HistorySourceKind.BATTERY, HistorySourceKind.BATTERY_ASSEMBLY))

private val motionMetrics = listOf("speed_kmh", "trip_distance_km", "odometer_km", "motor_temperature_c",
    "esc_temperature_c", "controller_power_w")
private val batteryMetrics = listOf("soc_percent", "power_w")

/**
 * One tier of sources per metric: a vehicle aggregate when there is one, else its contributors.
 * Never both — an aggregate summed with the batteries it aggregates double-counts.
 */
internal fun tier(sources: List<HistorySource>, tiers: List<Set<HistorySourceKind>>): List<HistorySource> =
    tiers.firstNotNullOfOrNull { kinds -> sources.filter { it.kind in kinds }.takeIf { it.isNotEmpty() } }.orEmpty()

/**
 * What a ride shows when the rider has chosen nothing: speed from the motion source, power from the
 * battery (the controller's when there is no battery power). One tier each, so an aggregate and its
 * contributors never both appear.
 */
fun historyDefaultMetrics(sources: List<HistorySource>): List<HistorySeriesKey> {
    val speed = tier(sources, motionTiers).firstOrNull()?.let { HistorySeriesKey(it.id, "speed_kmh") }
    val power = tier(sources, batteryTiers).firstOrNull()?.let { HistorySeriesKey(it.id, "power_w") }
        ?: tier(sources, motionTiers).firstOrNull()?.let { HistorySeriesKey(it.id, "controller_power_w") }
    return listOfNotNull(speed, power)
}

/** Stats to read for the tiles of a ride with these sources. */
fun historyTotalsKeys(sources: List<HistorySource>): List<HistorySeriesKey> {
    val motion = tier(sources, motionTiers).flatMap { source -> motionMetrics.map { HistorySeriesKey(source.id, it) } }
    val battery = tier(sources, batteryTiers).flatMap { source -> batteryMetrics.map { HistorySeriesKey(source.id, it) } }
    val temperature = tier(sources, batteryTemperatureTiers).map { HistorySeriesKey(it.id, "battery_temperature_c") }
    return (motion + battery + temperature).distinct()
}

/**
 * The ride's headline numbers from whole-ride stats. The distance is computed elsewhere ([historyRideDistance] from
 * detail samples, GPS first; [historyStatsDistance] from stats) and handed in; average speed and consumption are
 * derived from it. A null [distance] omits all three tiles: an unearned distance is never replaced by `max - min`.
 * The average is over [moving] time only (speed above 3 km/h, the rider's definition); without a speed series
 * ([moving] null) it falls back to distance / wall duration and says so as approximate. An earlier revision always
 * divided by wall duration; that was wrong for the rider because standing at a light dragged the average down.
 */
fun historyRideTotals(
    sources: List<HistorySource>,
    stats: Map<HistorySeriesKey, HistoryStats>,
    durationMillis: Long?,
    distance: HistoryRideDistance?,
    moving: HistoryMovingTime? = null,
    batteryUsed: HistoryBatteryUsed? = null,
): List<HistoryTotal> {
    fun values(tierSources: List<HistorySource>, metric: String) =
        tierSources.mapNotNull { source -> stats[HistorySeriesKey(source.id, metric)]?.let { metric to it } }
    fun summaries(tierSources: List<HistorySource>, metric: String) = tierSources.mapNotNull { source ->
        val key = HistorySeriesKey(source.id, metric)
        stats[key]?.let { historyMetricSummary(key, it) }
    }
    fun peak(tierSources: List<HistorySource>, metric: String): HistoryTotal? {
        val found = summaries(tierSources, metric).filter { it.maximum.value != null }
        val best = found.maxByOrNull { it.maximum.value!! } ?: return null
        return HistoryTotal(HistoryTotalKind.MAX_KMH, best.maximum.value!!, found.any { it.maximum.quality != SummaryQuality.EXACT })
    }
    val motion = tier(sources, motionTiers)
    val battery = tier(sources, batteryTiers)
    val result = mutableListOf<HistoryTotal>()

    val distanceTotal = distance?.let { HistoryTotal(HistoryTotalKind.DISTANCE_KM, it.km, it.approximate) }
    distanceTotal?.let(result::add)
    val duration = durationMillis?.takeIf { it > 0 }
    duration?.let { result += HistoryTotal(HistoryTotalKind.DURATION_MILLIS, it.toDouble()) }
    moving?.let { result += HistoryTotal(HistoryTotalKind.MOVING_MILLIS, it.millis.toDouble(), it.approximate) }
    if (distanceTotal != null && moving != null) {
        if (moving.millis > 0) result += HistoryTotal(HistoryTotalKind.AVERAGE_KMH,
            distanceTotal.value / (moving.millis / 3_600_000.0), distanceTotal.approximate || moving.approximate)
    } else if (distanceTotal != null && duration != null)
        result += HistoryTotal(HistoryTotalKind.AVERAGE_KMH, distanceTotal.value / (duration / 3_600_000.0), true)
    peak(motion, "speed_kmh")?.let { result += it.copy(kind = HistoryTotalKind.MAX_KMH) }

    // Battery energy is the pack's own measurement; the controller's is the fallback.
    val packEnergy = summaries(battery, "power_w").filter { it.energy.value != null }
    val energy = if (packEnergy.isNotEmpty())
        HistoryTotal(HistoryTotalKind.ENERGY_WH, packEnergy.sumOf { it.energy.value!! }, packEnergy.any { it.energy.quality != SummaryQuality.EXACT })
    else summaries(motion, "controller_power_w").filter { it.energy.value != null }.takeIf { it.isNotEmpty() }?.let { found ->
        HistoryTotal(HistoryTotalKind.ENERGY_WH, found.maxOf { it.energy.value!! }, found.any { it.energy.quality != SummaryQuality.EXACT })
    }
    energy?.let(result::add)
    if (energy != null && distanceTotal != null && distanceTotal.value >= MIN_CONSUMPTION_DISTANCE_KM)
        result += HistoryTotal(HistoryTotalKind.WH_PER_KM, energy.value / distanceTotal.value, energy.approximate || distanceTotal.approximate)

    // Signed start - end of the charge on calm stretches ([historyBatteryUsed]), read from detail by the caller. An earlier
    // revision was max - min of the stored stats with a hard-coded minus ("-39 %" on a pack that ended at 79 %); that was
    // wrong because the extremes of a voltage-estimated charge are its load-sag dips. Without detail there is no tile.
    batteryUsed?.let { result += HistoryTotal(HistoryTotalKind.BATTERY_USED_PERCENT, it.percent, it.approximate) }

    // A summed peak of several packs is not knowable from their separate peaks: only a single source counts.
    val packPower = summaries(battery, "power_w").filter { it.maximum.value != null }
    val power = packPower.singleOrNull()?.maximum ?: summaries(motion, "controller_power_w")
        .filter { it.maximum.value != null }.maxByOrNull { it.maximum.value!! }?.maximum
    power?.value?.takeIf { it > 0 }?.let { result += HistoryTotal(HistoryTotalKind.MAX_POWER_W, it, power.quality != SummaryQuality.EXACT) }

    peak(motion, "motor_temperature_c")?.let { result += it.copy(kind = HistoryTotalKind.MOTOR_TEMP_C) }
    peak(motion, "esc_temperature_c")?.let { result += it.copy(kind = HistoryTotalKind.CONTROLLER_TEMP_C) }
    peak(tier(sources, batteryTemperatureTiers), "battery_temperature_c")?.let { result += it.copy(kind = HistoryTotalKind.BATTERY_TEMP_C) }
    return result
}

/**
 * Tiles worth a glance: a value that would print as zero ("0.0 km", "−0 %") says nothing on a
 * parking-lot recording, and an average over no distance is noise. Measured, but not news.
 */
fun historyVisibleTotals(totals: List<HistoryTotal>): List<HistoryTotal> {
    val distance = totals.firstOrNull { it.kind == HistoryTotalKind.DISTANCE_KM }?.value ?: 0.0
    return totals.filter { t -> when (t.kind) {
        HistoryTotalKind.DISTANCE_KM -> t.value >= 0.05
        HistoryTotalKind.AVERAGE_KMH -> distance >= 0.05 && t.value >= 0.05
        HistoryTotalKind.ENERGY_WH, HistoryTotalKind.MAX_POWER_W -> t.value >= 0.5
        // Signed: a charging session (+12 %) is as much news as a ride (-12 %).
        HistoryTotalKind.BATTERY_USED_PERCENT -> historyBatteryUsedDigit(t.value) >= 1
        HistoryTotalKind.MOVING_MILLIS -> t.value >= 1_000.0
        else -> true
    } }
}

/** Below this the Wh/km figure is noise from parking-lot moves. */
private const val MIN_CONSUMPTION_DISTANCE_KM = 0.5

/** Track length over consecutive fixes; a jump after a long silence is a GPS gap, not riding. */
fun historyGpsDistanceKm(fixes: List<HistoryGpsFix>): Double {
    var meters = 0.0
    fixes.zipWithNext { a, b ->
        val seconds = (b.tick.epochMillis - a.tick.epochMillis) / 1000.0
        if (seconds <= 0 || seconds > GPS_GAP_SECONDS) return@zipWithNext
        if (a.accuracyMeters > GPS_MAX_ACCURACY_M || b.accuracyMeters > GPS_MAX_ACCURACY_M) return@zipWithNext
        meters += haversineMeters(a.lat, a.lon, b.lat, b.lon)
    }
    return meters / 1000.0
}

private const val GPS_GAP_SECONDS = 30.0
private const val GPS_MAX_ACCURACY_M = 50.0

private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6_371_000.0
    val dLat = (lat2 - lat1) * PI / 180; val dLon = (lon2 - lon1) * PI / 180
    val h = sin(dLat / 2).pow(2) + cos(lat1 * PI / 180) * cos(lat2 * PI / 180) * sin(dLon / 2).pow(2)
    return 2 * r * asin(sqrt(h))
}
