package ru.sodovaya.volty.data.history

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import ru.sodovaya.volty.domain.history.*

/**
 * Review I1 (2026-11-04): a preset metric whose SOURCE KIND this build does not know (written by a newer build, e.g. a
 * «Скорость GPS» metric of kind GPS read by 0.7.66) is DROPPED from what [read] returns and logged; an unknown metric id
 * of a known kind is kept and shown as unavailable (see [parse]). Retraction: decode used
 * `HistorySourceKind.valueOf` and threw for the whole envelope, so one such metric made every preset of every vehicle
 * unreadable and unsavable. The dropped entries are NOT lost: [update] writes them back into the preset they came
 * from, so a round trip through an older build leaves them for the newer one. A preset deleted here loses them with it.
 * A wrong envelope version, an oversized text or malformed JSON still refuses, as before: that is corruption.
 */
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
            val envelope = envelope(before[key])
            val next = change(known(envelope))
            validate(next)
            // Metrics this build could not read go back into their own preset, untouched.
            val kept = envelope?.presets.orEmpty().associate { stored -> stored.id to stored.metrics.filter { parse(it) == null } }
            val encoded = json.encodeToString(Envelope.serializer(), Envelope(presets = next.map { preset ->
                StoredPreset(preset.id, preset.name, preset.vehicleId, preset.metrics.map {
                    StoredMetric(it.sourceId, it.sourceKind.name, it.metricId, it.cellIndex)
                } + kept[preset.id].orEmpty())
            }))
            require(encoded.length <= MAX_TEXT_LENGTH)
            before.toMutablePreferences().apply { this[key] = encoded }
        }
        return decode(updated[key])
    }

    private fun envelope(raw: String?): Envelope? {
        if (raw == null) return null
        require(raw.length <= MAX_TEXT_LENGTH)
        val envelope = json.decodeFromString(Envelope.serializer(), raw)
        require(envelope.version == 1) { "Unsupported metric preset version" }
        return envelope
    }

    private fun decode(raw: String?): List<HistoryMetricPreset> = known(envelope(raw))

    private fun known(envelope: Envelope?): List<HistoryMetricPreset> {
        val result = envelope?.presets.orEmpty().map { preset ->
            HistoryMetricPreset(preset.id, preset.name, preset.vehicleId, preset.metrics.mapNotNull { stored ->
                parse(stored).also { if (it == null) historySkipped("preset metric '${stored.sourceKind}/${stored.metricId}'") }
            })
        }
        validate(result)
        return result
    }

    /**
     * The metric as this build can hold it, or null. Only an unknown KIND (or a malformed entry) is dropped: an unknown
     * metric id of a known kind (`x_y` on a BATTERY source) is kept, because the preset model can carry it and
     * `resolveHistoryMetricPreset` already shows it as unavailable, which is the pre-existing, tested contract
     * (`unknownMetricSurvivesStorageForExplicitUnavailablePresentation`). An unknown kind cannot be represented at all.
     */
    private fun parse(stored: StoredMetric): HistoryPresetMetric? {
        val kind = historySourceKindOrNull(stored.sourceKind) ?: return null
        if (stored.sourceId.isBlank() || stored.metricId.isBlank() || (stored.cellIndex != null && stored.cellIndex < 0)) return null
        return HistoryPresetMetric(stored.sourceId, kind, stored.metricId, stored.cellIndex)
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
