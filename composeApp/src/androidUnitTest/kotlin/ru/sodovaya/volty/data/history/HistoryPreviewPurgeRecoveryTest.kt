package ru.sodovaya.volty.data.history

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import ru.sodovaya.volty.data.db.VoltyDatabase
import ru.sodovaya.volty.data.db.VoltyDatabaseProvider
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HistoryPreviewPurgeRecoveryTest {
    @Test
    fun pendingPurgeSurvivesDatabaseReopenAndAcknowledgementIsPersistent() = withTempDirectory { root ->
        val databaseFile = File(root, "history.db")
        val cacheDirectory = File(root, "preview")
        val previewFile = File(cacheDirectory, "$cacheKey.png")
        var driver = openDatabase(databaseFile)
        try {
            VoltyDatabase.Schema.create(driver)
            val journalBeforeRestart = HistoryPreviewPurgeJournal(VoltyDatabaseProvider(driver))
            val generation = journalBeforeRestart.request()
            HistoryPreviewFileCache(cacheDirectory).write(cacheKey, pngBytes)
            assertTrue(previewFile.isFile)
            assertContentEquals(pngBytes, previewFile.readBytes())
            assertEquals(generation, journalBeforeRestart.pendingGeneration())

            driver.close()
            driver = openDatabase(databaseFile)
            val journalAfterRestart = HistoryPreviewPurgeJournal(VoltyDatabaseProvider(driver))
            val recovery = HistoryPreviewPurgeRecovery(journalAfterRestart) {
                HistoryPreviewFileCache(cacheDirectory).clearOwned()
            }

            assertTrue(recovery.reconcile())
            assertFalse(previewFile.exists())
            assertNull(journalAfterRestart.pendingGeneration())

            driver.close()
            driver = openDatabase(databaseFile)
            val journalAfterSecondRestart = HistoryPreviewPurgeJournal(VoltyDatabaseProvider(driver))
            assertNull(journalAfterSecondRestart.pendingGeneration())
        } finally {
            driver.close()
        }
    }

    @Test
    fun failedFilesystemPurgeRetainsRequestUntilCachePathCanBeRepaired() = withTempDirectory { root ->
        val databaseFile = File(root, "history.db")
        val cachePath = File(root, "preview")
        val foreignBytes = byteArrayOf(10, 11, 12)
        val ordinaryFile = cachePath.apply { writeBytes(foreignBytes) }
        val preservedFile = File(root, "preserved-foreign-file.bin")
        val driver = openDatabase(databaseFile)
        try {
            VoltyDatabase.Schema.create(driver)
            val journal = HistoryPreviewPurgeJournal(VoltyDatabaseProvider(driver))
            val generation = journal.request()
            val recovery = HistoryPreviewPurgeRecovery(journal) {
                HistoryPreviewFileCache(cachePath).clearOwned()
            }

            assertFailsWith<IOException> { recovery.reconcile() }
            assertEquals(generation, journal.pendingGeneration())
            assertTrue(ordinaryFile.isFile)
            assertContentEquals(foreignBytes, ordinaryFile.readBytes())

            assertTrue(ordinaryFile.renameTo(preservedFile))
            assertTrue(recovery.reconcile())
            assertNull(journal.pendingGeneration())
            assertTrue(preservedFile.isFile)
            assertContentEquals(foreignBytes, preservedFile.readBytes())
        } finally {
            driver.close()
        }
    }

    @Test
    fun newerRequestIssuedDuringClearSurvivesOldAcknowledgement() = withTempDirectory { root ->
        val databaseFile = File(root, "history.db")
        val cacheDirectory = File(root, "preview")
        val previewFile = File(cacheDirectory, "$cacheKey.png")
        val driver = openDatabase(databaseFile)
        try {
            VoltyDatabase.Schema.create(driver)
            val journal = HistoryPreviewPurgeJournal(VoltyDatabaseProvider(driver))
            val firstGeneration = journal.request()
            val cache = HistoryPreviewFileCache(cacheDirectory)
            cache.write(cacheKey, pngBytes)
            assertTrue(previewFile.isFile)

            var secondGeneration: Long? = null
            val recovery = HistoryPreviewPurgeRecovery(journal) {
                cache.clearOwned()
                if (secondGeneration == null) secondGeneration = journal.request()
            }

            assertFalse(recovery.reconcile())
            val nextGeneration = assertNotNull(secondGeneration)
            assertNotEquals(firstGeneration, nextGeneration)
            assertEquals(nextGeneration, journal.pendingGeneration())
            assertFalse(previewFile.exists())

            assertTrue(recovery.reconcile())
            assertNull(journal.pendingGeneration())
            assertFalse(previewFile.exists())
        } finally {
            driver.close()
        }
    }

    private fun openDatabase(file: File): JdbcSqliteDriver =
        JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")

    private fun withTempDirectory(block: (File) -> Unit) {
        val root = Files.createTempDirectory("volty-preview-purge-recovery-").toFile()
        try {
            block(root)
        } finally {
            val canonicalRoot = root.canonicalFile
            val canonicalTemp = File(System.getProperty("java.io.tmpdir")).canonicalFile
            check(canonicalRoot.parentFile == canonicalTemp)
            check(canonicalRoot.name.startsWith("volty-preview-purge-recovery-"))
            check(canonicalRoot.deleteRecursively())
        }
    }

    private companion object {
        val cacheKey = "a".repeat(64)
        val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
    }
}
