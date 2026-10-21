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
}
