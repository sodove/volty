package ru.sodovaya.volty.presentation.history

import ru.sodovaya.volty.domain.history.HistorySeriesKey
import ru.sodovaya.volty.domain.history.HistorySource

data class HistoryMetricOption(val key: HistorySeriesKey, val title: String, val group: String, val available: Boolean)
data class HistoryMetricKeyOption(val key: HistorySeriesKey, val available: Boolean)
private val searchSeparator = Regex("[^\\p{L}\\p{N}]+")

fun historySearchMatches(text: String, query: String): Boolean =
    matchesTerms(text, query.split(searchSeparator).filter { it.isNotEmpty() })

private fun matchesTerms(text: String, terms: List<String>): Boolean {
    val words = text.split(searchSeparator)
    return terms.all { term -> words.any { word ->
        if (term.length == 1 || term.all(Char::isDigit)) word.equals(term, ignoreCase = true)
        else word.contains(term, ignoreCase = true)
    } }
}

/** Search display text; identity and ordering stay with the recorded keys. */
fun historyMetricOptions(options: List<HistoryMetricOption>, query: String): List<HistoryMetricOption> {
    val terms = query.split(searchSeparator).filter { it.isNotEmpty() }
    return options.filter { matchesTerms("${it.group} ${it.title}", terms) }
}

/** A selected key remains removable even outside a bounded/unexpanded key list. */
fun historyMetricPickerKeys(recorded: List<HistorySeriesKey>, selected: List<HistorySeriesKey>, sourceId: String): List<HistoryMetricKeyOption> {
    val keys = linkedMapOf<HistorySeriesKey, Boolean>()
    recorded.filter { it.sourceId == sourceId }.forEach { keys[it] = true }
    selected.filter { it.sourceId == sourceId }.forEach { if (it !in keys) keys[it] = false }
    return keys.map { HistoryMetricKeyOption(it.key, it.value) }
}

fun reorderHistoryMetric(keys: List<HistorySeriesKey>, key: HistorySeriesKey, index: Int): List<HistorySeriesKey> {
    if (key !in keys) return keys.toList()
    val reordered = (keys - key).toMutableList()
    reordered.add(index.coerceIn(0, reordered.size), key)
    return reordered.toList()
}

fun historyMetricItemKey(key: HistorySeriesKey): String =
    "${key.sourceId.length}:${key.sourceId}${key.metricId.length}:${key.metricId}:${key.cellIndex ?: -1}"

fun historySourceLabels(sources: List<HistorySource>): Map<String, String> {
    val totals = sources.groupingBy { it.kind to it.label }.eachCount()
    val ordinals = mutableMapOf<Pair<ru.sodovaya.volty.domain.history.HistorySourceKind, String>, Int>()
    return sources.associate { source ->
        val group = source.kind to source.label
        val ordinal = (ordinals[group] ?: 0) + 1
        ordinals[group] = ordinal
        source.id to if (totals.getValue(group) > 1) "${source.label} · $ordinal" else source.label
    }
}
