package ru.sodovaya.volty.domain.history

data class HistoryPresetMetric(
    val sourceId: String,
    val sourceKind: HistorySourceKind,
    val metricId: String,
    val cellIndex: Int? = null,
) {
    init {
        require(sourceId.isNotBlank())
        require(metricId.isNotBlank())
        require(cellIndex == null || cellIndex >= 0)
    }
}

data class HistoryMetricPreset(
    val id: String,
    val name: String,
    val vehicleId: String,
    val metrics: List<HistoryPresetMetric>,
) {
    init {
        require(id.isNotBlank())
        require(name.isNotBlank())
        require(vehicleId.isNotBlank())
    }
}

data class HistoryPresetResolution(
    val keys: List<HistorySeriesKey>,
    val unavailable: List<HistoryPresetMetric>,
)

fun resolveHistoryMetricPreset(
    preset: HistoryMetricPreset,
    vehicleId: String,
    sources: List<HistorySource>,
): HistoryPresetResolution {
    val keys = linkedSetOf<HistorySeriesKey>()
    val unavailable = linkedSetOf<HistoryPresetMetric>()

    if (preset.vehicleId != vehicleId) {
        unavailable.addAll(preset.metrics)
        return HistoryPresetResolution(keys.toList(), unavailable.toList())
    }

    val sourcesById = sources.groupBy(HistorySource::id)
    for (metric in preset.metrics) {
        val matchingSources = sourcesById[metric.sourceId]
        val source = matchingSources?.singleOrNull()
        val descriptor = HistoryMetricRegistry.descriptor(metric.metricId)
        val shapeMatches = descriptor != null &&
            descriptor.indexedByCell == (metric.cellIndex != null)

        if (source == null || source.kind != metric.sourceKind || descriptor == null ||
            metric.sourceKind !in descriptor.sourceKinds || !shapeMatches
        ) {
            unavailable.add(metric)
        } else {
            keys.add(HistorySeriesKey(metric.sourceId, metric.metricId, metric.cellIndex))
        }
    }

    return HistoryPresetResolution(keys.toList(), unavailable.toList())
}

fun captureHistoryMetricPreset(
    id: String,
    name: String,
    vehicleId: String,
    keys: List<HistorySeriesKey>,
    sources: List<HistorySource>,
): HistoryMetricPreset {
    val sourcesById = sources.groupBy(HistorySource::id)
    val metrics = linkedSetOf<HistoryPresetMetric>()

    for (key in keys) {
        val source = sourcesById[key.sourceId]?.singleOrNull()
            ?: throw IllegalArgumentException("Series source must have one unambiguous identity")
        val descriptor = HistoryMetricRegistry.descriptor(key.metricId)
            ?: throw IllegalArgumentException("Unknown history metric: ${key.metricId}")
        require(source.kind in descriptor.sourceKinds) {
            "History metric ${key.metricId} is not available for source kind ${source.kind}"
        }
        require(descriptor.indexedByCell == (key.cellIndex != null)) {
            "History metric ${key.metricId} has an invalid cell index shape"
        }

        metrics.add(
            HistoryPresetMetric(
                sourceId = source.id,
                sourceKind = source.kind,
                metricId = key.metricId,
                cellIndex = key.cellIndex,
            ),
        )
    }

    return HistoryMetricPreset(
        id = id,
        name = name,
        vehicleId = vehicleId,
        metrics = metrics.toList(),
    )
}
