package ru.sodovaya.volty.data.history

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import ru.sodovaya.volty.data.db.VoltyDatabaseProvider
import ru.sodovaya.volty.domain.history.*
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** Legacy has wall time only: elapsed zero is a placeholder, never an admissible v2 origin. */
data class LegacyHistorySnapshot(
    val ride: HistoryRide,
    val sources: List<HistorySource>,
    val samples: List<HistorySample>,
    val unmappedMetricKeys: Set<String>,
    val truncated: Boolean,
    val origin: HistoryTick? = null,
    val stats: HistoryStats = HistoryStats(null, null, null, false),
)

@OptIn(ExperimentalTime::class)
class LegacyHistoryReader(provider: VoltyDatabaseProvider,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default) : HistoryReadRepository {
    private val database = provider.database
    override suspend fun rides(before: HistoryCursor?): HistoryPage<HistoryRide> = withContext(dispatcher) {
        val rows = database.rideRowQueries.pageLegacyHistory(before?.epochMillis, before?.stableId.orEmpty(), 21,
            ::historyRide).executeAsList()
        val selected = rows.take(20)
        val more = rows.size > 20
        HistoryPage(selected, if (more) selected.last().let { HistoryCursor(it.started.epochMillis, it.id) } else null, more)
    }

    override suspend fun ride(rideId: String): HistoryRide? = withContext(dispatcher) {
        database.rideRowQueries.selectById(rideId).executeAsOneOrNull()?.let {
            historyRide(it.id, it.vehicleId, it.startedAt, it.endedAt)
        }
    }

    override suspend fun sources(rideId: String): List<HistorySource> = withContext(dispatcher) {
        database.transactionWithResult {
            if (database.rideRowQueries.selectById(rideId).executeAsOneOrNull() == null) emptyList()
            else legacySources(database.ridePointRowQueries.selectHistoryMetricKeys(rideId).executeAsList())
        }
    }

    override suspend fun series(rideId: String, key: HistorySeriesKey, range: HistoryRange, maxPoints: Int): HistorySeries = withContext(dispatcher) {
        require(maxPoints in 3..600)
        database.transactionWithResult {
            val empty = HistorySeries(key, emptyList(), emptyList(), false)
            if (database.rideRowQueries.selectById(rideId).executeAsOneOrNull() == null) return@transactionWithResult empty
            val descriptor = HistoryMetricRegistry.descriptor(key.metricId) ?: return@transactionWithResult empty
            if (descriptor.indexedByCell != (key.cellIndex != null)) return@transactionWithResult empty
            val oldKey = database.ridePointRowQueries.selectHistoryMetricKeys(rideId).executeAsList().singleOrNull {
                legacyMetric(it)?.let { metric -> metric.sourceId == key.sourceId && metric.id == key.metricId } == true
            } ?: return@transactionWithResult empty
            val metric = checkNotNull(legacyMetric(oldKey))
            val rows = database.ridePointRowQueries.selectHistorySeries(rideId, oldKey, key.cellIndex?.toLong() ?: -1,
                range.startEpochMillis, range.endEpochMillis, 20001).executeAsList()
            val samples = rows.take(20000).map { row ->
                val value = historyValue(row.value_ * metric.canonicalMultiplier, row.isKnown != 0L)
                HistorySample(wallTick(row.timestamp), key, value, if (value == null) HistoryUnknownReason.LEGACY else null)
            }
            // Legacy elapsed=0 is a placeholder. Thin the wall-ordered rows;
            // never run v2 acquisition-cadence/integration rules against them.
            HistorySeries(key, projectHistoryOverviewRows(samples, maxPoints), emptyList(), rows.size > 20000)
        }
    }

    override suspend fun gps(rideId: String, range: HistoryRange, after: HistoryCursor?): HistoryPage<HistoryGpsFix> {
        currentCoroutineContext().ensureActive()
        return HistoryPage(emptyList(), null, false)
    }

    override suspend fun stats(rideId: String, key: HistorySeriesKey, range: HistoryRange): HistoryStats {
        currentCoroutineContext().ensureActive()
        return HistoryStats(null, null, null, false)
    }
    suspend fun load(rideId: String, limit: Int = 20000): LegacyHistorySnapshot? = withContext(dispatcher) {
        require(limit in 1..20000)
        database.transactionWithResult {
            val ride = database.rideRowQueries.selectById(rideId).executeAsOneOrNull()
                ?: return@transactionWithResult null
            val metricKeys = database.ridePointRowQueries.selectHistoryMetricKeys(rideId).executeAsList()
            val rows = database.ridePointRowQueries.selectBoundedHistory(rideId, limit.toLong() + 1).executeAsList()
            val samples = rows.take(limit).mapNotNull { row ->
                val metric = legacyMetric(row.metricKey) ?: return@mapNotNull null
                val key = HistorySeriesKey(metric.sourceId, metric.id, row.cellIndex.takeIf { it >= 0 }?.toInt())
                val value = historyValue(row.value_ * metric.canonicalMultiplier, row.isKnown != 0L)
                HistorySample(wallTick(row.timestamp), key, value, if (value == null) HistoryUnknownReason.LEGACY else null)
            }
            val sources = legacySources(metricKeys)
            LegacyHistorySnapshot(HistoryRide(ride.id, ride.vehicleId, wallTick(ride.startedAt),
                ride.endedAt?.let(::wallTick), HistoryRideStatus.LEGACY), sources, samples,
                metricKeys.filter { legacyMetric(it) == null }.toSet(), rows.size > limit)
        }
    }
    private fun historyRide(id: String, vehicleId: String, startedAt: String, endedAt: String?) =
        HistoryRide(id, vehicleId, wallTick(startedAt), endedAt?.let(::wallTick), HistoryRideStatus.LEGACY)

    private fun legacySources(metricKeys: List<String>) = metricKeys.mapNotNull(::legacyMetric).map { it.sourceId }.distinct().map {
        HistorySource(it, if (it == "legacy-battery") HistorySourceKind.VEHICLE_BATTERY else HistorySourceKind.VEHICLE_MOTION, it)
    }
    private fun wallTick(text: String) = HistoryTick(Instant.parse(text).toEpochMilliseconds(), 0, 0)
}

private data class LegacyMetric(val id: String, val sourceId: String, val canonicalMultiplier: Double = 1.0)

/** The retired recorder persisted GraphTelemetryMapper's display values, not wire values. */
private fun legacyMetric(key: String): LegacyMetric? = when (key) {
    "SOC" -> LegacyMetric("soc_percent", "legacy-battery")
    "POWER" -> LegacyMetric("power_w", "legacy-battery", -1.0)
    "CURRENT" -> LegacyMetric("current_a", "legacy-battery", -1.0)
    "VOLTAGE" -> LegacyMetric("voltage_v", "legacy-battery")
    "TEMPERATURE" -> LegacyMetric("battery_temperature_c", "legacy-battery")
    "CELL_MIN_V" -> LegacyMetric("cell_min_v", "legacy-battery")
    "CELL_MAX_V" -> LegacyMetric("cell_max_v", "legacy-battery")
    "CELL_DELTA_MV" -> LegacyMetric("cell_delta_mv", "legacy-battery")
    "SPEED" -> LegacyMetric("speed_kmh", "legacy-motion")
    "DUTY" -> LegacyMetric("duty_percent", "legacy-motion")
    "MOTOR_CURRENT" -> LegacyMetric("motor_current_a", "legacy-motion")
    "INPUT_VOLTAGE" -> LegacyMetric("input_voltage_v", "legacy-motion")
    "MOTOR_POWER" -> LegacyMetric("controller_power_w", "legacy-motion")
    "ERPM" -> LegacyMetric("erpm", "legacy-motion")
    "ESC_TEMPERATURE" -> LegacyMetric("esc_temperature_c", "legacy-motion")
    "MOTOR_TEMPERATURE" -> LegacyMetric("motor_temperature_c", "legacy-motion")
    else -> null
}
