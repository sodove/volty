package ru.sodovaya.volty.data.history

import ru.sodovaya.volty.domain.history.HistoryMetricRegistry
import ru.sodovaya.volty.domain.history.HistorySeriesKey
import ru.sodovaya.volty.domain.history.HistorySource
import ru.sodovaya.volty.domain.history.HistorySourceKind

/**
 * Tolerant reading of stored history names (review I1, 2026-11-04).
 *
 * Source kinds and metric ids are stored as text. A build that meets one it does not know (a ride or preset written
 * by a NEWER build, e.g. `GPS` read by 0.7.66) used to throw from `valueOf`, which surfaced as a storage error for the
 * ride, the vehicle overview, the export and, for presets, the whole preset store. Now an unknown kind or metric is
 * skipped and logged, the way an unknown unknown-code already reads back as invalid data (`HistoryCompactCodec`):
 * the series it owns stays invisible, everything else still opens.
 */
internal fun historySourceKindOrNull(name: String): HistorySourceKind? = HistorySourceKind.entries.firstOrNull { it.name == name }

/** Whether this build can show [key] of a source of [kind]: the metric is registered for that kind. */
internal fun historyMetricKnownFor(kind: HistorySourceKind, key: HistorySeriesKey): Boolean {
    val descriptor = HistoryMetricRegistry.descriptor(key.metricId) ?: return false
    return kind in descriptor.sourceKinds && descriptor.indexedByCell == (key.cellIndex != null)
}

/** Stored source rows to sources, skipping (and logging) kinds this build does not know. */
internal fun <T> historyKnownSources(rows: List<T>, id: (T) -> String, kind: (T) -> String, label: (T) -> String,
    where: String): List<HistorySource> {
    val known = ArrayList<HistorySource>(rows.size)
    rows.forEach { row ->
        val parsed = historySourceKindOrNull(kind(row))
        if (parsed == null) historySkipped("$where: source of unknown kind '${kind(row)}'")
        else known += HistorySource(id(row), parsed, label(row))
    }
    return known
}

/** One line per skipped name. No ride ids, coordinates or readings: only the name that was not understood. */
internal object HistorySkippedLog {
    internal var sink: (String) -> Unit = { println(it) }
}

internal fun historySkipped(message: String) = HistorySkippedLog.sink("[VOLTY-HISTORY] skipped $message")
