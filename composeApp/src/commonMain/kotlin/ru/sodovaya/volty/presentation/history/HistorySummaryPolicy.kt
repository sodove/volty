package ru.sodovaya.volty.presentation.history

import ru.sodovaya.volty.domain.history.*

enum class SummaryQuality { EXACT, PARTIAL, UNAVAILABLE }
data class HistorySummaryValue(val value: Double?, val quality: SummaryQuality)
data class HistoryMetricSummary(val minimum: HistorySummaryValue, val maximum: HistorySummaryValue,
    val energy: HistorySummaryValue, val unit: HistoryUnit?,
    val average: HistorySummaryValue = HistorySummaryValue(null, SummaryQuality.UNAVAILABLE))

/** Extrema are stored in domain sign; energy already uses consumption sign.
 * Qualify each field separately: complete extrema do not establish energy.
 * Never sum a vehicle aggregate with the contributing battery/controller.
 */
fun historyMetricSummary(key: HistorySeriesKey, stats: HistoryStats?): HistoryMetricSummary {
    val missing = HistorySummaryValue(null, SummaryQuality.UNAVAILABLE)
    val descriptor = HistoryMetricRegistry.descriptor(key.metricId)
        ?.takeIf { it.indexedByCell == (key.cellIndex != null) }
        ?: return HistoryMetricSummary(missing, missing, missing, null, missing)
    fun field(value: Double?, gapMakesPartial: Boolean = false): HistorySummaryValue {
        if (value == null || !value.isFinite()) return missing
        return HistorySummaryValue(if (value == 0.0) 0.0 else value,
            if (stats?.complete == true && !(gapMakesPartial && stats.hasGap)) SummaryQuality.EXACT else SummaryQuality.PARTIAL)
    }
    val multiplier = descriptor.displayMultiplier
    val low = if (multiplier < 0) stats?.maximum else stats?.minimum
    val high = if (multiplier < 0) stats?.minimum else stats?.maximum
    return HistoryMetricSummary(field(low?.times(multiplier)), field(high?.times(multiplier)),
        field(stats?.energyWh?.takeIf { descriptor.consumptionMultiplier != null }, gapMakesPartial = true), descriptor.unit,
        field(stats?.average?.times(multiplier)))
}
