package ru.sodovaya.volty.data.history

import ru.sodovaya.volty.domain.history.*
import ru.sodovaya.volty.domain.model.TelemetryObservation
import ru.sodovaya.volty.domain.model.TelemetryOrigin
import ru.sodovaya.volty.domain.model.TelemetryUnknown

/** Receives attributed decoder observations, never a mixed-age dashboard aggregate. */
internal fun historyTelemetrySamples(source: HistorySource, tick: HistoryTick,
    observation: TelemetryObservation): List<HistorySample> {
    val kind = when (observation.origin) {
        TelemetryOrigin.BATTERY -> HistorySourceKind.BATTERY
        TelemetryOrigin.CONTROLLER -> HistorySourceKind.CONTROLLER
    }
    require(source.kind == kind) { "History requires an attributed contributor, not an aggregate" }
    return observation.readings.map { reading ->
        val descriptor = checkNotNull(HistoryMetricRegistry.descriptor(reading.metric.id))
        require(kind in descriptor.sourceKinds && descriptor.indexedByCell == reading.metric.indexed)
        val reason = when (reading.unknown) {
            TelemetryUnknown.UNAVAILABLE -> HistoryUnknownReason.UNAVAILABLE
            TelemetryUnknown.INVALID_VALUE -> HistoryUnknownReason.INVALID_VALUE
            TelemetryUnknown.PARTIAL_SOURCE -> HistoryUnknownReason.PARTIAL_SOURCE
            null -> null
        }
        HistorySample(tick, HistorySeriesKey(source.id, reading.metric.id, reading.cellIndex), reading.value, reason)
    }
}
