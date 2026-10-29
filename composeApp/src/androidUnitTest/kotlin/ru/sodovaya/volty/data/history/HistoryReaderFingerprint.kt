package ru.sodovaya.volty.data.history

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import ru.sodovaya.volty.data.db.VoltyDatabaseProvider
import ru.sodovaya.volty.domain.history.*
import ru.sodovaya.volty.presentation.history.readCardMaxDuty
import ru.sodovaya.volty.presentation.history.readHistoryTotals
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.TreeMap
import java.util.concurrent.Executors

/**
 * Runs every history reader over every ride of a database FILE and writes a canonical text fingerprint of their
 * answers, so a storage change can be proved against the readers rather than argued (compact-history-storage plan,
 * Task 2). Run on v27 code it writes the baseline; run on the converted code it must write the same text, except for
 * lines a task named in advance.
 *
 * Line format: `ride|key|reader|value`. `key` is `-` for ride-level readers, else `source/metric/cell`. Bulk readers
 * (samples, buckets, overview, route, markers) print `n=<count> sha=<SHA-256 of their canonical lines>` so the file
 * stays small and holds no coordinate; pass `verbose = true` to also print every canonical line under
 * `<reader>.line` when a difference has to be located. Doubles are printed as raw bits. There is no wall clock and no
 * hash-map order anywhere: rides, sources and keys are sorted, the CSV runs in UTC.
 *
 * The collapsed form of `samplesFull` (Global Constraint 2 of the plan): a maximal chain of consecutive unknown rows
 * of one series, with the same reason, clock generation and epoch skew, every step within
 * [HISTORY_MAX_INTERVAL_MILLIS] and no gap marker (global or of that source) in `(prev, next]`, is printed as its
 * first and last member plus `x<count>`. The v27 baseline is written in this form too, so both sides compare like
 * with like. See [collapseUnknownChains].
 *
 * It reads a COPY of [source] (never the original) and deletes the copy afterwards.
 */
object HistoryReaderFingerprint {
    sealed interface Segment {
        data class One(val sample: HistorySample) : Segment
        data class Chain(val first: HistorySample, val last: HistorySample, val count: Int) : Segment
    }

    /** `elapsed,epoch,generation,rawBits|reason`; an unknown has empty bits, a known has no reason. */
    fun sampleLine(sample: HistorySample): String =
        "${sample.tick.elapsedMillis},${sample.tick.epochMillis},${sample.tick.clockGeneration}," +
            "${sample.value?.toRawBits() ?: ""}|${sample.unknown?.name ?: ""}"

    fun segmentLine(segment: Segment): String = when (segment) {
        is Segment.One -> sampleLine(segment.sample)
        is Segment.Chain -> "${sampleLine(segment.first)}..${sampleLine(segment.last)}x${segment.count}"
    }

    /**
     * Reduces every maximal unknown chain of ONE series (rows in `(elapsed, sequence)` order, as the store pages them)
     * to a [Segment.Chain]; a lone unknown and every known row stay [Segment.One]. [markers] are all the ride's
     * markers: only those of a gap kind that are global or scoped to the series' source split a chain.
     */
    fun collapseUnknownChains(samples: List<HistorySample>, markers: List<HistoryMarker>,
        maxIntervalMillis: Long = HISTORY_MAX_INTERVAL_MILLIS): List<Segment> =
        collapseUnknownRuns(samples, markers, maxIntervalMillis).map { row ->
            val end = row.run
            if (end == null) Segment.One(row.sample)
            else Segment.Chain(row.sample, HistorySample(end.tick, row.sample.key, null, row.sample.unknown), end.count)
        }

    /** Copies [source] to a temp file, fingerprints the copy into [out] and returns per-reader line counts. */
    fun run(source: File, out: File, verbose: Boolean = false): Map<String, Long> {
        require(source.isFile) { "No database at the given path" }
        val copy = Files.createTempFile("volty-fingerprint-", ".db")
        try {
            Files.copy(source.toPath(), copy, StandardCopyOption.REPLACE_EXISTING)
            val executor = Executors.newSingleThreadExecutor { Thread(it, "fingerprint-io").apply { isDaemon = true } }
            try {
                val dispatcher = executor.asCoroutineDispatcher()
                out.absoluteFile.parentFile?.mkdirs()
                return out.bufferedWriter(Charsets.UTF_8).use { writer ->
                    runBlocking(dispatcher) { fingerprint(copy.toFile(), dispatcher, writer, verbose) }
                }
            } finally { executor.shutdownNow() }
        } finally {
            Files.deleteIfExists(copy)
            for (suffix in listOf("-journal", "-wal", "-shm")) Files.deleteIfExists(File(copy.toString() + suffix).toPath())
        }
    }

    private class Emitter(val writer: Appendable, val verbose: Boolean) {
        val counts = TreeMap<String, Long>()
        fun line(ride: String, key: String, reader: String, value: String) {
            writer.append(ride).append('|').append(key).append('|').append(reader).append('|').append(value).append('\n')
            counts.merge(reader, 1L) { a, b -> a + b }
        }
        /** Bulk reader: a digest over canonical lines, with every line too when [verbose]. */
        inner class Bulk(val ride: String, val key: String, val reader: String) {
            private val digest = MessageDigest.getInstance("SHA-256")
            var n = 0L; private set
            fun add(text: String) {
                digest.update(text.toByteArray(Charsets.UTF_8)); digest.update('\n'.code.toByte()); n++
                if (verbose) line(ride, key, "$reader.line", text)
            }
            fun finish(extra: String = "") = line(ride, key, reader, "n=$n sha=${hex(digest.digest())}$extra")
        }
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    private fun bits(value: Double?) = value?.toRawBits()?.toString() ?: "null"

    private suspend fun fingerprint(file: File, dispatcher: CoroutineDispatcher, writer: Appendable, verbose: Boolean): Map<String, Long> {
        val driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
        try {
            val provider = VoltyDatabaseProvider(driver)
            val store = SqlDelightHistoryStore(provider, dispatcher)
            val repository = DefaultHistoryReadRepository(store, computeDispatcher = dispatcher)
            val exportSource = SqlDelightHistoryExportSource(provider, dispatcher)
            val encoder = StreamingHistoryExportEncoder(zone = { TimeZone.UTC })
            val emit = Emitter(writer, verbose)
            val version = driver.executeQuery(null, "PRAGMA user_version", { cursor ->
                QueryResult.Value(if (cursor.next().value) cursor.getLong(0) else null)
            }, 0).value
            writer.append("# volty reader fingerprint v1 userVersion=$version\n")

            val rides = ArrayList<HistoryRide>()
            var cursor: HistoryCursor? = null
            while (true) {
                val page = store.rides(cursor, 20)
                rides += page.items
                cursor = page.next ?: break
            }
            rides.sortBy { it.id }
            var seriesCount = 0L; var sampleRows = 0L; var knownRows = 0L; var unknownRows = 0L; var chainCount = 0L

            for (ride in rides) {
                val id = ride.id
                val end = ride.ended?.let { historyAxisEpochMillis(ride.started, it) }
                val range = HistoryRange(ride.started.epochMillis, end ?: Long.MAX_VALUE)
                val sources = store.sources(id).sortedBy { it.id }
                emit.line(id, "-", "ride", "status=${ride.status} start=${ride.started.epochMillis},${ride.started.elapsedMillis}," +
                    "${ride.started.clockGeneration} end=${ride.ended?.let { "${it.epochMillis},${it.elapsedMillis},${it.clockGeneration}" }} " +
                    "sources=${sources.size}")
                val markers = store.markers(id, range)
                guarded(emit, id, "-", "markers") {
                    val bulk = emit.Bulk(id, "-", "markers")
                    markers.forEach { bulk.add("${it.tick.elapsedMillis},${it.tick.epochMillis},${it.tick.clockGeneration}|${it.kind}|${it.sourceId ?: ""}") }
                    bulk.finish()
                }
                var rideKnown = 0L; var rideUnknown = 0L; var rideChains = 0L

                for (source in sources) {
                    val recorded = repository.recordedKeys(id, source.id, 20000)
                    val keys = recorded.keys.sortedWith(compareBy({ it.sourceId }, { it.metricId }, { it.cellIndex ?: -1 }))
                    emit.line(id, "${source.id}/*", "recordedKeys", "n=${keys.size} truncated=${recorded.truncated}")
                    emit.line(id, "${source.id}/*", "otherSourcesLastElapsed",
                        (if (ride.ended == null) "n/a" else guard { store.lastSampleElapsedOfOtherSources(id, source.id, ride.ended.elapsedMillis) }.toString()))
                    for (key in keys) {
                        seriesCount++
                        val keyText = "${key.sourceId}/${key.metricId}/${key.cellIndex ?: ""}"
                        guarded(emit, id, keyText, "samplesFull") {
                            val rows = ArrayList<HistorySample>()
                            var after: HistoryCursor? = null
                            while (true) {
                                val page = store.samples(id, key, range, after, HISTORY_DETAIL_PAGE_SIZE)
                                rows += page.items
                                after = page.next ?: break
                            }
                            val segments = collapseUnknownChains(rows, markers)
                            val bulk = emit.Bulk(id, keyText, "samplesFull")
                            segments.forEach { bulk.add(segmentLine(it)) }
                            bulk.finish()
                            val known = rows.count { it.value != null }.toLong()
                            val chains = segments.count { it is Segment.Chain || (it is Segment.One && it.sample.value == null) }.toLong()
                            emit.line(id, keyText, "counts", "known=$known unknown=${rows.size - known} chains=$chains")
                            sampleRows += rows.size; knownRows += known; unknownRows += rows.size - known; chainCount += chains
                            rideKnown += known; rideUnknown += rows.size - known; rideChains += chains
                        }
                        guarded(emit, id, keyText, "stats") {
                            val s = store.stats(id, key, range)
                            emit.line(id, keyText, "stats", "min=${bits(s.minimum)} max=${bits(s.maximum)} energy=${bits(s.energyWh)} " +
                                "complete=${s.complete} avg=${bits(s.average)} pendingWalk=${s.pendingWalk} hasGap=${s.hasGap}")
                        }
                        guarded(emit, id, keyText, "buckets") {
                            val bulk = emit.Bulk(id, keyText, "buckets")
                            var after: HistoryCursor? = null
                            while (true) {
                                val page = store.buckets(id, key, range, after, HISTORY_DETAIL_PAGE_SIZE)
                                page.items.forEach { bulk.add(it.toString()) }
                                after = page.next ?: break
                            }
                            bulk.finish()
                        }
                        guarded(emit, id, keyText, "walk") {
                            val s = repository.stats(id, key, range)
                            emit.line(id, keyText, "walk", "complete=${s.complete} hasGap=${s.hasGap} pendingWalk=${s.pendingWalk}")
                            emit.line(id, keyText, "energyRepair", "energy=${bits(s.energyWh)}")
                        }
                        guarded(emit, id, keyText, "hasKnownValue") {
                            emit.line(id, keyText, "hasKnownValue", repository.hasKnownValue(id, key, range).toString())
                        }
                        guarded(emit, id, keyText, "overview") {
                            val series = repository.series(id, key, range, 600)
                            val bulk = emit.Bulk(id, keyText, "overview")
                            series.samples.forEach { bulk.add(sampleLine(it)) }
                            bulk.finish(" markers=${series.markers.size} truncated=${series.truncated}")
                        }
                    }
                }
                emit.line(id, "-", "rideCounts", "known=$rideKnown unknown=$rideUnknown chains=$rideChains")

                guarded(emit, id, "-", "route") {
                    val bulk = emit.Bulk(id, "-", "route")
                    var after: HistoryCursor? = null
                    var markerRows = 0
                    while (true) {
                        val page = repository.route(id, range, after)
                        page.fixes.items.forEach {
                            bulk.add("${it.tick.elapsedMillis},${it.tick.epochMillis},${it.tick.clockGeneration}," +
                                "${it.lat.toRawBits()},${it.lon.toRawBits()},${it.accuracyMeters.toRawBits()}," +
                                "${it.heading?.toRawBits() ?: ""},${it.provider ?: ""}")
                        }
                        markerRows = page.markers.size
                        after = page.fixes.next ?: break
                    }
                    bulk.finish(" markers=$markerRows")
                }
                guarded(emit, id, "-", "totals") {
                    val totals = readHistoryTotals(repository, id, sources, range)
                    emit.line(id, "-", "totals", "n=${totals.size}")
                    totals.forEach { emit.line(id, "-", "total.${it.kind}", "value=${it.value.toRawBits()} approximate=${it.approximate} estimated=${it.estimated}") }
                }
                guarded(emit, id, "-", "cardMaxDuty") {
                    val duty = readCardMaxDuty(repository, ride)
                    emit.line(id, "-", "cardMaxDuty", duty?.let { "value=${it.value.toRawBits()} approximate=${it.approximate} estimated=${it.estimated}" } ?: "null")
                }
                for (format in HistoryExportFormat.entries) {
                    guarded(emit, id, "-", format.name.lowercase()) {
                        if (end == null) { emit.line(id, "-", format.name.lowercase(), "n/a unfinished"); return@guarded }
                        val digest = MessageDigest.getInstance("SHA-256")
                        var bytes = 0L
                        val input = exportSource.open(HistoryExportRequest(ride.favoriteSource(), range, format))
                        val counts = encoder.write(format, input.metadata, input.records) { chunk ->
                            val b = chunk.toByteArray(Charsets.UTF_8)
                            digest.update(b); bytes += b.size
                        }
                        emit.line(id, "-", format.name.lowercase(), "bytes=$bytes sha=${hex(digest.digest())} samples=${counts.samples} " +
                            "locations=${counts.locations} events=${counts.events} unmapped=${counts.unmapped} droppedLate=${counts.droppedLate}")
                    }
                }
            }
            writer.append("# summary rides=${rides.size} series=$seriesCount sampleRows=$sampleRows known=$knownRows " +
                "unknown=$unknownRows chains=$chainCount\n")
            return emit.counts.toMap()
        } finally { driver.close() }
    }

    private inline fun <T> guard(block: () -> T): Any? = try { block() } catch (e: Exception) { "ERROR ${e::class.simpleName}: ${e.message?.take(120)}" }

    /** One failing reader is a line of its own, not the end of the run. */
    private inline fun guarded(emit: Emitter, ride: String, key: String, reader: String, block: () -> Unit) {
        try { block() } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { emit.line(ride, key, reader, "ERROR ${e::class.simpleName}: ${e.message?.take(120)?.replace('\n', ' ')}") }
    }
}
