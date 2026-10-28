package ru.sodovaya.volty.presentation.history

import kotlin.math.*
import ru.sodovaya.volty.domain.history.*
import ru.sodovaya.volty.domain.plausibility.RobustPeak

enum class HistoryTotalKind {
    DISTANCE_KM, DURATION_MILLIS, MOVING_MILLIS, AVERAGE_KMH, MAX_KMH, ENERGY_WH, WH_PER_KM,
    BATTERY_USED_PERCENT, MAX_POWER_W, MOTOR_TEMP_C, CONTROLLER_TEMP_C, BATTERY_TEMP_C, MAX_DUTY_PERCENT,
}

/**
 * [approximate] when a contributing stat was incomplete; [HistoryTotalKind.BATTERY_USED_PERCENT] is SIGNED (positive = discharged); a missing input omits the tile instead.
 * [estimated] when the number is the PWM estimate of a wheel that does not report one (the series `duty_estimated_percent`),
 * not a measurement; the tile marks it with a tilde ([historyTotalPrefix]). Distinct from [approximate]: that is "the
 * measurement may be off", this is "nothing was measured".
 */
data class HistoryTotal(val kind: HistoryTotalKind, val value: Double, val approximate: Boolean = false, val estimated: Boolean = false)

/** The prefix a tile prints before the value: a tilde for an estimate, an approximation sign for an incomplete measurement. */
fun historyTotalPrefix(total: HistoryTotal): String =
    (if (total.estimated) "~" else "") + (if (total.approximate) "≈" else "") + (if (total.estimated || total.approximate) " " else "")

/**
 * The peak PWM of a ride from its two duty series: what the controller [measured] and what the estimator [estimated].
 * The larger wins; on a tie the measurement wins. The result is marked [HistoryTotal.estimated] only when the estimate won.
 * It is approximate when the winner is, or when a LOSER is approximate and its maximum is at or above the winner's (a loser's
 * stats maximum is an upper bound, so one below the winner cannot change the number; an earlier revision flagged any
 * approximate candidate, which put "~≈" on an exact estimate because an early raw-0 series was over budget).
 *
 * Values are compared by magnitude: every decoder in the repo already emits |duty| (VESC, Begode, InMotion abs at decode;
 * KingSong and Veteran are unsigned; the estimator is 0..100), so this guards the invariant for a future signed producer.
 * No upper bound is applied in this layer: Sherman / SV wheels with field weakening show 105 %. Two PRE-EXISTING clamps
 * to 100 remain outside it (the InMotion decoder and the PWM estimator) and are not changed here.
 * An old ride (before the estimate had its own series) has only the first list and reads as measured.
 */
internal fun historyPeakDuty(measured: List<HistoryTotal>, estimated: List<HistoryTotal>): HistoryTotal? {
    val all = measured.map { it to false } + estimated.map { it to true }
    val best = all.maxByOrNull { kotlin.math.abs(it.first.value) } ?: return null
    val magnitude = kotlin.math.abs(best.first.value)
    val approximate = best.first.approximate || all.any { it !== best && it.first.approximate && kotlin.math.abs(it.first.value) >= magnitude }
    return HistoryTotal(HistoryTotalKind.MAX_DUTY_PERCENT, magnitude, approximate, best.second)
}

/** The single mark of the ride-card line: a tilde for an estimate, else an approximation sign, never both ("PWM ≤ ~≈ 87" is redundant). */
fun historyCardMark(total: HistoryTotal): String = if (total.estimated) "~ " else if (total.approximate) "≈ " else ""

/**
 * Needle-free peaks read from paged detail ([historyRobustPeaks] in the totals reader), in display units, per series.
 * [overBudget] lists series whose detail did not fit the page budget: their tile keeps today's stats maximum and says
 * approximate, because a needle could not be ruled out. A series in neither collection (a reader that cannot page
 * detail) behaves as before. See `HistoryNeedles.kt` for the rule and its false-positive risk.
 */
class HistoryRobustPeaks(val peaks: Map<HistorySeriesKey, RobustPeak> = emptyMap(), val overBudget: Set<HistorySeriesKey> = emptySet()) {
    companion object { val NONE = HistoryRobustPeaks() }
}

internal val motionTiers = listOf(setOf(HistorySourceKind.VEHICLE_MOTION), setOf(HistorySourceKind.CONTROLLER))
internal val batteryTiers = listOf(setOf(HistorySourceKind.VEHICLE_BATTERY), setOf(HistorySourceKind.BATTERY))
internal val batteryTemperatureTiers = listOf(setOf(HistorySourceKind.VEHICLE_BATTERY),
    setOf(HistorySourceKind.BATTERY, HistorySourceKind.BATTERY_ASSEMBLY))

private val motionMetrics = listOf("speed_kmh", "trip_distance_km", "odometer_km", "motor_temperature_c",
    "esc_temperature_c", "controller_power_w", "duty_percent", "duty_estimated_percent")
private val batteryMetrics = listOf("soc_percent", "power_w")

/**
 * One tier of sources per metric: a vehicle aggregate when there is one, else its contributors.
 * Never both — an aggregate summed with the batteries it aggregates double-counts.
 */
internal fun tier(sources: List<HistorySource>, tiers: List<Set<HistorySourceKind>>): List<HistorySource> =
    tiers.firstNotNullOfOrNull { kinds -> sources.filter { it.kind in kinds }.takeIf { it.isNotEmpty() } }.orEmpty()

/**
 * What a ride shows when the rider has chosen nothing: speed from the motion source, power from the
 * battery when it holds a value (the controller's otherwise). One tier each, so an aggregate and its
 * contributors never both appear.
 *
 * Retraction: an earlier revision took the battery's `power_w` whenever a battery source existed. That was wrong
 * for a Begode T4, whose BMS frame is a placeholder: the pack power series is all unknown, the default chart read
 * "No data" while the Peak power tile (which asks whether the pack has a value) showed the controller's 4694 W.
 * [packPowerHasValue] is the same question the tile asks (the pack series holds a known value). When there is no
 * controller to fall back to, the pack series stays: an empty chart is then the honest answer, not another series.
 */
fun historyDefaultMetrics(sources: List<HistorySource>, packPowerHasValue: (HistorySeriesKey) -> Boolean = { true }): List<HistorySeriesKey> {
    val speed = tier(sources, motionTiers).firstOrNull()?.let { HistorySeriesKey(it.id, "speed_kmh") }
    val controller = tier(sources, motionTiers).firstOrNull()?.let { HistorySeriesKey(it.id, "controller_power_w") }
    val pack = historyDefaultPackPowerKey(sources)
    val power = if (pack != null && (controller == null || packPowerHasValue(pack))) pack else controller
    return listOfNotNull(speed, power)
}

/** The pack power series [historyDefaultMetrics] would prefer; the caller asks whether it has a value. */
fun historyDefaultPackPowerKey(sources: List<HistorySource>): HistorySeriesKey? =
    tier(sources, batteryTiers).firstOrNull()?.let { HistorySeriesKey(it.id, "power_w") }

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
    robustPeaks: HistoryRobustPeaks = HistoryRobustPeaks.NONE,
): List<HistoryTotal> {
    fun values(tierSources: List<HistorySource>, metric: String) =
        tierSources.mapNotNull { source -> stats[HistorySeriesKey(source.id, metric)]?.let { metric to it } }
    fun summaries(tierSources: List<HistorySource>, metric: String) = tierSources.mapNotNull { source ->
        val key = HistorySeriesKey(source.id, metric)
        stats[key]?.let { historyMetricSummary(key, it) }
    }
    /** One peak per source: the needle-free detail peak when there is one, else the stored stats maximum. */
    fun candidates(tierSources: List<HistorySource>, metric: String): List<HistoryTotal> = tierSources.mapNotNull { source ->
        val key = HistorySeriesKey(source.id, metric)
        robustPeaks.peaks[key]?.let { return@mapNotNull HistoryTotal(HistoryTotalKind.MAX_KMH, it.value, it.approximate) }
        val maximum = historyMetricSummary(key, stats[key]).maximum
        maximum.value?.let { HistoryTotal(HistoryTotalKind.MAX_KMH, it, maximum.quality != SummaryQuality.EXACT || key in robustPeaks.overBudget) }
    }
    fun peak(tierSources: List<HistorySource>, metric: String): HistoryTotal? {
        val found = candidates(tierSources, metric)
        val best = found.maxByOrNull { it.value } ?: return null
        return HistoryTotal(HistoryTotalKind.MAX_KMH, best.value, found.any { it.approximate })
    }
    val motion = tier(sources, motionTiers)
    val battery = tier(sources, batteryTiers)
    val result = mutableListOf<HistoryTotal>()

    val distanceTotal = distance?.let { HistoryTotal(HistoryTotalKind.DISTANCE_KM, it.km, it.approximate) }
    distanceTotal?.let(result::add)
    val duration = durationMillis?.takeIf { it > 0 }
    duration?.let { result += HistoryTotal(HistoryTotalKind.DURATION_MILLIS, it.toDouble()) }
    moving?.let { result += HistoryTotal(HistoryTotalKind.MOVING_MILLIS, it.millis.toDouble(), it.approximate) }
    val topSpeed = peak(motion, "speed_kmh")
    if (distanceTotal != null && moving != null) {
        if (moving.millis > 0) {
            val perMoving = distanceTotal.value / (moving.millis / 3_600_000.0)
            // Final guard (final review I2): an average above the ride's own top speed is impossible, whatever produced it
            // (GPS distance over a wheel gap, standstill jitter). An earlier revision showed it, 45 km/h over a 32 km/h
            // peak; that was wrong because the distance and the moving time may describe different parts of the ride.
            // Fall back to the wall-clock average, marked approximate. While recording the peak is the stats maximum.
            if (topSpeed != null && perMoving > topSpeed.value && duration != null)
                result += HistoryTotal(HistoryTotalKind.AVERAGE_KMH, distanceTotal.value / (duration / 3_600_000.0), true)
            else result += HistoryTotal(HistoryTotalKind.AVERAGE_KMH, perMoving, distanceTotal.approximate || moving.approximate)
        }
    } else if (distanceTotal != null && duration != null)
        result += HistoryTotal(HistoryTotalKind.AVERAGE_KMH, distanceTotal.value / (duration / 3_600_000.0), true)
    topSpeed?.let { result += it.copy(kind = HistoryTotalKind.MAX_KMH) }

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
    val packPower = candidates(battery, "power_w")
    val power = packPower.singleOrNull() ?: peak(motion, "controller_power_w")
    power?.takeIf { it.value > 0 }?.let { result += HistoryTotal(HistoryTotalKind.MAX_POWER_W, it.value, it.approximate) }

    // Peak PWM: the measured series and the estimate are separate series; the winner decides whether the tile is marked.
    historyPeakDuty(candidates(motion, "duty_percent"), candidates(motion, "duty_estimated_percent"))?.let { result += it }

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
        HistoryTotalKind.ENERGY_WH, HistoryTotalKind.MAX_POWER_W, HistoryTotalKind.MAX_DUTY_PERCENT -> t.value >= 0.5
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

internal fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6_371_000.0
    val dLat = (lat2 - lat1) * PI / 180; val dLon = (lon2 - lon1) * PI / 180
    val h = sin(dLat / 2).pow(2) + cos(lat1 * PI / 180) * cos(lat2 * PI / 180) * sin(dLon / 2).pow(2)
    return 2 * r * asin(sqrt(h))
}
