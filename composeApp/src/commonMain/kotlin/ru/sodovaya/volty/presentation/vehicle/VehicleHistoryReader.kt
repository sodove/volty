package ru.sodovaya.volty.presentation.vehicle

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.sodovaya.volty.domain.history.*
import ru.sodovaya.volty.presentation.history.*

/** One recorded value with the moment it was measured; never a live reading. */
data class VehicleReading(val metricId: String, val value: Double, val unit: HistoryUnit)

/** A pack's state at the end of a recorded ride. [cells] are per-cell volts in cell order, empty when not recorded. */
data class VehicleBatterySnapshot(val label: String, val readings: List<VehicleReading>, val atEpochMillis: Long,
    val cells: List<Double> = emptyList()) {
    fun value(metricId: String): Double? = readings.firstOrNull { it.metricId == metricId }?.value
}

/** What the garage shows for one vehicle without connecting: its newest ride, how it ended. */
data class VehicleGlance(val ride: HistoryRide, val battery: VehicleBatterySnapshot?, val totals: List<HistoryTotal>) {
    fun total(kind: HistoryTotalKind): HistoryTotal? = totals.firstOrNull { it.kind == kind }
}

internal val vehicleBatteryMetrics = listOf("soc_percent", "voltage_v", "cell_min_v", "cell_max_v", "cell_delta_mv", "battery_temperature_c")

/** The newest non-null value of each metric in a series window, signed for display. */
internal fun vehicleBatterySnapshot(label: String, series: List<HistorySeries>, cells: List<Double> = emptyList()): VehicleBatterySnapshot? {
    val latest = series.mapNotNull { s ->
        val descriptor = HistoryMetricRegistry.descriptor(s.key.metricId) ?: return@mapNotNull null
        val sample = s.samples.lastOrNull { it.value != null && it.value.isFinite() } ?: return@mapNotNull null
        Triple(VehicleReading(s.key.metricId, sample.value!! * descriptor.displayMultiplier, descriptor.unit), sample.tick.epochMillis, s.key.metricId)
    }
    if (latest.isEmpty()) return null
    val ordered = latest.sortedBy { vehicleBatteryMetrics.indexOf(it.third) }
    return VehicleBatterySnapshot(label, ordered.map { it.first }, ordered.maxOf { it.second }, cells)
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
            val cells = if (!withCells) emptyList() else cellVolts(rideId, source, tail)
            vehicleBatterySnapshot(source.label, series, cells)
        }
    }

    private suspend fun cellVolts(rideId: String, source: HistorySource, tail: HistoryRange): List<Double> {
        val keys = tolerant { history.recordedKeys(rideId, source.id) }?.keys.orEmpty()
            .filter { it.metricId == "cell_v" && it.cellIndex != null }.sortedBy { it.cellIndex }.take(MAX_CELLS)
        val volts = keys.map { key -> tolerant { history.series(rideId, key, tail, LAST_STATE_POINTS) }
            ?.samples?.lastOrNull { it.value != null }?.value }
        // A cell row with a hole is not a cell picture: show none rather than a misleading one.
        return if (volts.isEmpty() || volts.any { it == null }) emptyList() else volts.map { it!! }
    }

    /** Whole-ride totals; the paging and walking run on [computeDispatcher], not the caller's (UI) dispatcher (final review I1). */
    suspend fun totals(rideId: String, recorded: Recorded): List<HistoryTotal> = withContext(computeDispatcher) {
        readHistoryTotals(history, rideId, recorded.sources, recorded.range, peakCache = peakCache)
    }

    suspend fun route(rideId: String, recorded: Recorded): List<Pair<Double, Double>> =
        tolerant { readHistoryRouteFixes(history, rideId, recorded.range, ROUTE_PAGES) }?.let(::vehicleRouteShape).orEmpty()

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
