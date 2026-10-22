package ru.sodovaya.volty.data.history

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.*
import ru.sodovaya.volty.domain.history.*
import kotlin.time.Instant

/** One record at a time. Original storage identities stay inside this owner. */
@OptIn(kotlin.time.ExperimentalTime::class)
internal class StructuredHistoryExportEncoder(private val metadata: HistoryExportMetadata) {
    private data class Source(val alias: String, val kind: HistorySourceKind, val label: String)
    private val legacy = metadata.ride.status == HistoryRideStatus.LEGACY
    private val quality = if (legacy) "LEGACY_WALL_TIME" else "RETAINED_DETAIL_AND_SUMMARIES"
    private val rangeAxis = if (legacy) "WALL_TIME" else "ORIGIN_PLUS_ELAPSED"
    private val sources: Map<String, Source>
    init {
        if (metadata.ride.status == HistoryRideStatus.RECORDING) fail(HistoryExportFailure.ACTIVE_RECORDING)
        if (metadata.sources.size > 2048 || metadata.sources.map { it.id }.distinct().size != metadata.sources.size)
            fail(HistoryExportFailure.INVALID_DATA)
        sources = metadata.sources.mapIndexed { index, source ->
            source.id to Source("source-${index + 1}", source.kind, historyExportLabel(source.label))
        }.toMap()
    }

    suspend fun write(format: HistoryExportFormat, records: Flow<HistoryExportRecord>,
        output: suspend (String) -> Unit): HistoryExportCounts {
        val json = format == HistoryExportFormat.JSON
        if (!json && format != HistoryExportFormat.CSV) fail(HistoryExportFailure.UNSUPPORTED_FORMAT)
        if (json) output(header().toString().dropLast(1) + ",\"records\":[")
        else output(csvColumns.joinToString(",") { it.first } + "\r\n")
        var counts = HistoryExportCounts(); var first = true
        records.collect { record ->
            currentCoroutineContext().ensureActive()
            val encoded = encode(record)
            if (json) { output((if (first) "" else ",") + encoded.toString()); first = false }
            else output(csvRow(encoded))
            counts = when (record) {
                is HistoryExportRecord.Numeric -> counts.copy(samples = counts.samples + 1)
                is HistoryExportRecord.Location -> counts.copy(locations = counts.locations + 1)
                is HistoryExportRecord.Event -> counts.copy(events = counts.events + 1)
                is HistoryExportRecord.Summary, is HistoryExportRecord.RetainedSummary -> counts.copy(summaries = counts.summaries + 1)
                is HistoryExportRecord.LegacyUnmapped -> counts.copy(unmapped = counts.unmapped + 1)
            }
        }
        if (json) output("],\"counts\":" + buildJsonObject {
            put("samples", counts.samples); put("locations", counts.locations)
            put("events", counts.events); put("summaries", counts.summaries)
            put("unmapped", counts.unmapped)
        } + "}\n")
        return counts
    }

    private fun header() = buildJsonObject {
        put("schemaVersion", 1); put("dataQuality", quality)
        put("storageKind", if (legacy) "LEGACY" else "V2"); put("title", historyExportLabel(metadata.title))
        put("origin", if (legacy) JsonNull else tick(metadata.ride.started))
        putJsonObject("range") {
            put("axis", rangeAxis)
            put("startEpochMillis", metadata.range.startEpochMillis); put("endEpochMillis", metadata.range.endEpochMillis)
        }
        put("summaryIntervalPolicy", "FULL_STORED_BUCKETS_INSIDE_WINDOW")
        putJsonArray("sources") { sources.values.forEach { source -> add(buildJsonObject {
            put("source", source.alias); put("kind", source.kind.name); put("label", source.label)
        }) } }
    }

    private fun encode(record: HistoryExportRecord): JsonObject = when (record) {
        is HistoryExportRecord.LegacyUnmapped -> buildJsonObject {
            if (!legacy) fail(HistoryExportFailure.INVALID_DATA)
            base("legacy_unmapped", record.tick)
            legacyFields(record.tick, record.capturedTimestamp, record.metric)
            put("dataQuality", "LEGACY_UNMAPPED")
            put("source", JsonNull); put("unit", JsonNull); put("value", JsonNull)
            put("cell", record.cellIndex?.let(::JsonPrimitive) ?: JsonNull)
            val raw = record.rawValue?.takeIf { record.known && it.isFinite() }
            put("legacyRawValue", raw?.let(::JsonPrimitive) ?: JsonNull)
            put("unknownReason", if (!record.known) "LEGACY" else if (raw == null) "INVALID_VALUE" else "LEGACY_UNMAPPED")
        }
        is HistoryExportRecord.Numeric -> sample(record.sample, capturedTimestamp = record.capturedTimestamp, legacyMetric = record.legacyMetric)
        is HistoryExportRecord.Location -> buildJsonObject {
            if (legacy) fail(HistoryExportFailure.INVALID_DATA)
            val fix = record.fix
            base("gps", fix.tick)
            put("latitude", fix.lat); put("longitude", fix.lon); put("accuracyMeters", fix.accuracyMeters)
            put("heading", fix.heading?.let(::JsonPrimitive) ?: JsonNull)
            put("provider", fix.provider?.name?.let(::JsonPrimitive) ?: JsonNull)
        }
        is HistoryExportRecord.Event -> buildJsonObject {
            val marker = record.marker
            base("event", marker.tick); put("event", marker.kind.name)
            marker.sourceId?.let { source(it) }
        }
        is HistoryExportRecord.RetainedSummary -> retainedSummary(record)
        is HistoryExportRecord.Summary -> buildJsonObject {
            if (legacy) fail(HistoryExportFailure.INVALID_DATA)
            val bucket = record.bucket
            val from = axis(bucket.first); val through = axis(bucket.last)
            if (from < metadata.range.startEpochMillis || through > metadata.range.endEpochMillis)
                fail(HistoryExportFailure.INVALID_DATA)
            // Retraction: labeling an overlapping bucket's original coverage
            // was honest about energy, but still leaked facts outside an
            // explicitly selected window. The reader must omit such buckets.
            put("type", "summary"); put("dataQuality", quality)
            put("rangeAxis", rangeAxis)
            put("epochMillis", bucket.first.epochMillis); put("elapsedMillis", bucket.first.elapsedMillis)
            put("clockGeneration", bucket.first.clockGeneration)
            metric(bucket.key)
            put("slot", bucket.slot); put("first", tick(bucket.first)); put("last", tick(bucket.last))
            put("minimum", bucket.minimum?.let { sample(it, checkRange = false) } ?: JsonNull)
            put("maximum", bucket.maximum?.let { sample(it, checkRange = false) } ?: JsonNull)
            put("energyWh", bucket.energyWh?.let(::JsonPrimitive) ?: JsonNull)
            put("sampleCount", bucket.sampleCount); put("complete", bucket.complete)
            put("coverage", "ORIGINAL_BUCKET")
            put("overlapsRangeBoundary", from < metadata.range.startEpochMillis || through > metadata.range.endEpochMillis)
        }
    }

    private fun retainedSummary(row: HistoryExportRecord.RetainedSummary) = buildJsonObject {
        if (legacy || row.sampleCount <= 0 || row.startElapsedMillis < metadata.ride.started.elapsedMillis ||
            row.endElapsedMillis < row.startElapsedMillis || (row.minimum == null) != (row.maximum == null) ||
            listOfNotNull(row.minimum, row.maximum, row.energyWh).any { !it.isFinite() } ||
            (row.minimum != null && row.maximum != null && row.minimum > row.maximum) ||
            (row.complete && row.minimum == null)) fail(HistoryExportFailure.INVALID_DATA)
        val coverageStart = row.firstElapsedMillis ?: row.startElapsedMillis
        if (coverageStart !in row.startElapsedMillis..row.endElapsedMillis) fail(HistoryExportFailure.INVALID_DATA)
        fun elapsedAxis(elapsed: Long) = axis(metadata.ride.started.copy(elapsedMillis = elapsed))
        if (elapsedAxis(coverageStart) < metadata.range.startEpochMillis ||
            elapsedAxis(row.endElapsedMillis) > metadata.range.endEpochMillis) fail(HistoryExportFailure.INVALID_DATA)
        fun partialTick(epoch: Long?, elapsed: Long?) = buildJsonObject {
            if (elapsed != null && elapsed !in coverageStart..row.endElapsedMillis) fail(HistoryExportFailure.INVALID_DATA)
            put("epochMillis", epoch?.let(::JsonPrimitive) ?: JsonNull)
            put("elapsedMillis", elapsed?.let(::JsonPrimitive) ?: JsonNull)
            put("clockGeneration", if (epoch != null && elapsed != null) JsonPrimitive(metadata.ride.started.clockGeneration) else JsonNull)
        }
        fun peak(value: Double?, epoch: Long?, elapsed: Long?): JsonElement = value?.let { buildJsonObject {
            partialTick(epoch, elapsed).forEach { (name, entry) -> put(name, entry) }
            metric(row.key); put("value", value); put("unknownReason", JsonNull)
        } } ?: JsonNull
        put("type", "summary"); put("dataQuality", "RETAINED_SUMMARY_TIME_UNAVAILABLE")
        put("rangeAxis", rangeAxis)
        partialTick(row.firstEpochMillis, row.firstElapsedMillis).forEach { (name, entry) -> put(name, entry) }
        metric(row.key)
        put("slot", (row.startElapsedMillis - metadata.ride.started.elapsedMillis) / HISTORY_SUMMARY_BUCKET_MILLIS)
        put("first", partialTick(row.firstEpochMillis, row.firstElapsedMillis))
        put("last", partialTick(row.lastEpochMillis, row.endElapsedMillis))
        put("minimum", peak(row.minimum, row.minEpochMillis, row.minElapsedMillis))
        put("maximum", peak(row.maximum, row.maxEpochMillis, row.maxElapsedMillis))
        put("energyWh", row.energyWh?.let(::JsonPrimitive) ?: JsonNull)
        put("sampleCount", row.sampleCount); put("complete", row.complete)
        put("coverage", "STORED_BUCKET_BOUND")
        put("coverageStartElapsedMillis", coverageStart); put("coverageEndElapsedMillis", row.endElapsedMillis)
        put("overlapsRangeBoundary", false)
    }

    private fun sample(value: HistorySample, checkRange: Boolean = true,
        capturedTimestamp: String? = null, legacyMetric: String? = null) = buildJsonObject {
        base("sample", value.tick, checkRange)
        legacyFields(value.tick, capturedTimestamp, legacyMetric)
        metric(value.key)
        put("value", value.value?.let(::JsonPrimitive) ?: JsonNull)
        put("unknownReason", value.unknown?.name?.let(::JsonPrimitive) ?: JsonNull)
    }
    private fun JsonObjectBuilder.legacyFields(value: HistoryTick, capturedTimestamp: String?, metric: String?) {
        if ((capturedTimestamp != null || metric != null) && !legacy) fail(HistoryExportFailure.INVALID_DATA)
        capturedTimestamp?.let {
            val parsed = try { Instant.parse(it).toEpochMilliseconds() } catch (_: IllegalArgumentException) { fail(HistoryExportFailure.INVALID_DATA) }
            if (parsed != value.epochMillis) fail(HistoryExportFailure.INVALID_DATA)
            put("capturedTimestamp", it)
        }
        metric?.let { put("legacyMetric", historyExportLabel(it)) }
    }
    private fun JsonObjectBuilder.base(type: String, value: HistoryTick, checkRange: Boolean = true) {
        val captured = if (legacy) value.epochMillis else axis(value)
        if (checkRange && captured !in metadata.range.startEpochMillis..metadata.range.endEpochMillis)
            fail(HistoryExportFailure.INVALID_DATA)
        put("type", type); put("dataQuality", quality)
        put("rangeAxis", rangeAxis)
        tick(value).forEach { (name, entry) -> put(name, entry) }
    }
    private fun tick(value: HistoryTick) = buildJsonObject {
        put("epochMillis", value.epochMillis)
        put("elapsedMillis", if (legacy) JsonNull else JsonPrimitive(value.elapsedMillis))
        put("clockGeneration", if (legacy) JsonNull else JsonPrimitive(value.clockGeneration))
    }
    private fun axis(value: HistoryTick) = historyAxisEpochMillis(metadata.ride.started, value)
        ?: fail(HistoryExportFailure.INVALID_DATA)

    private fun JsonObjectBuilder.source(id: String): Source {
        val source = sources[id] ?: fail(HistoryExportFailure.INVALID_DATA)
        put("source", source.alias); put("sourceKind", source.kind.name); put("sourceLabel", source.label)
        return source
    }
    private fun JsonObjectBuilder.metric(key: HistorySeriesKey) {
        val source = source(key.sourceId)
        val descriptor = HistoryMetricRegistry.descriptor(key.metricId) ?: fail(HistoryExportFailure.INVALID_DATA)
        if (source.kind !in descriptor.sourceKinds || descriptor.indexedByCell != (key.cellIndex != null))
            fail(HistoryExportFailure.INVALID_DATA)
        put("metric", key.metricId); put("cell", key.cellIndex?.let(::JsonPrimitive) ?: JsonNull)
        put("unit", descriptor.unit.name); put("displayMultiplier", descriptor.displayMultiplier)
        put("consumptionMultiplier", descriptor.consumptionMultiplier?.let(::JsonPrimitive) ?: JsonNull)
    }

    private fun csvRow(record: JsonObject): String = csvColumns.joinToString(",") { (_, name) ->
        val value = if (name == "summaryJson") record.takeIf { it["type"]?.jsonPrimitive?.content == "summary" }?.toString().orEmpty()
        else record[name]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content.orEmpty()
        val safe = if (name in numericColumns || name == "summaryJson") value else spreadsheetText(value)
        "\"${safe.replace("\"", "\"\"")}\""
    } + "\r\n"

    private fun spreadsheetText(value: String): String {
        val beginning = value.trimStart().firstOrNull()
        return if (beginning in setOf('=', '+', '-', '@') || value.firstOrNull() in setOf('\t', '\r', '\n')) "'$value" else value
    }
    private fun fail(reason: HistoryExportFailure): Nothing = throw HistoryExportException(reason)

    private companion object {
        val csvColumns = listOf("record_type" to "type", "epoch_millis" to "epochMillis", "elapsed_millis" to "elapsedMillis",
            "clock_generation" to "clockGeneration", "source" to "source", "source_kind" to "sourceKind", "source_label" to "sourceLabel",
            "metric" to "metric", "cell" to "cell", "unit" to "unit", "value" to "value", "unknown_reason" to "unknownReason",
            "display_multiplier" to "displayMultiplier", "consumption_multiplier" to "consumptionMultiplier",
            "latitude" to "latitude", "longitude" to "longitude", "accuracy_meters" to "accuracyMeters",
            "heading_degrees" to "heading", "provider" to "provider", "event" to "event",
            "summary_json" to "summaryJson", "data_quality" to "dataQuality", "range_axis" to "rangeAxis",
            "captured_timestamp" to "capturedTimestamp", "legacy_metric" to "legacyMetric", "legacy_raw_value" to "legacyRawValue")
        val numericColumns = setOf("epochMillis", "elapsedMillis", "clockGeneration", "cell", "value", "displayMultiplier",
            "consumptionMultiplier", "latitude", "longitude", "accuracyMeters", "heading", "legacyRawValue")
    }
}
