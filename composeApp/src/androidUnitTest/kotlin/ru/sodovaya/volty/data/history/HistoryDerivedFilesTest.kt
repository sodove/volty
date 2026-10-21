package ru.sodovaya.volty.data.history

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File
import java.nio.file.Files
import ru.sodovaya.volty.data.db.*
import kotlin.test.*

class HistoryDerivedFilesTest {
    private fun fixture(block: (VoltyDatabaseProvider, File) -> Unit) {
        val root = Files.createTempDirectory("volty-derived-test-").toFile()
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try { VoltyDatabase.Schema.create(driver); block(VoltyDatabaseProvider(driver), root) }
        finally { driver.close(); root.deleteRecursively() }
    }
    @Test fun purgeAcknowledgesOnlyAfterBothOwnedNamespacesAreRemoved() = fixture { provider, root ->
        val preview = HistoryPreviewFileCache(File(root, "previews"))
        preview.write("a".repeat(64), byteArrayOf(1))
        val exports = File(root, "exports").apply { mkdirs() }
        val ready = File(exports, "volty-ride-12345678-1234-1234-1234-123456789abc.json").apply { writeText("private GPS") }
        val temporary = File(exports, "volty-export-tmp-v1-123.tmp").apply { writeText("partial") }
        val foreign = File(exports, "keep.json").apply { writeText("keep") }
        val nested = File(exports, "nested").apply { mkdirs() }
        val nestedFile = File(nested, ready.name).apply { writeText("keep nested") }
        val journal = HistoryPreviewPurgeJournal(provider); journal.request()
        assertTrue(HistoryDerivedFiles(preview, exports, provider).reconcileLocked())
        assertNull(journal.pendingGeneration())
        assertNull(preview.read("a".repeat(64)))
        assertFalse(ready.exists()); assertFalse(temporary.exists())
        assertEquals("keep", foreign.readText()); assertEquals("keep nested", nestedFile.readText())
    }
    @Test fun inaccessibleExportNamespaceDoesNotAcknowledgeOrDiscardRecoveryRequest() = fixture { provider, root ->
        val blocked = File(root, "exports").apply { writeText("ordinary file") }
        val journal = HistoryPreviewPurgeJournal(provider); val generation = journal.request()
        val files = HistoryDerivedFiles(HistoryPreviewFileCache(File(root, "previews")), blocked, provider)
        assertFailsWith<java.io.IOException> { files.reconcileLocked() }
        assertEquals(generation, journal.pendingGeneration())
        assertEquals("ordinary file", blocked.readText())
    }
}
