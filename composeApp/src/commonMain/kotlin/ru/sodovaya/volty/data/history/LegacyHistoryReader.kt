package ru.sodovaya.volty.data.history

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
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
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default) {
    private val database = provider.database
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
            val sources = metricKeys.mapNotNull(::legacyMetric).map { it.sourceId }.distinct().map {
                HistorySource(it, if (it == "legacy-battery") HistorySourceKind.VEHICLE_BATTERY else HistorySourceKind.VEHICLE_MOTION, it)
            }
            LegacyHistorySnapshot(HistoryRide(ride.id, ride.vehicleId, wallTick(ride.startedAt),
                ride.endedAt?.let(::wallTick), HistoryRideStatus.LEGACY), sources, samples,
                metricKeys.filter { legacyMetric(it) == null }.toSet(), rows.size > limit)
        }
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
