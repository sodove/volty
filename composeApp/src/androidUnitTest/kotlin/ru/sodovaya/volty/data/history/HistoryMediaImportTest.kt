package ru.sodovaya.volty.data.history

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import ru.sodovaya.volty.data.db.*
import ru.sodovaya.volty.domain.history.*
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryMediaImportTest {
    private val pick = HistoryMediaPick("favorite", "content://picker/source", "image/png")
    private class Fixture(val root: File, val driver: JdbcSqliteDriver, val provider: VoltyDatabaseProvider,
        val journal: HistoryMediaJournal, val files: HistoryMediaFiles) {
        var source: (String) -> InputStream = { ByteArrayInputStream("abc".encodeToByteArray()) }
        var inspection: (File, String) -> HistoryMediaInspection = { _, mime -> HistoryMediaInspection(mime, null) }
        var opened = 0
        private var serial = 0
        fun id() = "aaaaaaaa-1111-4222-8333-${(++serial).toString().padStart(12, '0')}"
        fun importer(dispatcher: CoroutineDispatcher) = HistoryMediaImport(journal, files,
            { uri -> opened++; source(uri) }, { file, mime -> inspection(file, mime) },
            { file -> "content://volty-private/${file.name}" }, dispatcher, ::id)
    }
    private suspend fun fixture(block: suspend Fixture.() -> Unit) {
        val root = Files.createTempDirectory("history-media-import-").toFile()
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            VoltyDatabase.Schema.create(driver)
            val provider = VoltyDatabaseProvider(driver)
            provider.database.rideHistoryV2Queries.insertRide("ride", "bike", 1000, 10, 1, "RECORDING")
            provider.database.rideHistoryV2Queries.finishRide(2000, 1010, 1, "COMPLETED", "ride")
            SqlDelightHistoryFavorites(provider) { 3000 }.save("favorite", HistoryFavoriteSource("ride", "bike", HistoryStorageKind.V2,
                HistoryTick(1000, 10, 1)), HistoryRange(1000, 2000), "Favorite")
            val limits = HistoryMediaLimits(8, 128, 128, 16, 16)
            Fixture(root, driver, provider, HistoryMediaJournal(provider, limits), HistoryMediaFiles(File(root, "media"), 8)).block()
        } finally { driver.close(); root.deleteRecursively() }
    }
    private suspend fun expect(reason: HistoryMediaFailure, block: suspend () -> Unit) {
        assertEquals(reason, assertFailsWith<HistoryMediaException> { block() }.reason)
    }

    @Test fun successfulImportOwnsBytesAfterSourceRemovedAndOnlyOffersVerifiedContentUri() = runTest { fixture {
        val sourceFile = File(root, "picked.png").apply { writeText("abc") }
        source = { sourceFile.inputStream() }
        val owner = importer(StandardTestDispatcher(testScheduler))
        val saved = owner.importItem(pick)
        assertTrue(sourceFile.delete())
        assertEquals(HistoryMediaStatus.READY, saved.status); assertNull(saved.capturedEpochMillis)
        assertEquals(3, saved.bytes); assertEquals(saved, owner.list("favorite").items.single())
        assertEquals("content://volty-private/${saved.id}.media", owner.readableUri(saved.id))
        assertEquals(mapOf(saved.id to "content://volty-private/${saved.id}.media"), owner.list("favorite").previewUris)
        assertEquals(setOf("${saved.id}.media"), files.ownedFiles())
    } }
    @Test fun oversizeAndCorruptInspectionLeaveNoReadyRowOrFile() = runTest { fixture {
        val owner = importer(StandardTestDispatcher(testScheduler))
        source = { ByteArrayInputStream(ByteArray(9)) }
        expect(HistoryMediaFailure.LIMIT) { owner.importItem(pick) }
        assertTrue(journal.all().isEmpty()); assertTrue(files.ownedFiles().isEmpty())
        source = { ByteArrayInputStream("abc".encodeToByteArray()) }
        inspection = { _, _ -> throw HistoryMediaException(HistoryMediaFailure.INVALID_MEDIA) }
        expect(HistoryMediaFailure.INVALID_MEDIA) { owner.importItem(pick) }
        assertTrue(journal.all().isEmpty()); assertTrue(files.ownedFiles().isEmpty())
    } }
    @Test fun cancellationClosesSourceCleansReservationAndIsNotReportedAsStorageFailure() = runTest { fixture {
        var closed = false
        val cancel = CancellationException("cancel copy")
        source = { object : ByteArrayInputStream("abc".encodeToByteArray()) {
            override fun read(b: ByteArray, off: Int, len: Int): Int = throw cancel
            override fun close() { closed = true; super.close() }
        } }
        val owner = importer(StandardTestDispatcher(testScheduler))
        val thrown = assertFailsWith<CancellationException> { owner.importItem(pick) }
        // withContext stack-trace recovery may copy CancellationException while
        // retaining the original as its cause; object identity is not its API.
        assertEquals(cancel.message, thrown.message)
        assertTrue(generateSequence<Throwable>(thrown) { it.cause }.take(8).any { it === cancel })
        assertTrue(closed); assertTrue(journal.all().isEmpty()); assertTrue(files.ownedFiles().isEmpty())
    } }
    @Test fun cancellingActualImportJobDuringReadClosesSourceAndCleansItsJournal() = runTest { fixture {
        var closed = false; var reads = 0
        val owner = importer(StandardTestDispatcher(testScheduler))
        val job = launch(start = CoroutineStart.LAZY) { owner.importItem(pick) }
        source = { object : ByteArrayInputStream("abc".encodeToByteArray()) {
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                reads++; job.cancel(); return super.read(b, off, len)
            }
            override fun close() { closed = true; super.close() }
        } }
        job.start(); runCurrent(); job.join()
        assertEquals(1, reads); assertTrue(job.isCancelled); assertTrue(closed)
        assertTrue(journal.all().isEmpty()); assertTrue(files.ownedFiles().isEmpty())
    } }
    @Test fun favoriteDeletedDuringCopyCannotAcquirePublishedMedia() = runTest { fixture {
        source = { object : ByteArrayInputStream("abc".encodeToByteArray()) {
            private var deleted = false
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (!deleted) {
                    deleted = true
                    provider.database.transaction {
                        provider.database.historyMediaQueries.retireFavoriteMedia("favorite")
                        provider.database.historyFavoriteQueries.deleteFavorite("favorite")
                    }
                }
                return super.read(b, off, len)
            }
        } }
        val owner = importer(StandardTestDispatcher(testScheduler))
        expect(HistoryMediaFailure.FAVORITE_UNAVAILABLE) { owner.importItem(pick) }
        assertTrue(journal.all().isEmpty()); assertTrue(files.ownedFiles().isEmpty())
    } }
    @Test fun recoveryRepairsAllInterruptedStagesAndMissingOrCorruptReadyFiles() = runTest { fixture {
        fun seed(publish: Boolean, ready: Boolean): String {
            val id = id(); journal.reserve(id, "favorite", "image/png")
            val staged = files.stage(id, { ByteArrayInputStream("abc".encodeToByteArray()) }, {}, {})
            if (publish) files.publish(id)
            if (ready) journal.complete(id, staged.bytes, staged.sha256, null)
            return id
        }
        seed(false, false); seed(true, false)
        val missing = seed(true, true); files.remove(missing)
        val corrupt = seed(true, true); File(root, "media/$corrupt.media").writeText("xyz")
        val valid = seed(true, true)
        val orphan = id(); files.stage(orphan, { ByteArrayInputStream("orphan".encodeToByteArray()) }, {}, {}); files.publish(orphan)
        val foreign = File(root, "media/foreign.txt").apply { writeText("keep") }
        val owner = importer(StandardTestDispatcher(testScheduler))
        val report = owner.reconcile()
        assertEquals(4, report.removedItems); assertEquals(1, report.orphanFiles); assertEquals(0, report.pendingCleanup)
        assertEquals(listOf(valid), journal.all().map { it.id }); assertEquals(setOf("$valid.media"), files.ownedFiles())
        assertEquals("keep", foreign.readText()); assertEquals(valid, owner.list("favorite").items.single().id)
    } }
    @Test fun failedFileCleanupRetainsDurableTombstoneUntilNextRecovery() = runTest { fixture {
        val owner = importer(StandardTestDispatcher(testScheduler)); val saved = owner.importItem(pick)
        val media = File(root, "media"); val backup = File(root, "media-backup")
        assertTrue(media.renameTo(backup)); media.writeText("temporary filesystem failure")
        expect(HistoryMediaFailure.STORAGE) { owner.delete(saved.id) }
        assertEquals(HistoryMediaStatus.DELETING, journal.all().single().status)
        assertTrue(media.delete()); assertTrue(backup.renameTo(media))
        assertEquals(0, owner.reconcile().pendingCleanup); assertTrue(journal.all().isEmpty()); assertTrue(files.ownedFiles().isEmpty())
    } }
    @Test fun missingGrantOrNonContentSourceNeverLeavesAnAttachment() = runTest { fixture {
        val owner = importer(StandardTestDispatcher(testScheduler))
        expect(HistoryMediaFailure.ACCESS_DENIED) { owner.importItem(pick.copy(sourceUri = "file:///private/source")) }
        assertEquals(0, opened)
        source = { throw SecurityException("revoked") }
        expect(HistoryMediaFailure.ACCESS_DENIED) { owner.importItem(pick) }
        assertTrue(journal.all().isEmpty()); assertTrue(files.ownedFiles().isEmpty())
    } }
    @Test fun verifiedMimeReplacesProviderHintButCannotChangeImageIntoVideo() = runTest { fixture {
        val owner = importer(StandardTestDispatcher(testScheduler))
        inspection = { _, _ -> HistoryMediaInspection("image/jpeg", 1500) }
        val saved = owner.importItem(pick)
        assertEquals("image/jpeg", saved.mime); assertEquals(1500L, saved.capturedEpochMillis)
        assertEquals("image/jpeg", journal.ready("favorite").single().mime)
        inspection = { _, _ -> HistoryMediaInspection("video/mp4", null) }
        expect(HistoryMediaFailure.INVALID_MEDIA) { owner.importItem(pick) }
        assertEquals(listOf(saved), journal.ready("favorite"))
    } }
}
