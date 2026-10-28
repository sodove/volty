package ru.sodovaya.volty.data.history

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlin.test.*

class HistoryExportFileCacheTest {
    private fun fixture(block: (File) -> Unit) {
        val root = Files.createTempDirectory("volty-export-cache-test-").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }
    private fun File.ready(index: Int, size: Int, time: Long) = File(this,
        "volty-ride-00000000-0000-0000-0000-${index.toString().padStart(12, '0')}.json").apply {
        writeBytes(ByteArray(size)); Files.setLastModifiedTime(toPath(), FileTime.fromMillis(time))
    }
    @Test fun reservesBoundedBytesAndEntryBeforeWriteAndRemovesAbandonedTempsOnly() = fixture { root ->
        val oldest = root.ready(1, 4, 1500); val second = root.ready(2, 4, 1600); val third = root.ready(3, 4, 1700)
        val temp = File(root, "volty-export-tmp-v1-123.tmp").apply { writeText("partial") }
        val foreign = File(root, "keep.json").apply { writeText("keep") }
        HistoryExportFileCache(root, maxBytes = 12, maxEntries = 3, maxAgeMillis = 1000, now = { 2000 }).prepare(4)
        assertFalse(oldest.exists()); assertTrue(second.exists()); assertTrue(third.exists())
        assertFalse(temp.exists()); assertEquals("keep", foreign.readText())
        assertEquals(8L, listOf(second, third).sumOf { it.length() })
    }
    @Test fun expiresOnlyOwnedRegularFilesAndClearDoesNotRecurseIntoNamedDirectory() = fixture { root ->
        val expired = root.ready(1, 4, 500); val recent = root.ready(2, 4, 1500)
        val nested = File(root, "volty-ride-00000000-0000-0000-0000-000000000003.json").apply { mkdirs() }
        val keep = File(nested, "keep").apply { writeText("keep") }
        val cache = HistoryExportFileCache(root, maxBytes = 12, maxEntries = 3, maxAgeMillis = 1000, now = { 2000 })
        cache.prepare(0, 0)
        assertFalse(expired.exists()); assertTrue(recent.exists()); assertEquals("keep", keep.readText())
        cache.clearOwned(); assertFalse(recent.exists()); assertEquals("keep", keep.readText())
    }
    @Test fun namedExportsAreOwnedAndOnlyCurrentNamesMayBeShared() = fixture { root ->
        val named = File(root, "volty_Мой-EXN_2026-10-29_23-59-58.csv").apply { writeText("t_day:Time:s:3:0:1\n") }
        val records = File(root, "volty_GotWay_59672_2026-10-29_23-59-58_records.csv").apply { writeText("x") }
        val foreign = File(root, "volty_notes.csv").apply { writeText("keep") }
        HistoryExportFileCache(root).clearOwned()
        assertFalse(named.exists()); assertFalse(records.exists()); assertEquals("keep", foreign.readText())
        assertTrue(HistoryExportFileCache.isReadyName("volty_Мой-EXN_2026-10-29_23-59-58.csv"))
        assertTrue(HistoryExportFileCache.isReadyName("volty_ride_2026-10-29_23-59-58.gpx"))
        assertFalse(HistoryExportFileCache.isReadyName("volty-ride-00000000-0000-0000-0000-000000000001.json"))
        assertFalse(HistoryExportFileCache.isReadyName("volty_../x_2026-10-29_23-59-58.csv"))
        assertFalse(HistoryExportFileCache.isReadyName("volty_a_2026-10-29_23-59-58.json"))
    }
}
