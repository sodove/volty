package ru.sodovaya.volty.data.history

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import ru.sodovaya.volty.data.bms.BegodeProtocol
import ru.sodovaya.volty.data.bms.BegodeT4Fixture
import ru.sodovaya.volty.domain.model.TelemetryObservation
import ru.sodovaya.volty.data.db.VoltyDatabase
import ru.sodovaya.volty.data.db.VoltyDatabaseProvider
import ru.sodovaya.volty.domain.history.*
import ru.sodovaya.volty.data.history.HistoryReaderFingerprint.Segment
import ru.sodovaya.volty.data.history.HistoryReaderFingerprint.Tolerance
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class HistoryReaderFingerprintTest {
    private val series = HistorySeriesKey("pack", "current_a")
    private fun tick(elapsed: Long, generation: Long = 1) = HistoryTick(10_000 + elapsed, 100 + elapsed, generation)
    private fun unknown(elapsed: Long, reason: HistoryUnknownReason, generation: Long = 1, at: HistoryTick = tick(elapsed, generation)) =
        HistorySample(at, series, null, reason)

    /** Renders chains as the brief writes them: `[first..last xN]`, or `[at]` for a single row. */
    private fun shape(segments: List<Segment>) = segments.map {
        when (it) {
            is Segment.One -> "[${it.sample.tick.elapsedMillis - 100}]"
            is Segment.Chain -> "[${it.first.tick.elapsedMillis - 100}..${it.last.tick.elapsedMillis - 100} x${it.count}]"
        }
    }

    @Test fun collapsedFormSplitsAtEveryRule2Boundary() {
        val dead = HistoryUnknownReason.UNAVAILABLE
        val samples = listOf(
            unknown(0, dead), unknown(300, dead), unknown(600, dead),
            unknown(900, HistoryUnknownReason.PARTIAL_SOURCE),   // another reason
            unknown(1200, dead),
            unknown(3300, dead),                                  // a step over HISTORY_MAX_INTERVAL_MILLIS
            unknown(3600, dead),                                  // a BLE_GAP marker at 3400 sits in (3300, 3600]
            unknown(3900, dead, generation = 2),                  // another clock generation
        )
        val global = HistoryMarker(tick(3400), HistoryMarkerKind.BLE_GAP)
        assertEquals(listOf("[0..600 x3]", "[900]", "[1200]", "[3300]", "[3600]", "[3900]"),
            shape(HistoryReaderFingerprint.collapseUnknownChains(samples, listOf(global))))

        // A marker scoped to ANOTHER source, or of a kind that is no gap, must not split: 3300 and 3600 join.
        val foreign = HistoryMarker(tick(3400), HistoryMarkerKind.BLE_GAP, "peer")
        val notAGap = HistoryMarker(tick(3400), HistoryMarkerKind.GPS_GAP)
        for (markers in listOf(listOf(foreign), listOf(notAGap))) {
            assertEquals(listOf("[0..600 x3]", "[900]", "[1200]", "[3300..3600 x2]", "[3900]"),
                shape(HistoryReaderFingerprint.collapseUnknownChains(samples, markers)))
        }
        // The same marker scoped to this source does split.
        assertEquals(listOf("[0..600 x3]", "[900]", "[1200]", "[3300]", "[3600]", "[3900]"),
            shape(HistoryReaderFingerprint.collapseUnknownChains(samples, listOf(global.copy(sourceId = "pack")))))

        // Epoch skew alone splits: same reason, generation and step, but the wall clock stepped by 5 s.
        val skewed = listOf(unknown(0, dead), unknown(300, dead), unknown(600, dead,
            at = HistoryTick(10_000 + 600 + 5_000, 100 + 600, 1)))
        assertEquals(listOf("[0..300 x2]", "[600]"), shape(HistoryReaderFingerprint.collapseUnknownChains(skewed, emptyList())))

        // A known row between two unknowns ends the chain, and the known row is never folded away.
        val known = HistorySample(tick(450), series, 1.5)
        assertEquals(listOf("[0]", "[450]", "[600]"),
            shape(HistoryReaderFingerprint.collapseUnknownChains(
                listOf(unknown(0, dead), known, unknown(600, dead)), emptyList())))
    }

    private enum class Mutation { NONE, VALUE, REASON, MARKER, GPS, BUCKET }

    @Test fun fingerprintIsDeterministic() = withTempDirectory { root ->
        val database = File(root, "fixture.db")
        buildFixture(database, Mutation.NONE)
        val first = File(root, "first.txt"); val second = File(root, "second.txt")
        val result = HistoryReaderFingerprint.run(database, first, workDir = root)
        HistoryReaderFingerprint.run(database, second, workDir = root)
        val text = first.readText()
        assertEquals(text, second.readText())
        assertTrue(first.readBytes().contentEquals(second.readBytes()))
        assertFalse(text.contains(root.absolutePath) || text.contains(root.name), "a temp path leaked into the fingerprint")

        // Not vacuously equal: the fixture's rides and the dead series are really in the text.
        val lines = text.lines()
        fun has(prefix: String) = lines.any { it.startsWith(prefix) }
        assertEquals(3L, result.linesPerReader["ride"])
        assertTrue(has("rideA|pack/current_a/|counts|known=0 chains=1"), text)
        assertTrue(has("rideA|pack/current_a/|samplesFull|n=1 "), text)   // 40 unknowns -> ONE segment: first..last
        assertTrue(has("rideA|pack/current_a/|chainMembers|n=1 "), text)
        assertTrue(has("rideB|ctl/controller_power_w/|counts|known=10 chains=10"), text)
        // The BLE_GAP marker inside the unknown run of rideB splits it: 5 + 5 rows, 2 chains.
        assertTrue(has("rideB|ctl/esc_temperature_c/|counts|known=0 chains=2"), text)
        // Readers that were once only "present": they carry real content, not a null or an empty hash.
        assertTrue(lines.any { it.startsWith("rideC|pack/power_w/|stats|") && "max=null" !in it && "complete=false" in it && "pendingWalk=true" in it }, text)
        assertTrue(lines.any { it.startsWith("rideC|pack/power_w/|walk|complete=false") }, text)
        assertTrue(lines.any { it.startsWith("rideB|ctl/controller_power_w/|energyRepair|energy=") && "energy=null" !in it }, text)
        assertTrue(lines.any { it.startsWith("rideC|pack/power_w/|buckets|n=") && !it.startsWith("rideC|pack/power_w/|buckets|n=0 ") }, text)
        assertTrue(lines.any { it.startsWith("rideA|pack/soc_percent/|overview|n=40 ") && "thinned=false" in it }, text)
        assertTrue(lines.any { it.startsWith("rideA|-|totals|n=") && !it.startsWith("rideA|-|totals|n=0") }, text)
        assertTrue(lines.any { it.startsWith("rideA|-|logui|bytes=") && "samples=40 " in it }, text)
        assertTrue(lines.any { it.startsWith("rideA|-|route|n=5 ") }, text)
        assertTrue(lines.any { it.startsWith("rideA|-|gpx|bytes=") }, text)
        assertTrue(has("rideC|pack/power_w/|w1.samples|"), text)
        assertTrue(has("rideC|pack/power_w/|w4.stats|"), text)
        assertTrue(has("rideA|-|w2.route|"), text)
        assertTrue(lines.filter { "|paging|" in it }.all { it.endsWith("|paging|ok") } && has("rideA|pack/current_a/|paging|ok"), text)

        // Errors: the only ones are the gpx of rideB and rideC (a ride with no fix has no track): expected, and nothing else.
        assertEquals(listOf("rideB|-|gpx|ERROR HistoryExportException: NO_ROUTE", "rideC|-|gpx|ERROR HistoryExportException: NO_ROUTE"), lines.filter { "|ERROR " in it }, text)
        assertEquals(emptyList(), result.unexpectedErrors)
        assertEquals(mapOf("gpx" to 2L), result.errorsPerReader)

        // The guarded paths really ran (review I5): a walk, the ride-end tail rule, an energy repair, a partial verdict.
        val exercise = result.exercise
        assertTrue(exercise.pendingWalk > 0 && exercise.walkPartial > 0 && exercise.tailRule > 0 && exercise.repairs > 0 &&
            exercise.incomplete > 0, exercise.toString())
        assertTrue(has("~|-|exercise|pendingWalk=${exercise.pendingWalk} "), text)
    }

    /** A control for the fingerprint itself: each kind of change must move the line of the reader that sees it. */
    @Test fun fingerprintMovesWhenOneThingChanges() = withTempDirectory { root ->
        fun fingerprint(mutation: Mutation): File {
            val database = File(root, "fixture-$mutation.db")
            buildFixture(database, mutation)
            val out = File(root, "out-$mutation.txt")
            HistoryReaderFingerprint.run(database, out, workDir = root)
            return out
        }
        val base = fingerprint(Mutation.NONE)
        val total = base.readLines().count { !it.startsWith("#") }
        fun changed(mutation: Mutation) = HistoryReaderFingerprint.compare(base, fingerprint(mutation)).map { it.location }

        val value = changed(Mutation.VALUE)
        assertTrue("rideA|pack/soc_percent/|samplesFull" in value, value.toString())
        val reason = changed(Mutation.REASON)
        assertTrue("rideA|pack/current_a/|samplesFull" in reason && "rideA|pack/current_a/|counts" in reason, reason.toString())
        val marker = changed(Mutation.MARKER)
        assertTrue("rideB|-|markers" in marker && "rideB|ctl/esc_temperature_c/|counts" in marker, marker.toString())
        val gps = changed(Mutation.GPS)
        assertTrue("rideA|-|route" in gps && "rideA|-|gpx" in gps && "rideA|-|logui" in gps, gps.toString())
        val bucket = changed(Mutation.BUCKET)
        assertTrue("rideC|pack/power_w/|buckets" in bucket && "rideC|pack/power_w/|stats" in bucket, bucket.toString())

        // "And only it": a change to one ride does not touch another ride's lines, and never everything.
        for ((name, locations, ride) in listOf(Triple("value", value, "rideA"), Triple("reason", reason, "rideA"),
            Triple("gps", gps, "rideA"), Triple("marker", marker, "rideB"), Triple("bucket", bucket, "rideC"))) {
            assertTrue(locations.all { it.startsWith("$ride|") || it.startsWith("~|") }, "$name touched another ride: $locations")
            assertTrue(locations.size < total / 2, "$name changed ${locations.size} of $total lines")
        }
        // A marker, a value, a reason and a fix change different readers: the lines are not interchangeable.
        assertFalse("rideA|-|route" in value || "rideB|-|markers" in value || "rideA|-|route" in reason)
        assertFalse("rideA|pack/soc_percent/|samplesFull" in gps)
    }

    @Test fun compareToleratesOnlyTheNamedDifferences() = withTempDirectory { root ->
        fun overview(n: Int, known: String, breaks: String, markers: Int = 1, thinned: Boolean = false) =
            "r|k|overview|n=$n sha=h$n known=$known breaks=$breaks markers=$markers truncated=false thinned=$thinned"
        fun diff(baseline: String, candidate: String, tolerate: Set<Tolerance>): List<HistoryReaderFingerprint.Difference> {
            val a = File(root, "a.txt").apply { writeText(baseline + "\n") }
            val b = File(root, "b.txt").apply { writeText(candidate + "\n") }
            return HistoryReaderFingerprint.compare(a, b, tolerate)
        }
        val overviewOnly = setOf(Tolerance.OVERVIEW_ROW_SELECTION)
        // Row selection moved but the drawn known points and break stretches are equal: named, accepted.
        assertEquals(1, diff(overview(10, "K", "B"), overview(12, "K", "B"), emptySet()).size)
        assertEquals(0, diff(overview(10, "K", "B"), overview(12, "K", "B"), overviewOnly).size)
        // A known point or a break stretch that changed within the budget is NOT accepted.
        assertEquals(1, diff(overview(10, "K", "B"), overview(12, "K2", "B"), overviewOnly).size)
        assertEquals(1, diff(overview(10, "K", "B"), overview(12, "K", "B2"), overviewOnly).size)
        // Above the budget the expanded series may pick other real points: accepted only when the baseline was thinned.
        assertEquals(0, diff(overview(600, "K", "B", thinned = true), overview(590, "K2", "B2"), overviewOnly).size)
        // Markers or truncation moving is never the row selection.
        assertEquals(1, diff(overview(600, "K", "B", thinned = true), overview(590, "K", "B", markers = 2), overviewOnly).size)
        // Any other reader differs even with every tolerance on.
        val all = Tolerance.entries.toSet()
        assertEquals(1, diff("r|k|samplesFull|n=1 sha=a", "r|k|samplesFull|n=1 sha=b", all).size)
        assertEquals(1, diff("r|k|w2.stats|min=1", "r|k|w2.stats|min=2", all).size)
        // chainMembers is its own named tolerance and nothing else is waved through with it.
        assertEquals(1, diff("r|k|chainMembers|n=2 sha=a", "r|k|chainMembers|n=2 sha=b", overviewOnly).size)
        assertEquals(0, diff("r|k|chainMembers|n=2 sha=a", "r|k|chainMembers|n=2 sha=b", setOf(Tolerance.CHAIN_MEMBERS)).size)
        // RANGE_CUT_EDGE: a window's cut-edge lines, only while that window's known rows and chain count are equal.
        val cut = setOf(Tolerance.RANGE_CUT_EDGE)
        val samples = "r|k|w1.samples|n=3 sha=K chains=2"
        fun lines(vararg text: String) = text.joinToString("\n")
        assertEquals(0, diff(lines(samples, "r|k|w1.unknownEdges|E"), lines(samples, "r|k|w1.unknownEdges|E2"), cut).size)
        assertEquals(2, diff(lines(samples, "r|k|w1.unknownEdges|E"),
            lines("r|k|w1.samples|n=3 sha=K chains=3", "r|k|w1.unknownEdges|E2"), cut).size)
        val windowOverview = { n: Int, known: String -> "r|k|w1.overview|n=$n sha=h$n known=$known breaks=B$n markers=0 truncated=false thinned=false" }
        assertEquals(0, diff(lines(samples, windowOverview(10, "K")), lines(samples, windowOverview(12, "K")), cut).size)
        assertEquals(1, diff(lines(samples, windowOverview(10, "K")), lines(samples, windowOverview(13, "K")), cut).size)
        assertEquals(1, diff(lines(samples, windowOverview(10, "K")), lines(samples, windowOverview(10, "K2")), cut).size)
        // Never a whole-ride line, never another window reader.
        assertEquals(1, diff(lines(samples, "r|k|unknownEdges|E"), lines(samples, "r|k|unknownEdges|E2"), cut).size)
        assertEquals(1, diff(lines(samples, "r|k|w1.stats|min=1"), lines(samples, "r|k|w1.stats|min=2"), cut).size)
        // The schema version and the physical summary are comments.
        assertEquals(0, diff("# userVersion=27\n# summary physicalSampleRows=9\nr|k|counts|known=1 chains=0",
            "# userVersion=29\n# summary physicalSampleRows=3\nr|k|counts|known=1 chains=0", emptySet()).size)
        // A missing or extra line is a difference, and a chunk mismatch is located.
        assertEquals(1, diff("r|k|a|1", "r|k|a|1\nr|k|b|2", emptySet()).size)
        assertTrue(diff("r|k|samplesFull|n=2000 sha=a chunks=aa,bb,cc,dd", "r|k|samplesFull|n=2000 sha=b chunks=aa,bb,xx,dd",
            emptySet()).single().detail.contains("chunk 2"))
    }

    /**
     * The real run: `-Dvolty.fingerprintDb=<absolute path of the file to copy> -Dvolty.fingerprintOut=<absolute file>`, optionally
     * `-Dvolty.fingerprintWork=<dir for the working copy>`, `-Dvolty.fingerprintCompare=<baseline to diff against>`
     * (with `-Dvolty.fingerprintTolerate=OVERVIEW_ROW_SELECTION,CHAIN_MEMBERS`) and `-Dvolty.fingerprintVerbose=true`;
     * skipped without the first two. Run it with `--tests "*HistoryReaderFingerprintTest.realDatabaseFingerprint" --rerun`.
     */
    @Test fun realDatabaseFingerprint() {
        val db = System.getProperty("volty.fingerprintDb")
        val out = System.getProperty("volty.fingerprintOut")
        if (db.isNullOrBlank() || out.isNullOrBlank()) {
            println("SKIPPED realDatabaseFingerprint: set -Dvolty.fingerprintDb and -Dvolty.fingerprintOut (see HistoryReaderFingerprint)")
            assumeTrue("volty.fingerprintDb / volty.fingerprintOut not set", false)
        }
        assertTrue(File(db).isAbsolute && File(out).isAbsolute, "pass absolute paths: a relative one resolves against composeApp/")
        File(out).delete()
        val started = System.currentTimeMillis()
        val work = System.getProperty("volty.fingerprintWork")?.takeIf { it.isNotBlank() }?.let(::File)
        val result = HistoryReaderFingerprint.run(File(db), File(out), System.getProperty("volty.fingerprintVerbose") == "true", work)
        val written = File(out)
        println("[fingerprint] wall ${System.currentTimeMillis() - started} ms, ${written.length()} bytes, $result")
        assertTrue(written.length() > 0 && written.lastModified() >= started - 1000, "the output was not freshly written")
        assertEquals(emptyList(), result.unexpectedErrors, "unexpected ERROR lines")
        System.getProperty("volty.fingerprintCompare")?.takeIf { it.isNotBlank() }?.let { baseline ->
            val tolerate = System.getProperty("volty.fingerprintTolerate").orEmpty().split(',').filter { it.isNotBlank() }
                .map { Tolerance.valueOf(it.trim()) }.toSet()
            val differences = HistoryReaderFingerprint.compare(File(baseline), written, tolerate)
            differences.take(50).forEach { println("[fingerprint] DIFF ${it.location}: ${it.detail}") }
            assertEquals(0, differences.size, "fingerprint differs from the baseline in ${differences.size} lines (tolerating $tolerate)")
        }
    }

    /**
     * [compact] picks the layout new rides record in. The tool's own tests pin V2 (false): their logui assertions read
     * the export, which reads V2 until Task 7 of the compact-history-storage plan.
     */
    private fun buildFixture(file: File, mutation: Mutation, compact: Boolean = false) {
        val driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
        try {
            VoltyDatabase.Schema.create(driver)
            val store = SqlDelightHistoryStore(VoltyDatabaseProvider(driver), recordCompact = compact)
            runBlocking {
                // Ride A: a dead series of 40 UNAVAILABLE rows beside a healthy one, and a short GPS track.
                val startA = HistoryTick(1_000_000, 5_000, 1)
                fun a(delta: Long) = HistoryTick(1_000_000 + delta, 5_000 + delta, 1)
                store.begin(HistoryRide("rideA", "bike", startA, null, HistoryRideStatus.RECORDING),
                    listOf(HistorySource("pack", HistorySourceKind.BATTERY, "Battery")))
                store.append(HistoryBatch("rideA",
                    (0L until 40L).map {
                        val reason = if (mutation == Mutation.REASON && it == 20L) HistoryUnknownReason.INVALID_VALUE else HistoryUnknownReason.UNAVAILABLE
                        HistorySample(a(it * 300), series, null, reason)
                    } + (0L until 40L).map {
                        val bump = if (mutation == Mutation.VALUE && it == 7L) 0.5 else 0.0
                        HistorySample(a(it * 300), HistorySeriesKey("pack", "soc_percent"), 80.0 - it + bump)
                    },
                    (0L until 5L).map {
                        val bump = if (mutation == Mutation.GPS && it == 2L) 0.0001 else 0.0
                        HistoryGpsFix(a(it * 2000), 10.0 + it * 0.001 + bump, 20.0, 5.0, null, HistoryGpsProvider.GPS)
                    },
                    emptyList(), "a1"))
                store.finish("rideA", a(12_000), HistoryRideStatus.COMPLETED)

                // Ride B: controller power alternating value / PARTIAL_SOURCE; a BLE_GAP inside an unknown chain.
                val startB = HistoryTick(2_000_000, 9_000, 1)
                fun b(delta: Long) = HistoryTick(2_000_000 + delta, 9_000 + delta, 1)
                store.begin(HistoryRide("rideB", "bike", startB, null, HistoryRideStatus.RECORDING),
                    listOf(HistorySource("ctl", HistorySourceKind.CONTROLLER, "Controller")))
                val gapKind = if (mutation == Mutation.MARKER) HistoryMarkerKind.GPS_GAP else HistoryMarkerKind.BLE_GAP
                store.append(HistoryBatch("rideB",
                    (0L until 20L).map {
                        val key = HistorySeriesKey("ctl", "controller_power_w")
                        if (it % 2 == 0L) HistorySample(b(it * 200), key, 100.0 + it)
                        else HistorySample(b(it * 200), key, null, HistoryUnknownReason.PARTIAL_SOURCE)
                    } + (0L until 10L).map {
                        HistorySample(b(it * 200), HistorySeriesKey("ctl", "esc_temperature_c"), null, HistoryUnknownReason.UNAVAILABLE)
                    },
                    emptyList(), listOf(HistoryMarker(b(900), gapKind)), "b1"))
                store.finish("rideB", b(4_000), HistoryRideStatus.COMPLETED)

                // Ride C: a battery pair with buckets. `pack` has twelve gap markers (12 s broken > 10 s) so the store's cheap
                // proof fails and the completeness walk runs; it stops at 59 s while `peer` goes on to 79 s and the ride
                // ends at 80 s, so the ride-end tail rule decides (the tail counts as broken because another source went on).
                val startC = HistoryTick(3_000_000, 20_000, 1)
                fun c(delta: Long) = HistoryTick(3_000_000 + delta, 20_000 + delta, 1)
                val rideC = HistoryRide("rideC", "bike", startC, null, HistoryRideStatus.RECORDING)
                val sources = listOf("pack", "peer").map { HistorySource(it, HistorySourceKind.BATTERY, it) }
                store.begin(rideC, sources)
                val raw = (0L until 60L).map { HistorySample(c(it * 1000), HistorySeriesKey("pack", "power_w"), 100.0 + it) } +
                    (0L until 80L).map { HistorySample(c(it * 1000), HistorySeriesKey("peer", "power_w"), 50.0) }
                val gaps = (0L until 12L).map { HistoryMarker(c(it * 2000 + 1500), HistoryMarkerKind.BLE_GAP, "pack") }
                val projected = HistoryRecordingProjection(rideC, sources).prepare(
                    HistoryBatch("rideC", raw, emptyList(), gaps, "c1"), true).batch
                store.append(projected)
                store.finish("rideC", c(80_000), HistoryRideStatus.COMPLETED)
            }
            // Buckets are untouched by the planned conversion, so this mutation stays valid against the converted layout.
            if (mutation == Mutation.BUCKET) driver.execute(null,
                "UPDATE HistoryBucketV2 SET maximum = maximum + 1 WHERE rideId = 'rideC' AND sourceId = 'pack'", 0)
        } finally { driver.close() }
    }

    // ---- Task 6: the fingerprint is the equivalence oracle between the two layouts -------------------------------

    /**
     * The same recordings written in V2 and in the compact layout give the same fingerprint. CHAIN_MEMBERS is never
     * needed (Ruling 2: a run reads back as its count). Every other whole-ride line is equal with no tolerance, except the
     * `overview` of a series with an UNEVENLY spaced run, whose interior unknown points carry interpolated ticks: there
     * `n`, `known`, `breaks`, `markers` and `truncated` are asserted equal and only `sha` differs (OVERVIEW_ROW_SELECTION,
     * see its KDoc). Window lines that differ are only `w<k>.unknownEdges` / `w<k>.overview` of a window cutting a run,
     * accepted by RANGE_CUT_EDGE (Ruling 4). The `tool` fixture (300 ms runs) needs no tolerance at all; the T4 replay
     * (uneven runs) needs the two and is asserted to, so the tolerances are not vacuous.
     *
     * The one excluded reader is `logui`: the export still reads `HistorySampleV2` until Task 7 (export, GPX and shape on
     * compact rides), so a compact ride's CSV has no sample rows yet. It is asserted to differ, so the exclusion cannot
     * hide a fixed reader. `gpx` reads no samples and is compared.
     */
    @Test fun compactLayoutFingerprintEqualsV2() = withTempDirectory { root ->
        fun fingerprint(name: String, build: (File) -> Unit): Pair<File, HistoryReaderFingerprint.Result> {
            val database = File(root, "$name.db")
            build(database)
            val out = File(root, "$name.txt")
            return out to HistoryReaderFingerprint.run(database, out, workDir = root)
        }
        val fixtures = listOf<Pair<String, (File, Boolean) -> Unit>>(
            "tool" to { f, c -> buildFixture(f, Mutation.NONE, c) },
            "replay" to { f, c -> buildReplayFixture(f, c) })
        for ((name, build) in fixtures) {
            val (v2, v2Result) = fingerprint("$name-v2") { build(it, false) }
            val (v3, v3Result) = fingerprint("$name-v3") { build(it, true) }
            val compactRows = JdbcSqliteDriver("jdbc:sqlite:${File(root, "$name-v3.db").absolutePath}").use { driver ->
                driver.executeQuery(null, "SELECT COUNT(*), TOTAL(runCount IS NOT NULL), " +
                    "(SELECT COUNT(*) FROM HistorySampleV2) FROM HistorySampleV3", { c ->
                    check(c.next().value)
                    QueryResult.Value(Triple(c.getLong(0)!!, c.getDouble(1)!!.toLong(), c.getLong(2)!!))
                }, 0).value
            }
            assertTrue(compactRows.first > 0 && compactRows.second > 0 && compactRows.third == 0L,
                "$name: the candidate is compact (rows, runs, V2 rows) = $compactRows")
            val raw = HistoryReaderFingerprint.compare(v2, v3)
            val logui = raw.filter { it.location.endsWith("|logui") }
            assertTrue(logui.isNotEmpty(), "$name: logui reads V2 until Task 7, so it must differ (else drop this exclusion)")
            val differences = raw - logui.toSet()
            val tolerated = HistoryReaderFingerprint.compare(v2, v3, setOf(Tolerance.OVERVIEW_ROW_SELECTION, Tolerance.RANGE_CUT_EDGE))
            assertEquals(emptyList(), tolerated.filterNot { it.location.endsWith("|logui") }, "$name: only the named tolerances")
            fun fields(value: String?) = value.orEmpty().split(' ').associate { it.substringBefore('=') to it.substringAfter('=') }
            for (d in differences) {
                val reader = d.location.substringAfterLast('|')
                if (reader == "overview") {
                    val (p, q) = fields(d.baseline) to fields(d.candidate)
                    for (field in listOf("n", "known", "breaks", "markers", "truncated")) assertEquals(p[field], q[field], "${d.location} $field")
                } else assertTrue(Regex("""w\d+\.(unknownEdges|overview)""").matches(reader), "$name: whole-ride ${d.location} differs")
            }
            if (name == "tool") assertEquals(emptyList(), differences, "evenly spaced runs: no tolerance at all")
            else assertTrue(differences.any { it.location.endsWith("|overview") } &&
                differences.any { it.location.endsWith(".unknownEdges") }, "$name: the uneven runs reach both tolerances")
            // A compact ride's CSV may even fail with NO_DATA (no V2 rows) until Task 7: the same exclusion, nothing else.
            assertEquals(v2Result.unexpectedErrors, v3Result.unexpectedErrors.filterNot { "|logui|" in it })
            assertEquals(v2Result.exercise, v3Result.exercise)
            val lines = v3.readLines()
            assertTrue(lines.filter { "|paging|" in it }.all { it.endsWith("|paging|ok") })
            assertTrue(lines.none { "|ERROR " in it && "|gpx|" !in it && "|logui|" !in it }, "$name: no reader failed on the compact layout")
            if (name == "replay") {
                val e = v3Result.exercise
                assertTrue(e.pendingWalk > 0 && e.tailRule > 0, "$name: the walk and the ride-end tail rule ran: $e")
            }
        }
    }

    /**
     * Ride T: the Begode T4 replay (frames, observations, projection, store) with a BLE_GAP of its first source.
     * Ride M: two batteries; `peer` is dead (one run) and goes silent at 30 s while `pack` goes on to 90 s and the ride
     * ends at 95 s (the ride-end tail rule); a BLE_GAP of `peer` committed in a LATER batch is back-dated into the stored
     * run (the writer cuts it; with a 300 ms step the cut is exact, see `splitStoredRunAt`); `pack` has a source-scoped
     * gap and the ride a global OVERFLOW.
     * Ride I: left RECORDING and closed by `recoverInterrupted`, whose INTERRUPTED marker lands on a run's last tick.
     */
    private fun buildReplayFixture(file: File, compact: Boolean) {
        val driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
        try {
            VoltyDatabase.Schema.create(driver)
            val store = SqlDelightHistoryStore(VoltyDatabaseProvider(driver), recordCompact = compact)
            runBlocking {
                suspend fun project(ride: HistoryRide, sources: List<HistorySource>, batches: List<HistoryBatch>) {
                    var projection = HistoryRecordingProjection(ride, sources)
                    batches.forEachIndexed { i, batch ->
                        val prepared = projection.prepare(batch, i == batches.lastIndex)
                        projection = prepared.next
                        store.append(prepared.batch)
                    }
                }
                // Ride T.
                val startT = HistoryTick(5_000_000, 1_000, 1)
                fun t(ms: Long) = HistoryTick(startT.epochMillis + ms, startT.elapsedMillis + ms, 1)
                val protocol = BegodeProtocol(cellCount = 24)
                val observations = mutableListOf<TelemetryObservation>()
                protocol.telemetryObserver = { observations += it }
                repeat(60) { BegodeT4Fixture.notifications.forEach { protocol.onNotification(it) } }
                val kinds = observations.map { historySourceKindForTelemetry(it.origin, it.role) }.distinct()
                val sourcesT = kinds.map { HistorySource(it.name.lowercase(), it, it.name) }
                val rideT = HistoryRide("rideT", "wheel", startT, null, HistoryRideStatus.RECORDING)
                store.begin(rideT, sourcesT)
                val samplesT = observations.flatMapIndexed { i, o ->
                    historyTelemetrySamples(sourcesT.single { it.kind == historySourceKindForTelemetry(o.origin, o.role) }, t(i * 55L), o)
                }
                val middle = samplesT[samplesT.size / 2].tick
                val gapT = HistoryMarker(HistoryTick(middle.epochMillis + 1, middle.elapsedMillis + 1, 1),
                    HistoryMarkerKind.BLE_GAP, sourcesT.first().id)
                val chunksT = samplesT.chunked(128)
                val carrier = chunksT.indexOfFirst { c -> c.any { it.tick.elapsedMillis >= gapT.tick.elapsedMillis } }
                project(rideT, sourcesT, chunksT.mapIndexed { i, c ->
                    HistoryBatch("rideT", c, emptyList(), if (i == carrier) listOf(gapT) else emptyList(), "t$i")
                })
                val lastT = samplesT.last().tick
                store.finish("rideT", HistoryTick(lastT.epochMillis + 400, lastT.elapsedMillis + 400, 1), HistoryRideStatus.COMPLETED)

                // Ride M.
                val startM = HistoryTick(6_000_000, 2_000, 1)
                fun m(ms: Long) = HistoryTick(startM.epochMillis + ms, startM.elapsedMillis + ms, 1)
                val sourcesM = listOf("pack", "peer").map { HistorySource(it, HistorySourceKind.BATTERY, it) }
                val rideM = HistoryRide("rideM", "bike", startM, null, HistoryRideStatus.RECORDING)
                store.begin(rideM, sourcesM)
                val packPower = HistorySeriesKey("pack", "power_w")
                val packSoc = HistorySeriesKey("pack", "soc_percent")
                val peerCurrent = HistorySeriesKey("peer", "current_a")
                fun slice(fromMs: Long, toMs: Long) = (fromMs until toMs step 300).flatMap { ms ->
                    buildList {
                        add(HistorySample(m(ms), packPower, 100.0 + (ms / 300) % 17))
                        // SOC: known, with a PARTIAL_SOURCE stretch between 40 s and 55 s.
                        add(if (ms in 40_000L until 55_000L) HistorySample(m(ms), packSoc, null, HistoryUnknownReason.PARTIAL_SOURCE)
                            else HistorySample(m(ms), packSoc, 90.0 - ms / 10_000.0))
                        if (ms < 30_000) add(HistorySample(m(ms), peerCurrent, null, HistoryUnknownReason.UNAVAILABLE))
                    }
                }
                project(rideM, sourcesM, listOf(
                    HistoryBatch("rideM", slice(0, 19_800), emptyList(), emptyList(), "m0"),
                    // Back-dated: 12.15 s lies inside peer's run, already stored up to 19.5 s. The slices keep the 300 ms
                    // grid: a first draft started the second slice at 20 s, the run became uneven (one 200 ms step) and
                    // the writer's interpolated cut moved the whole-ride chain edges by 40 ms (Task 5's stated bound).
                    HistoryBatch("rideM", slice(19_800, 49_800), emptyList(),
                        listOf(HistoryMarker(m(12_150), HistoryMarkerKind.BLE_GAP, "peer")), "m1"),
                    HistoryBatch("rideM", slice(49_800, 90_000), emptyList(),
                        listOf(HistoryMarker(m(60_150), HistoryMarkerKind.BLE_GAP, "pack"),
                            HistoryMarker(m(70_150), HistoryMarkerKind.OVERFLOW)), "m2")))
                store.finish("rideM", m(95_000), HistoryRideStatus.COMPLETED)

                // Ride I.
                val startI = HistoryTick(7_000_000, 3_000, 1)
                fun i(ms: Long) = HistoryTick(startI.epochMillis + ms, startI.elapsedMillis + ms, 1)
                val sourcesI = listOf(HistorySource("ctl", HistorySourceKind.CONTROLLER, "ctl"))
                val rideI = HistoryRide("rideI", "bike", startI, null, HistoryRideStatus.RECORDING)
                store.begin(rideI, sourcesI)
                val power = HistorySeriesKey("ctl", "controller_power_w")
                val temp = HistorySeriesKey("ctl", "esc_temperature_c")
                project(rideI, sourcesI, listOf(HistoryBatch("rideI", (0L until 100L).flatMap {
                    listOf(HistorySample(i(it * 300), power, 50.0 + it),
                        HistorySample(i(it * 300), temp, null, HistoryUnknownReason.UNAVAILABLE))
                }, emptyList(), emptyList(), "i0")))
                assertEquals(1, store.recoverInterrupted())
            }
        } finally { driver.close() }
    }

    private fun withTempDirectory(block: (File) -> Unit) {
        val root = Files.createTempDirectory("volty-fingerprint-test-").toFile()
        try { block(root) } finally {
            val canonical = root.canonicalFile
            check(canonical.parentFile == File(System.getProperty("java.io.tmpdir")).canonicalFile)
            check(canonical.name.startsWith("volty-fingerprint-test-"))
            check(canonical.deleteRecursively())
        }
    }
}
