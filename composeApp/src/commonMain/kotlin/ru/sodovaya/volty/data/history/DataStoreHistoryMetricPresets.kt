package ru.sodovaya.volty.data.history

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import ru.sodovaya.volty.domain.history.*

class DataStoreHistoryMetricPresets(private val store: DataStore<Preferences>) : HistoryMetricPresetStore {
    override suspend fun read(): List<HistoryMetricPreset> = decode(store.data.first()[key])

    override suspend fun save(preset: HistoryMetricPreset): List<HistoryMetricPreset> = update { old ->
        val index = old.indexOfFirst { it.id == preset.id }
        if (index < 0) old + preset else old.toMutableList().apply { set(index, preset) }
    }

    override suspend fun delete(id: String): List<HistoryMetricPreset> = update { old -> old.filterNot { it.id == id } }

    private suspend fun update(change: (List<HistoryMetricPreset>) -> List<HistoryMetricPreset>): List<HistoryMetricPreset> {
        // Decode inside the atomic update. Corrupt/future data must not be
        // replaced with an empty list and then silently overwritten.
        val updated = store.updateData { before ->
            val next = change(decode(before[key]))
            validate(next)
            val encoded = json.encodeToString(Envelope.serializer(), Envelope(presets = next.map { preset ->
                StoredPreset(preset.id, preset.name, preset.vehicleId, preset.metrics.map {
                    StoredMetric(it.sourceId, it.sourceKind.name, it.metricId, it.cellIndex)
                })
            }))
            require(encoded.length <= MAX_TEXT_LENGTH)
            before.toMutablePreferences().apply { this[key] = encoded }
        }
        return decode(updated[key])
    }

    private fun decode(raw: String?): List<HistoryMetricPreset> {
        if (raw == null) return emptyList()
        require(raw.length <= MAX_TEXT_LENGTH)
        val envelope = json.decodeFromString(Envelope.serializer(), raw)
        require(envelope.version == 1) { "Unsupported metric preset version" }
        val result = envelope.presets.map { preset ->
            HistoryMetricPreset(preset.id, preset.name, preset.vehicleId, preset.metrics.map {
                HistoryPresetMetric(it.sourceId, HistorySourceKind.valueOf(it.sourceKind), it.metricId, it.cellIndex)
            })
        }
        validate(result)
        return result
    }

    private fun validate(presets: List<HistoryMetricPreset>) {
        require(presets.size <= 24 && presets.map { it.id }.distinct().size == presets.size)
        require(presets.all { it.name.length <= 80 && it.metrics.size <= 128 })
    }

    @Serializable private data class Envelope(val version: Int = 1, val presets: List<StoredPreset>)
    @Serializable private data class StoredPreset(val id: String, val name: String, val vehicleId: String, val metrics: List<StoredMetric>)
    @Serializable private data class StoredMetric(val sourceId: String, val sourceKind: String, val metricId: String, val cellIndex: Int? = null)
    private companion object {
        val key = stringPreferencesKey("history_metric_presets_v1")
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        const val MAX_TEXT_LENGTH = 1_048_576
    }
}
