package ru.sodovaya.volty.data.history

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import ru.sodovaya.volty.data.db.VoltyDatabase
import ru.sodovaya.volty.data.db.VoltyDatabaseProvider
import ru.sodovaya.volty.domain.history.*
import ru.sodovaya.volty.data.history.HistoryReaderFingerprint.Segment
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

    @Test fun fingerprintIsDeterministic() = withTempDirectory { root ->
        val database = File(root, "fixture.db")
        buildFixture(database)
        val first = File(root, "first.txt"); val second = File(root, "second.txt")
        val counts = HistoryReaderFingerprint.run(database, first)
        HistoryReaderFingerprint.run(database, second)
        val text = first.readText()
        assertEquals(text, second.readText())
        assertTrue(first.readBytes().contentEquals(second.readBytes()))

        // Not vacuously equal: the fixture's rides and the dead series are really in the text.
        val lines = text.lines()
        assertTrue(lines.any { it.startsWith("rideA|pack/current_a/|counts|known=0 unknown=40 chains=1") }, text)
        assertTrue(lines.any { it.startsWith("rideA|pack/current_a/|samplesFull|n=1 ") }, text)   // 40 unknowns -> ONE segment: first, last, x40
        assertTrue(lines.any { it.startsWith("rideB|ctl/controller_power_w/|counts|known=10 unknown=10 chains=10") }, text)
        // The BLE_GAP marker inside the unknown run of rideB splits it: 5 + 5 rows, 2 chains.
        assertTrue(lines.any { it.startsWith("rideB|ctl/esc_temperature_c/|counts|known=0 unknown=10 chains=2") }, text)
        assertTrue(lines.none { it.contains("|ERROR ") && !it.contains("|gpx|") && !it.contains("|route|") }, text)
        assertEquals(2L, counts["ride"])
        assertTrue(lines.last { it.isNotEmpty() }.startsWith("# summary rides=2 series="), text)
    }

    /** The real run: `-Dvolty.fingerprintDb=<copy source> -Dvolty.fingerprintOut=<file>`; skipped without them. */
    @Test fun realDatabaseFingerprint() {
        val db = System.getProperty("volty.fingerprintDb")
        val out = System.getProperty("volty.fingerprintOut")
        if (db.isNullOrBlank() || out.isNullOrBlank()) {
            println("SKIPPED realDatabaseFingerprint: set -Dvolty.fingerprintDb and -Dvolty.fingerprintOut (see HistoryReaderFingerprint)")
            assumeTrue("volty.fingerprintDb / volty.fingerprintOut not set", false)
        }
        val started = System.nanoTime()
        val counts = HistoryReaderFingerprint.run(File(db), File(out), System.getProperty("volty.fingerprintVerbose") == "true")
        println("[fingerprint] wall ${(System.nanoTime() - started) / 1_000_000} ms, ${File(out).length()} bytes, lines per reader: $counts")
    }

    private fun buildFixture(file: File) {
        val driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
        try {
            VoltyDatabase.Schema.create(driver)
            val store = SqlDelightHistoryStore(VoltyDatabaseProvider(driver))
            runBlocking {
                // Ride A: a dead series of 40 UNAVAILABLE rows beside a healthy one, and a short GPS track.
                val startA = HistoryTick(1_000_000, 5_000, 1)
                fun a(delta: Long) = HistoryTick(1_000_000 + delta, 5_000 + delta, 1)
                store.begin(HistoryRide("rideA", "bike", startA, null, HistoryRideStatus.RECORDING),
                    listOf(HistorySource("pack", HistorySourceKind.BATTERY, "Battery")))
                store.append(HistoryBatch("rideA",
                    (0L until 40L).map { HistorySample(a(it * 300), series, null, HistoryUnknownReason.UNAVAILABLE) } +
                        (0L until 40L).map { HistorySample(a(it * 300), HistorySeriesKey("pack", "soc_percent"), 80.0 - it) },
                    (0L until 5L).map { HistoryGpsFix(a(it * 2000), 10.0 + it * 0.001, 20.0, 5.0, null, HistoryGpsProvider.GPS) },
                    emptyList(), "a1"))
                store.finish("rideA", a(12_000), HistoryRideStatus.COMPLETED)

                // Ride B: controller power alternating value / PARTIAL_SOURCE; a BLE_GAP inside an unknown chain.
                val startB = HistoryTick(2_000_000, 9_000, 1)
                fun b(delta: Long) = HistoryTick(2_000_000 + delta, 9_000 + delta, 1)
                store.begin(HistoryRide("rideB", "bike", startB, null, HistoryRideStatus.RECORDING),
                    listOf(HistorySource("ctl", HistorySourceKind.CONTROLLER, "Controller")))
                store.append(HistoryBatch("rideB",
                    (0L until 20L).map {
                        val key = HistorySeriesKey("ctl", "controller_power_w")
                        if (it % 2 == 0L) HistorySample(b(it * 200), key, 100.0 + it)
                        else HistorySample(b(it * 200), key, null, HistoryUnknownReason.PARTIAL_SOURCE)
                    } + (0L until 10L).map {
                        HistorySample(b(it * 200), HistorySeriesKey("ctl", "esc_temperature_c"), null, HistoryUnknownReason.UNAVAILABLE)
                    },
                    emptyList(), listOf(HistoryMarker(b(900), HistoryMarkerKind.BLE_GAP)), "b1"))
                store.finish("rideB", b(4_000), HistoryRideStatus.COMPLETED)
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
