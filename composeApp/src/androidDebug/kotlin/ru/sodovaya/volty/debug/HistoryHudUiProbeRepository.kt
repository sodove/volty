package ru.sodovaya.volty.debug

import ru.sodovaya.volty.domain.history.*
import kotlin.math.sin

/** Read-only fixture with earned totals and a chart for visual checks of the saved-ride HUD. */
internal class HistoryHudUiProbeRepository : HistoryReadRepository {
    private val start = HistoryTick(1794929700000L, 10L, 1L)
    private val waypoints = listOf(
        56.8380 to 60.6050, 56.8434 to 60.6079, 56.8490 to 60.6108,
        56.8501 to 60.6027, 56.8453 to 60.5995, 56.8401 to 60.5938,
    )
    private val samplesPerLeg = 103
    private val fixes = (0..(waypoints.size - 1) * samplesPerLeg).map { index ->
        val leg = (index / samplesPerLeg).coerceAtMost(waypoints.lastIndex - 1)
        val fraction = (index - leg * samplesPerLeg).toDouble() / samplesPerLeg
        val (lat, lon) = waypoints[leg]
        val (nextLat, nextLon) = waypoints[leg + 1]
        val offset = index * 2_000L
        val tick = start.copy(epochMillis = start.epochMillis + offset, elapsedMillis = start.elapsedMillis + offset)
        HistoryGpsFix(tick, lat + (nextLat - lat) * fraction, lon + (nextLon - lon) * fraction, 3.0, null)
    }
    private val ride = HistoryRide("hud-probe", "hud-probe-wheel", start, fixes.last().tick, HistoryRideStatus.COMPLETED)
    private val sources = listOf(HistorySource("wheel", HistorySourceKind.VEHICLE_MOTION, "Wheel"),
        HistorySource("pack", HistorySourceKind.VEHICLE_BATTERY, "Battery"))
    private val speedKey = HistorySeriesKey("wheel", "speed_kmh")
    private val powerKey = HistorySeriesKey("pack", "power_w")
    private val telemetry = listOf(speedKey, powerKey).associateWith { key ->
        fixes.mapIndexed { index, fix ->
            val speed = 15.0 + 7.0 * sin(index * 0.071) + 4.0 * sin(index * 0.32)
            HistorySample(fix.tick, key, if (key == speedKey) speed else -speed * 9.5)
        }
    }

    override suspend fun rides(before: HistoryCursor?) = HistoryPage(listOf(ride).takeIf { before == null }.orEmpty(), null, false)
    override suspend fun ride(rideId: String) = ride.takeIf { it.id == rideId }
    override suspend fun sources(rideId: String) = sources.takeIf { rideId == ride.id }.orEmpty()
    override suspend fun recordedKeys(rideId: String, sourceId: String, limit: Int): HistoryRecordedKeys {
        val keys = telemetry.keys.filter { rideId == ride.id && it.sourceId == sourceId }
        return HistoryRecordedKeys(keys.take(limit), keys.size > limit)
    }

    override suspend fun gps(rideId: String, range: HistoryRange, after: HistoryCursor?) =
        HistoryPage(if (rideId == ride.id && after == null) fixes.filter { it.tick.epochMillis in range.startEpochMillis..range.endEpochMillis }
            else emptyList(), null, false)

    override suspend fun route(rideId: String, range: HistoryRange, after: HistoryCursor?) =
        HistoryRoutePage(start, gps(rideId, range, after), emptyList())

    override suspend fun series(rideId: String, key: HistorySeriesKey, range: HistoryRange, maxPoints: Int) =
        HistorySeries(key, historyOverview(points(rideId, key, range), emptyList(), maxPoints), emptyList(), false, start)

    override suspend fun stats(rideId: String, key: HistorySeriesKey, range: HistoryRange) =
        historyStats(points(rideId, key, range), emptyList(), HistoryMetricRegistry.descriptor(key.metricId)?.consumptionMultiplier)

    private fun points(rideId: String, key: HistorySeriesKey, range: HistoryRange): List<HistorySample> =
        if (rideId == ride.id) telemetry[key].orEmpty().filter { it.tick.epochMillis in range.startEpochMillis..range.endEpochMillis }
        else emptyList()
}
