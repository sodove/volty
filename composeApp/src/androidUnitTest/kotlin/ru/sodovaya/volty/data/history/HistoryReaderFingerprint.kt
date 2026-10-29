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
 * the differences [Tolerance] names.
 *
 * ## Line format
 * `ride|key|reader|value`. `key` is `-` for ride-level readers, else `source/metric/cell`. Lines starting with `#` are
 * comments (the format version, the schema version, the physical row summary) and [compare] ignores them: they are the
 * only place where the storage layout may show. Bulk readers print `n=<count> sha=<SHA-256 of their canonical lines>`
 * and, beyond [CHUNK_LINES] lines, `chunks=<first 8 hex of the hash of each [CHUNK_LINES]-line chunk>`, so a mismatch
 * is located to a chunk without a second 600 MB run ([compare] reports it). Coordinates are hashed and never printed;
 * `verbose = true` also prints every canonical line of the non-geographic readers under `<reader>.line` (the route is
 * never printed). Doubles are printed as raw bits. There is no wall clock and no hash-map order: rides, sources and
 * keys are sorted, the CSV runs in UTC, ERROR text carries no temp path.
 *
 * ## Layout independence (review I4)
 * The collapsed form of `samplesFull` prints a maximal unknown chain (Global Constraint 2) as its first and last
 * member, with NO member count: after the conversion the readers return a run as first sample, fillers at most 2 s
 * apart and end sample, and those collapse to the very same first/last. `counts` carries known rows and maximal chains,
 * both layout independent. The member count of each chain is the one number a v27 file holds as rows and a v29 file
 * holds as `runCount`; it is the reader `chainMembers`, computed through [ChainMembers] (by default from the rows, i.e.
 * correct on v27). Task 9 supplies the `runCount`-aware implementation; until then it compares with
 * [Tolerance.CHAIN_MEMBERS], which skips that one reader and nothing else.
 *
 * ## The one predicted difference: the chart overview
 * [Tolerance.OVERVIEW_ROW_SELECTION] (Task 3's accepted difference). Point selection treats unknown rows and their
 * neighbours as mandatory, so dropping interior unknowns frees budget. The overview line therefore also carries
 * `known=` (hash of its known points), `breaks=` (hash of its collapsed unknown stretches), `markers=`, `truncated=`
 * and `thinned=` (the output reached [OVERVIEW_THIN_THRESHOLD] points, i.e. the budget bit). The tolerance lets
 * `n`/`sha` differ on an `overview` / `w<k>.overview` line only, and only when `markers`/`truncated` are equal and,
 * unless the baseline was `thinned`, `known` and `breaks` are equal too. Anything else still fails.
 *
 * ## What is probed (review I2, I5)
 * Per series: all samples at one page size; the same samples re-paged at sizes 7 and 100 (an internal consistency
 * check, reported as an ERROR on mismatch); `stats`; `buckets`; the completeness walk and energy repair through the
 * repository; `hasKnownValue`; the overview. Per series and per ride, five fixed windows of the ride's own time
 * ([WINDOWS]: thirds and two sevenths-based ones), never derived from run boundaries, through `samples` (known rows
 * hashed, unknown chains counted), `stats`, `series` and `route`. A window that opens inside an unknown run is the
 * case the layout can break. The `~|-|exercise` line counts how often the walk, the ride-end tail rule and the energy
 * repair actually ran, so a green run cannot be one where they never fired.
 *
 * ## Errors (review I1, I6)
 * Every reader is guarded; a failure becomes an `ERROR <class>: <message>` line for that item and the run goes on. The
 * result lists the ERROR lines that are not expected. The only expected one is `gpx` `NO_ROUTE` for a ride that has no
 * GPS fix at all (the file has nothing to put in a track): on the rider's database two rides are like that.
 *
 * It reads a COPY of the source (never the original, and with its `-wal` if one exists) and deletes the copy.
 */
object HistoryReaderFingerprint {
    const val CHUNK_LINES = 500
    const val OVERVIEW_THIN_THRESHOLD = 590

    /** Named differences [compare] may be told to accept. There is no blanket ignore. */
    enum class Tolerance { OVERVIEW_ROW_SELECTION, CHAIN_MEMBERS }

    sealed interface Segment {
        data class One(val sample: HistorySample) : Segment
        data class Chain(val first: HistorySample, val last: HistorySample, val count: Int) : Segment
    }

    /** `elapsed,epoch,generation,rawBits|reason`; an unknown has empty bits, a known has no reason. */
    fun sampleLine(sample: HistorySample): String =
        "${sample.tick.elapsedMillis},${sample.tick.epochMillis},${sample.tick.clockGeneration}," +
            "${sample.value?.toRawBits() ?: ""}|${sample.unknown?.name ?: ""}"

    /** A chain prints first and last only: the member count is storage layout, see the class comment. */
    fun segmentLine(segment: Segment): String = when (segment) {
        is Segment.One -> sampleLine(segment.sample)
        is Segment.Chain -> "${sampleLine(segment.first)}..${sampleLine(segment.last)}"
    }

    /**
     * Reduces every maximal unknown chain of ONE series (rows in `(elapsed, sequence)` order, as the store pages it)
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

    /**
     * The member count of every unknown chain of a series, in order. The default counts rows, which is right while a
     * chain is stored as its member rows (v27). Over a run-based store the rows are expanded fillers, so the
     * implementation must read `runCount` instead; that is Task 9's one addition.
     */
    fun interface ChainMembers {
        suspend fun counts(rideId: String, key: HistorySeriesKey, segments: List<Segment>): List<Int>
    }
    val physicalChainMembers = ChainMembers { _, _, segments ->
        segments.mapNotNull { when (it) { is Segment.Chain -> it.count; is Segment.One -> if (it.sample.value == null) 1 else null } }
    }

    /** Exercise counters: proof that the guarded paths ran. Layout independent, so they are compared. */
    data class Exercise(var pendingWalk: Long = 0, var walkComplete: Long = 0, var walkPartial: Long = 0,
        var repairs: Long = 0, var tailRule: Long = 0, var incomplete: Long = 0) {
        override fun toString() = "pendingWalk=$pendingWalk walkComplete=$walkComplete walkPartial=$walkPartial " +
            "repairs=$repairs tailRule=$tailRule incomplete=$incomplete"
    }

    class Result(val linesPerReader: Map<String, Long>, val errorsPerReader: Map<String, Long>,
        val unexpectedErrors: List<String>, val exercise: Exercise) {
        override fun toString() = "lines=$linesPerReader errors=$errorsPerReader unexpected=${unexpectedErrors.size} exercise=[$exercise]"
    }

    /** One difference between two fingerprints; [detail] says where inside a bulk line it starts, when it can. */
    data class Difference(val location: String, val baseline: String?, val candidate: String?, val detail: String)

    /** The fixed windows of a ride: (from, to, denominator) in ride-time fractions. Never derived from run boundaries. */
    val WINDOWS = listOf(Triple(0, 1, 3), Triple(1, 2, 3), Triple(2, 3, 3), Triple(2, 4, 7), Triple(3, 6, 7))

    /**
     * Copies [source] (and its `-wal`, if any) under [workDir] (default the system temp), fingerprints the copy into
     * [out]. The copy is deleted in a `finally` and, should the JVM die first, by a shutdown hook; its path is
     * printed so a leftover can be found.
     */
    fun run(source: File, out: File, verbose: Boolean = false, workDir: File? = null,
        chainMembers: ChainMembers = physicalChainMembers): Result {
        require(source.isFile) { "No database at the given path" }
        workDir?.mkdirs()
        val copy = if (workDir != null) Files.createTempFile(workDir.toPath(), "volty-fingerprint-", ".db")
        else Files.createTempFile("volty-fingerprint-", ".db")
        fun cleanup() {
            Files.deleteIfExists(copy)
            for (suffix in listOf("-journal", "-wal", "-shm")) Files.deleteIfExists(File(copy.toString() + suffix).toPath())
        }
        val hook = Thread { runCatching { cleanup() } }
        Runtime.getRuntime().addShutdownHook(hook)
        try {
            println("[fingerprint] working copy: $copy")
            Files.copy(source.toPath(), copy, StandardCopyOption.REPLACE_EXISTING)
            val wal = File(source.path + "-wal")
            if (wal.isFile && wal.length() > 0) {
                println("[fingerprint] WARNING: ${wal.name} exists; copying it so the copy sees the same committed state")
                Files.copy(wal.toPath(), File(copy.toString() + "-wal").toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            val executor = Executors.newSingleThreadExecutor { Thread(it, "fingerprint-io").apply { isDaemon = true } }
            try {
                val dispatcher = executor.asCoroutineDispatcher()
                out.absoluteFile.parentFile?.mkdirs()
                return out.bufferedWriter(Charsets.UTF_8).use { writer ->
                    runBlocking(dispatcher) { fingerprint(copy.toFile(), dispatcher, writer, verbose, chainMembers) }
                }
            } finally { executor.shutdownNow() }
        } finally {
            cleanup()
            runCatching { Runtime.getRuntime().removeShutdownHook(hook) }
        }
    }

    private class Emitter(val writer: Appendable, val verbose: Boolean, val scrub: (String) -> String) {
        val counts = TreeMap<String, Long>()
        val errors = TreeMap<String, Long>()
        val unexpected = ArrayList<String>()
        fun line(ride: String, key: String, reader: String, value: String, expectedError: Boolean = false) {
            writer.append(ride).append('|').append(key).append('|').append(reader).append('|').append(value).append('\n')
            counts.merge(reader, 1L) { a, b -> a + b }
            if (value.startsWith("ERROR ")) {
                errors.merge(reader, 1L) { a, b -> a + b }
                if (!expectedError) unexpected += "$ride|$key|$reader|$value"
            }
        }
        fun error(ride: String, key: String, reader: String, e: Exception) =
            line(ride, key, reader, "ERROR ${e::class.simpleName}: ${scrub(e.message ?: "")}")

        /** Bulk reader: a digest over canonical lines, chunk hashes, and every line too when [verbose] and not [sensitive]. */
        inner class Bulk(val ride: String, val key: String, val reader: String, private val sensitive: Boolean = false) {
            private val digest = MessageDigest.getInstance("SHA-256")
            private var chunk = MessageDigest.getInstance("SHA-256")
            private var chunkLines = 0
            private val chunks = ArrayList<String>()
            var n = 0L; private set
            fun add(text: String) {
                val bytes = text.toByteArray(Charsets.UTF_8)
                digest.update(bytes); digest.update(10); chunk.update(bytes); chunk.update(10); n++; chunkLines++
                if (chunkLines == CHUNK_LINES) closeChunk()
                if (verbose && !sensitive) line(ride, key, "$reader.line", text)
            }
            private fun closeChunk() { chunks += hex(chunk.digest()).take(8); chunk = MessageDigest.getInstance("SHA-256"); chunkLines = 0 }
            fun value(extra: String = ""): String {
                if (chunkLines > 0) closeChunk()
                return "n=$n sha=${hex(digest.digest())}" + (if (chunks.size > 1) " chunks=${chunks.joinToString(",")}" else "") + extra
            }
            fun finish(extra: String = "") = line(ride, key, reader, value(extra))
        }
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    private fun bits(value: Double?) = value?.toRawBits()?.toString() ?: "null"
    private fun sha(lines: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        lines.forEach { digest.update(it.toByteArray(Charsets.UTF_8)); digest.update(10) }
        return hex(digest.digest())
    }
    private fun statsText(s: HistoryStats) = "min=${bits(s.minimum)} max=${bits(s.maximum)} energy=${bits(s.energyWh)} " +
        "complete=${s.complete} avg=${bits(s.average)} pendingWalk=${s.pendingWalk} hasGap=${s.hasGap}"

    private suspend fun pageAll(store: HistoryStore, id: String, key: HistorySeriesKey, range: HistoryRange,
        size: Int, maxRows: Int = Int.MAX_VALUE): List<HistorySample> {
        val rows = ArrayList<HistorySample>()
        var after: HistoryCursor? = null
        while (rows.size < maxRows) {
            val page = store.samples(id, key, range, after, size)
            rows += page.items
            after = page.next ?: break
        }
        return rows
    }

    private fun overviewText(emit: Emitter, ride: String, key: String, reader: String, series: HistorySeries): String {
        val bulk = emit.Bulk(ride, key, reader)
        series.samples.forEach { bulk.add(sampleLine(it)) }
        val known = sha(series.samples.filter { it.value != null }.map(::sampleLine))
        val breaks = sha(HistoryReaderFingerprint.collapseUnknownChains(series.samples, series.markers)
            .filter { it is Segment.Chain || (it is Segment.One && it.sample.value == null) }.map(::segmentLine))
        return bulk.value(" known=$known breaks=$breaks markers=${series.markers.size} truncated=${series.truncated} " +
            "thinned=${series.samples.size >= OVERVIEW_THIN_THRESHOLD}")
    }

    private suspend fun fingerprint(file: File, dispatcher: CoroutineDispatcher, writer: Appendable, verbose: Boolean,
        chainMembers: ChainMembers): Result {
        val driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
        try {
            val provider = VoltyDatabaseProvider(driver)
            val store = SqlDelightHistoryStore(provider, dispatcher)
            val repository = DefaultHistoryReadRepository(store, computeDispatcher = dispatcher)
            val exportSource = SqlDelightHistoryExportSource(provider, dispatcher)
            val encoder = StreamingHistoryExportEncoder(zone = { TimeZone.UTC })
            val scrubPaths = Regex("(?i)[a-z]:[\\\\/][^\\s'\"):;]*|/[^\\s'\"):;]*(\\.db|/tmp/[^\\s'\"):;]*)")
            val emit = Emitter(writer, verbose) { text ->
                text.replace(file.absolutePath, "<db>").replace(scrubPaths, "<path>").replace('\n', ' ').take(120)
            }
            val exercise = Exercise()
            val version = driver.executeQuery(null, "PRAGMA user_version", { cursor ->
                QueryResult.Value(if (cursor.next().value) cursor.getLong(0) else null)
            }, 0).value
            writer.append("# volty reader fingerprint v2\n# userVersion=$version\n")

            val rides = ArrayList<HistoryRide>()
            var cursor: HistoryCursor? = null
            while (true) {
                val page = store.rides(cursor, 20)
                rides += page.items
                cursor = page.next ?: break
            }
            rides.sortBy { it.id }
            var seriesCount = 0L; var sampleRows = 0L; var chainCount = 0L

            for (ride in rides) {
                val id = ride.id
                val end = ride.ended?.let { historyAxisEpochMillis(ride.started, it) }
                val range = HistoryRange(ride.started.epochMillis, end ?: Long.MAX_VALUE)
                val sources = try { store.sources(id).sortedBy { it.id } }
                catch (e: Exception) { emit.error(id, "-", "sources", e); continue }
                emit.line(id, "-", "ride", "status=${ride.status} start=${ride.started.epochMillis},${ride.started.elapsedMillis}," +
                    "${ride.started.clockGeneration} end=${ride.ended?.let { "${it.epochMillis},${it.elapsedMillis},${it.clockGeneration}" }} " +
                    "sources=${sources.joinToString(",") { "${it.id}:${it.kind}:${it.label}" }}")
                var markers = emptyList<HistoryMarker>()
                guarded(emit, id, "-", "markers") {
                    markers = store.markers(id, range)
                    val bulk = emit.Bulk(id, "-", "markers")
                    markers.forEach { bulk.add("${it.tick.elapsedMillis},${it.tick.epochMillis},${it.tick.clockGeneration}|${it.kind}|${it.sourceId ?: ""}") }
                    bulk.finish()
                }
                var rideKnown = 0L; var rideChains = 0L

                // Fixed windows of the ride's own time (never run boundaries); null for a ride with no end.
                val windows = if (end == null) emptyList() else {
                    val span = end - ride.started.epochMillis
                    WINDOWS.map { (a, b, d) -> HistoryRange(ride.started.epochMillis + span * a / d, ride.started.epochMillis + span * b / d) }
                }
                if (end == null) emit.line(id, "-", "windows", "n/a unfinished")

                for (source in sources) {
                    val recorded = try { repository.recordedKeys(id, source.id, 20000) }
                    catch (e: Exception) { emit.error(id, "${source.id}/*", "recordedKeys", e); continue }
                    val keys = recorded.keys.sortedWith(compareBy({ it.sourceId }, { it.metricId }, { it.cellIndex ?: -1 }))
                    emit.line(id, "${source.id}/*", "recordedKeys", "n=${keys.size} truncated=${recorded.truncated}")
                    guarded(emit, id, "${source.id}/*", "otherSourcesLastElapsed") {
                        emit.line(id, "${source.id}/*", "otherSourcesLastElapsed", if (ride.ended == null) "n/a"
                        else store.lastSampleElapsedOfOtherSources(id, source.id, checkNotNull(ride.ended).elapsedMillis).toString())
                    }
                    for (key in keys) {
                        seriesCount++
                        val keyText = "${key.sourceId}/${key.metricId}/${key.cellIndex ?: ""}"
                        var lastRowElapsed: Long? = null
                        guarded(emit, id, keyText, "samplesFull") {
                            val rows = pageAll(store, id, key, range, HISTORY_DETAIL_PAGE_SIZE)
                            lastRowElapsed = rows.lastOrNull()?.tick?.elapsedMillis
                            val segments = collapseUnknownChains(rows, markers)
                            val bulk = emit.Bulk(id, keyText, "samplesFull")
                            segments.forEach { bulk.add(segmentLine(it)) }
                            bulk.finish()
                            val known = rows.count { it.value != null }.toLong()
                            val members = chainMembers.counts(id, key, segments)
                            emit.line(id, keyText, "counts", "known=$known chains=${members.size}")
                            emit.line(id, keyText, "chainMembers", "n=${members.size} sha=${sha(members.map { it.toString() })}")
                            sampleRows += rows.size; chainCount += members.size; rideKnown += known; rideChains += members.size

                            // Re-page at small sizes: the answer must not depend on where a page ends.
                            val small = pageAll(store, id, key, range, 7, 1400)
                            val medium = pageAll(store, id, key, range, 100, 30_000)
                            val ok7 = small == rows.take(small.size) && (small.size >= 1400 || small.size == rows.size)
                            val ok100 = medium == rows.take(medium.size) && (medium.size >= 30_000 || medium.size == rows.size)
                            emit.line(id, keyText, "paging", if (ok7 && ok100) "ok" else "ERROR PAGING_MISMATCH size7=$ok7 size100=$ok100")
                        }
                        var storeStats: HistoryStats? = null
                        guarded(emit, id, keyText, "stats") {
                            val s = store.stats(id, key, range)
                            storeStats = s
                            emit.line(id, keyText, "stats", statsText(s))
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
                            val stored = storeStats
                            if (stored != null) {
                                if (stored.pendingWalk) {
                                    exercise.pendingWalk++
                                    if (s.complete) exercise.walkComplete++ else exercise.walkPartial++
                                    val rideEnd = ride.ended?.elapsedMillis
                                    val last = lastRowElapsed
                                    if (rideEnd != null && last != null && rideEnd - last > HISTORY_MAX_INTERVAL_MILLIS) exercise.tailRule++
                                }
                                if (stored.energyWh == null && s.energyWh != null) exercise.repairs++
                            }
                            if (!s.complete) exercise.incomplete++
                        }
                        guarded(emit, id, keyText, "hasKnownValue") {
                            emit.line(id, keyText, "hasKnownValue", repository.hasKnownValue(id, key, range).toString())
                        }
                        guarded(emit, id, keyText, "overview") {
                            emit.line(id, keyText, "overview", overviewText(emit, id, keyText, "overview", repository.series(id, key, range, 600)))
                        }
                        windows.forEachIndexed { index, window ->
                            guarded(emit, id, keyText, "w$index.samples") {
                                val rows = pageAll(store, id, key, window, HISTORY_DETAIL_PAGE_SIZE)
                                val segments = collapseUnknownChains(rows, markers)
                                val known = emit.Bulk(id, keyText, "w$index.samples")
                                rows.filter { it.value != null }.forEach { known.add(sampleLine(it)) }
                                val chains = segments.filter { it is Segment.Chain || (it is Segment.One && it.sample.value == null) }
                                emit.line(id, keyText, "w$index.samples", known.value(" chains=${chains.size}"))
                                emit.line(id, keyText, "w$index.unknownEdges", sha(chains.map(::segmentLine)))
                            }
                            guarded(emit, id, keyText, "w$index.stats") {
                                emit.line(id, keyText, "w$index.stats", statsText(store.stats(id, key, window)))
                            }
                            guarded(emit, id, keyText, "w$index.overview") {
                                emit.line(id, keyText, "w$index.overview", overviewText(emit, id, keyText, "w$index.overview",
                                    repository.series(id, key, window, 600)))
                            }
                        }
                    }
                }
                emit.line(id, "-", "rideCounts", "known=$rideKnown chains=$rideChains")

                var fixCount = -1L
                guarded(emit, id, "-", "route") {
                    val bulk = emit.Bulk(id, "-", "route", sensitive = true)
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
                    fixCount = bulk.n
                    bulk.finish(" markers=$markerRows")
                }
                windows.forEachIndexed { index, window ->
                    guarded(emit, id, "-", "w$index.route") {
                        val bulk = emit.Bulk(id, "-", "w$index.route", sensitive = true)
                        var after: HistoryCursor? = null
                        var markerRows = 0
                        while (true) {
                            val page = repository.route(id, window, after)
                            page.fixes.items.forEach {
                                bulk.add("${it.tick.elapsedMillis},${it.tick.epochMillis},${it.tick.clockGeneration}," +
                                    "${it.lat.toRawBits()},${it.lon.toRawBits()}")
                            }
                            markerRows = page.markers.size
                            after = page.fixes.next ?: break
                        }
                        bulk.finish(" markers=$markerRows")
                    }
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
                    val reader = format.name.lowercase()
                    try {
                        if (end == null) { emit.line(id, "-", reader, "n/a unfinished"); continue }
                        val digest = MessageDigest.getInstance("SHA-256")
                        var bytes = 0L
                        val input = exportSource.open(HistoryExportRequest(ride.favoriteSource(), range, format))
                        val counts = encoder.write(format, input.metadata, input.records) { chunk ->
                            val b = chunk.toByteArray(Charsets.UTF_8)
                            digest.update(b); bytes += b.size
                        }
                        emit.line(id, "-", reader, "bytes=$bytes sha=${hex(digest.digest())} samples=${counts.samples} " +
                            "locations=${counts.locations} events=${counts.events} unmapped=${counts.unmapped} droppedLate=${counts.droppedLate}")
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: Exception) {
                        // The one expected error: a ride with no GPS fix has no track to write.
                        val noTrack = format == HistoryExportFormat.GPX && fixCount == 0L &&
                            (e as? HistoryExportException)?.reason == HistoryExportFailure.NO_ROUTE
                        emit.line(id, "-", reader, "ERROR ${e::class.simpleName}: ${emit.scrub(e.message ?: "")}", expectedError = noTrack)
                    }
                }
            }
            emit.line("~", "-", "exercise", exercise.toString())
            writer.append("# summary rides=${rides.size} series=$seriesCount physicalSampleRows=$sampleRows chains=$chainCount " +
                "errors=${emit.errors.values.sum()} unexpectedErrors=${emit.unexpected.size}\n")
            return Result(emit.counts.toMap(), emit.errors.toMap(), emit.unexpected.toList(), exercise)
        } finally { driver.close() }
    }

    /** One failing reader is a line of its own, not the end of the run. */
    private inline fun guarded(emit: Emitter, ride: String, key: String, reader: String, block: () -> Unit) {
        try { block() } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { emit.error(ride, key, reader, e) }
    }

    // --- comparing two fingerprints -------------------------------------------------------------------------------

    private fun parse(file: File): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        file.forEachLine { line ->
            if (line.isEmpty() || line.startsWith("#")) return@forEachLine
            val parts = line.split("|", limit = 4)
            require(parts.size == 4) { "Malformed fingerprint line" }
            result["${parts[0]}|${parts[1]}|${parts[2]}"] = parts[3]
        }
        return result
    }
    private fun fields(value: String) = value.split(' ').mapNotNull { part ->
        part.indexOf('=').takeIf { it > 0 }?.let { part.substring(0, it) to part.substring(it + 1) }
    }.toMap()

    /**
     * Differences between a baseline and a candidate fingerprint, `#` lines ignored. [tolerate] names the only
     * differences accepted; see the class comment. The location is `ride|key|reader`, so a mismatch is found without
     * coordinates, and for a bulk line [Difference.detail] names the first differing chunk.
     */
    fun compare(baseline: File, candidate: File, tolerate: Set<Tolerance> = emptySet()): List<Difference> {
        val a = parse(baseline); val b = parse(candidate)
        val differences = ArrayList<Difference>()
        for (location in (a.keys + b.keys).toSortedSet()) {
            val x = a[location]; val y = b[location]
            if (x == y) continue
            val reader = location.substringAfterLast('|')
            if (Tolerance.CHAIN_MEMBERS in tolerate && reader == "chainMembers") continue
            if (Tolerance.OVERVIEW_ROW_SELECTION in tolerate && (reader == "overview" || Regex("w\\d+\\.overview").matches(reader)) &&
                x != null && y != null && overviewDifferenceTolerated(fields(x), fields(y))) continue
            differences += Difference(location, x, y, describe(x, y))
        }
        return differences
    }

    private fun overviewDifferenceTolerated(x: Map<String, String>, y: Map<String, String>): Boolean {
        if (x["markers"] != y["markers"] || x["truncated"] != y["truncated"] || x["markers"] == null) return false
        if (x["thinned"] == "true") return true
        return x["known"] == y["known"] && x["breaks"] == y["breaks"] && x["known"] != null
    }

    private fun describe(x: String?, y: String?): String {
        if (x == null) return "missing in baseline"
        if (y == null) return "missing in candidate"
        val a = fields(x)["chunks"]?.split(','); val b = fields(y)["chunks"]?.split(',')
        if (a != null && b != null) {
            val at = (0 until maxOf(a.size, b.size)).firstOrNull { a.getOrNull(it) != b.getOrNull(it) }
            if (at != null) return "first differing chunk $at (lines ${at * CHUNK_LINES}..${(at + 1) * CHUNK_LINES - 1})"
        }
        return "value differs"
    }
}
