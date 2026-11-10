package ru.sodovaya.volty.presentation.vehicle

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import ru.sodovaya.volty.domain.history.*
import ru.sodovaya.volty.presentation.history.*

/** One recorded value with the moment it was measured; never a live reading. */
data class VehicleReading(val metricId: String, val value: Double, val unit: HistoryUnit)

/** A pack's state at the end of a recorded ride. [cells] are per-cell volts in cell order, empty when not recorded. */
data class VehicleBatterySnapshot(val label: String, val readings: List<VehicleReading>, val atEpochMillis: Long,
    val cells: List<Double> = emptyList(), val sourceId: String) {
    fun value(metricId: String): Double? = readings.firstOrNull { it.metricId == metricId }?.value
}

/** What the garage shows for one vehicle without connecting: its newest ride, how it ended. */
data class VehicleGlance(val ride: HistoryRide, val battery: VehicleBatterySnapshot?, val totals: List<HistoryTotal>) {
    fun total(kind: HistoryTotalKind): HistoryTotal? = totals.firstOrNull { it.kind == kind }
}

internal val vehicleBatteryMetrics = listOf("soc_percent", "voltage_v", "cell_min_v", "cell_max_v", "cell_delta_mv", "battery_temperature_c")

/** The newest non-null value of each metric in a series window, signed for display. */
internal fun vehicleBatterySnapshot(source: HistorySource, series: List<HistorySeries>, cells: List<Double> = emptyList(),
    cellEpochMillis: Long? = null): VehicleBatterySnapshot? {
    val latest = series.mapNotNull { s ->
        val descriptor = HistoryMetricRegistry.descriptor(s.key.metricId) ?: return@mapNotNull null
        val sample = s.samples.lastOrNull { it.value != null && it.value.isFinite() } ?: return@mapNotNull null
        Triple(VehicleReading(s.key.metricId, sample.value!! * descriptor.displayMultiplier, descriptor.unit), sample.tick.epochMillis, s.key.metricId)
    }
    val recordedMetrics = latest.mapTo(mutableSetOf()) { it.third }
    val derived = if (cellEpochMillis != null && recordedMetrics.none { it in cellSummaryMetrics }) {
        recordedCellReadings(cells).map { Triple(it, cellEpochMillis, it.metricId) }
    } else emptyList()
    val ordered = (latest + derived).sortedBy { vehicleBatteryMetrics.indexOf(it.third) }
    if (ordered.isEmpty()) return null
    return VehicleBatterySnapshot(source.label, ordered.map { it.first }, ordered.maxOf { it.second }, cells, source.id)
}

private val cellSummaryMetrics = setOf("cell_min_v", "cell_max_v", "cell_delta_mv")

/** Summarizes the recorded cells used by the chart. Begode can record them in separate pages. */
private fun recordedCellReadings(cells: List<Double>): List<VehicleReading> {
    if (cells.size < 2) return emptyList()
    val min = cells.min()
    val max = cells.max()
    return listOf(
        VehicleReading("cell_min_v", min, HistoryUnit.VOLT),
        VehicleReading("cell_max_v", max, HistoryUnit.VOLT),
        VehicleReading("cell_delta_mv", (max - min) * 1_000, HistoryUnit.MILLIVOLT),
    )
}

internal fun vehicleBatteryCards(snapshots: List<VehicleBatterySnapshot>): List<VehicleBatterySnapshot> {
    val charged = snapshots.filter { it.value("soc_percent") != null }
    return charged.ifEmpty { snapshots.take(1) }
}

/** Keeps the shape of a track in at most [max] points, always including both ends. */
fun vehicleRouteShape(fixes: List<HistoryGpsFix>, max: Int = 160): List<Pair<Double, Double>> {
    if (fixes.size <= max) return fixes.map { it.lat to it.lon }
    val step = (fixes.size - 1).toDouble() / (max - 1)
    return (0 until max).map { i -> fixes[(i * step).toInt().coerceAtMost(fixes.lastIndex)] }.map { it.lat to it.lon }
}

/**
 * Reads a vehicle's recorded history for screens shown without a connection.
 * Every read is tolerant: a failed metric drops out instead of failing the whole view.
 */
class VehicleHistoryReader(
    private val history: HistoryReadRepository,
    private val readTick: () -> HistoryTick,
    private val maxCatalogPages: Int = 20,
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val peakCache = ru.sodovaya.volty.presentation.history.HistoryPeakCache()
    private data class PreviewRoute(val ride: HistoryRide, val points: List<Pair<Double, Double>>)
    private val lastPreviewRoute = MutableStateFlow<PreviewRoute?>(null)
    data class Recorded(val sources: List<HistorySource>, val range: HistoryRange)

    /** One pass over the shared catalog, newest first, collecting up to [perVehicle] rides of each vehicle. */
    suspend fun newestRides(vehicleIds: Set<String>, perVehicle: Int): Map<String, List<HistoryRide>> {
        val found = vehicleIds.associateWith { mutableListOf<HistoryRide>() }
        var before: HistoryCursor? = null
        repeat(maxCatalogPages) {
            val page = history.rides(before)
            page.items.forEach { ride -> found[ride.vehicleId]?.takeIf { it.size < perVehicle }?.add(ride) }
            if (found.values.all { it.size >= perVehicle }) return found
            before = page.next ?: return found
        }
        return found
    }

    suspend fun recorded(ride: HistoryRide) = Recorded(history.sources(ride.id), historyRecordedRange(history, ride, readTick))

    /** Packs that reported a charge come first and hide those that did not: a controller's view of the
     * same battery (a Begode reports its pack too) is the same battery, not a second, empty one. */
    suspend fun battery(rideId: String, recorded: Recorded, withCells: Boolean): List<VehicleBatterySnapshot> =
        vehicleBatteryCards(readBattery(rideId, recorded, withCells))

    private suspend fun readBattery(rideId: String, recorded: Recorded, withCells: Boolean): List<VehicleBatterySnapshot> {
        val range = recorded.range
        val tail = HistoryRange(maxOf(range.startEpochMillis, range.endEpochMillis - LAST_STATE_WINDOW_MILLIS), range.endEpochMillis)
        return tier(recorded.sources, batteryTiers).mapNotNull { source ->
            val series = vehicleBatteryMetrics.mapNotNull { metric -> tolerant { history.series(rideId, HistorySeriesKey(source.id, metric), tail, LAST_STATE_POINTS) } }
            val cells = if (withCells) cellSamples(rideId, source, tail) else emptyList()
            vehicleBatterySnapshot(source, series, cells.mapNotNull { it.value }, cells.maxOfOrNull { it.tick.epochMillis })
        }
    }

    private suspend fun cellSamples(rideId: String, source: HistorySource, tail: HistoryRange): List<HistorySample> {
        val recorded = tolerant { history.recordedKeys(rideId, source.id) } ?: return emptyList()
        val keys = recorded.keys.filter { it.metricId == "cell_v" && it.cellIndex != null }.sortedBy { it.cellIndex }
        if (recorded.truncated || keys.size > MAX_CELLS ||
            keys.zipWithNext().any { (a, b) -> b.cellIndex != a.cellIndex!! + 1 }) return emptyList()
        return keys.map { key ->
            tolerant { history.series(rideId, key, tail, LAST_STATE_POINTS) }
                ?.samples?.lastOrNull { it.value?.isFinite() == true } ?: return emptyList()
        }
    }

    /** Whole-ride totals; the paging and walking run on [computeDispatcher], not the caller's (UI) dispatcher (final review I1). */
    suspend fun totals(rideId: String, recorded: Recorded): List<HistoryTotal> = withContext(computeDispatcher) {
        readHistoryTotals(history, rideId, recorded.sources, recorded.range, peakCache = peakCache)
    }

    internal fun cachedRoute(ride: HistoryRide): List<Pair<Double, Double>>? =
        lastPreviewRoute.value?.takeIf { it.ride == ride }?.points

    internal suspend fun route(ride: HistoryRide, recorded: Recorded): List<Pair<Double, Double>> {
        val points = withContext(computeDispatcher) {
            tolerant { readHistoryRouteFixes(history, ride.id, recorded.range, ROUTE_PAGES)?.let(::vehicleRouteShape) }.orEmpty()
        }
        if (ride.status == HistoryRideStatus.COMPLETED && ride.ended != null && points.size >= 2) {
            lastPreviewRoute.value = PreviewRoute(ride, points)
        }
        return points
    }

    suspend fun glance(ride: HistoryRide): VehicleGlance {
        val recorded = recorded(ride)
        return VehicleGlance(ride, battery(ride.id, recorded, withCells = false).firstOrNull(),
            totals(ride.id, recorded))
    }

    private suspend fun <T> tolerant(block: suspend () -> T): T? =
        try { block() } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { null }

    private companion object {
        const val LAST_STATE_WINDOW_MILLIS = 10 * 60_000L
        const val LAST_STATE_POINTS = 60
        const val MAX_CELLS = 64
        const val ROUTE_PAGES = 4
    }
}

/** Begode branches share a BLE endpoint. Other packs stay distinct even when their labels match. */
internal fun vehicleBatteryGroups(snapshots: List<VehicleBatterySnapshot>): List<List<VehicleBatterySnapshot>> =
    snapshots.groupBy { snapshot ->
        begodeHistoryBranch.matchEntire(snapshot.sourceId)?.groupValues?.get(1)
            ?.let { "begode:$it" } ?: snapshot.sourceId
    }.values.toList()

private val begodeHistoryBranch = Regex(
    "native-history-v1:17:((?:[0-9A-F]{2}:){5}[0-9A-F]{2})6:BEGODE7:BATTERY1:[01]11:CONTRIBUTOR0:0:",
)
