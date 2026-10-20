package ru.sodovaya.volty.domain.history

/** Explicit named layouts; reading these never starts telemetry or a recording. */
interface HistoryMetricPresetStore {
    suspend fun read(): List<HistoryMetricPreset>
    suspend fun save(preset: HistoryMetricPreset): List<HistoryMetricPreset>
    suspend fun delete(id: String): List<HistoryMetricPreset>
}
