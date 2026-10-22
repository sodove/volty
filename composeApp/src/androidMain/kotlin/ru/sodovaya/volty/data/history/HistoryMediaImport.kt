package ru.sodovaya.volty.data.history

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.sodovaya.volty.domain.history.*
import java.io.File
import java.io.InputStream
import java.util.UUID

data class HistoryMediaInspection(val mime: String, val capturedEpochMillis: Long?)

/** Android's picker supplies an ephemeral content URI; only the private copy is durable. */
class HistoryMediaImport(
    private val journal: HistoryMediaJournal,
    private val files: HistoryMediaFiles,
    private val openSource: (String) -> InputStream,
    private val inspect: (File, String) -> HistoryMediaInspection,
    private val toReadableUri: (File) -> String,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : HistoryMediaRepository {
    private val mutex = Mutex()
    override suspend fun importItem(pick: HistoryMediaPick): HistoryMediaRecord = action {
        if (!pick.sourceUri.startsWith("content://")) fail(HistoryMediaFailure.ACCESS_DENIED)
        recover(verifyReady = false)
        val id = newId()
        journal.reserve(id, pick.favoriteId, pick.mime)
        try {
            val context = currentCoroutineContext()
            var inspection: HistoryMediaInspection? = null
            val staged = files.stage(id, { openSource(pick.sourceUri) }, { context.ensureActive() }) { file ->
                inspection = inspect(file, pick.mime)
                if (inspection?.mime?.substringBefore('/') != pick.mime.substringBefore('/')) fail(HistoryMediaFailure.INVALID_MEDIA)
            }
            context.ensureActive()
            files.publish(id)
            context.ensureActive()
            val inspected = checkNotNull(inspection)
            journal.complete(id, staged.bytes, staged.sha256, inspected.capturedEpochMillis, inspected.mime)
        } catch (failure: Exception) {
            // Retire metadata before touching files. Failed cleanup remains a
            // durable tombstone, including when the caller has been cancelled.
            withContext(NonCancellable) {
                try {
                    journal.retire(id)
                    if (!cleanup(id)) failure.addSuppressed(java.io.IOException("Media cleanup pending"))
                } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
            }
            throw failure
        }
    }

    override suspend fun list(favoriteId: String): HistoryMediaListing = action {
        val report = recover(verifyReady = true, favoriteId = favoriteId)
        val verified = journal.ready(favoriteId).mapNotNull { record ->
            currentCoroutineContext().ensureActive()
            val file = files.verifiedFile(record.id, record.bytes, checkNotNull(record.sha256)) ?: return@mapNotNull null
            val uri = toReadableUri(file)
            if (!uri.startsWith("content://")) fail(HistoryMediaFailure.STORAGE)
            record to uri
        }
        // A favorite deletion can run in its own SQLite transaction while a
        // large file is being checked. Do not publish its earlier snapshot.
        val currentIds = journal.ready(favoriteId).map { it.id }.toSet()
        val retained = verified.filter { it.first.id in currentIds }
        HistoryMediaListing(retained.map { it.first }, report, retained.associate { it.first.id to it.second })
    }

    override suspend fun delete(id: String): Unit = action {
        if (journal.all().none { it.id == id }) return@action
        journal.retire(id)
        if (!cleanup(id)) fail(HistoryMediaFailure.STORAGE)
    }

    override suspend fun reconcile(): HistoryMediaRecovery = action { recover(verifyReady = true) }
    override suspend fun cleanupRetired(): HistoryMediaRecovery = action { recover(verifyReady = false) }

    override suspend fun readableUri(id: String): String = action {
        val record = journal.all().singleOrNull { it.id == id && it.status == HistoryMediaStatus.READY }
            ?: fail(HistoryMediaFailure.INVALID_MEDIA)
        if (journal.ready(record.favoriteId).none { it.id == id }) fail(HistoryMediaFailure.FAVORITE_UNAVAILABLE)
        val file = files.verifiedFile(id, record.bytes, checkNotNull(record.sha256))
        if (file == null) {
            journal.retire(id); cleanup(id)
            fail(HistoryMediaFailure.INVALID_MEDIA)
        }
        currentCoroutineContext().ensureActive()
        if (journal.ready(record.favoriteId).none { it.id == id }) fail(HistoryMediaFailure.FAVORITE_UNAVAILABLE)
        toReadableUri(file).also { if (!it.startsWith("content://")) fail(HistoryMediaFailure.STORAGE) }
    }

    private suspend fun recover(verifyReady: Boolean, favoriteId: String? = null): HistoryMediaRecovery {
        journal.retireIncomplete()
        var removed = 0
        var pending = 0
        journal.all().filter { it.status == HistoryMediaStatus.DELETING }.forEach {
            currentCoroutineContext().ensureActive()
            if (cleanup(it.id)) removed++ else pending++
        }
        if (verifyReady) {
            val candidates = if (favoriteId == null) journal.all().filter { it.status == HistoryMediaStatus.READY }
                else journal.ready(favoriteId)
            candidates.forEach { record ->
                currentCoroutineContext().ensureActive()
                val present = try { files.verifiedFile(record.id, record.bytes, checkNotNull(record.sha256)) != null }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) { pending++; return@forEach }
                if (!present) {
                    journal.retire(record.id)
                    if (cleanup(record.id)) removed++ else pending++
                }
            }
        }
        val retainedIds = journal.all().map { it.id }.toSet()
        val orphanIds = files.ownedFiles().map { it.substringBeforeLast('.') }.toSet() - retainedIds
        var orphans = 0
        orphanIds.forEach { id ->
            currentCoroutineContext().ensureActive()
            try { files.remove(id); orphans++ }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { pending++ }
        }
        return HistoryMediaRecovery(removed, orphans, pending)
    }

    private fun cleanup(id: String): Boolean = try {
        files.remove(id)
        journal.forgetDeleted(id)
        true
    } catch (cancel: CancellationException) { throw cancel }
    catch (_: Exception) { false }

    private suspend fun <T> action(block: suspend () -> T): T = withContext(dispatcher) {
        mutex.withLock {
            try { block() }
            catch (cancel: CancellationException) { throw cancel }
            catch (failure: HistoryMediaException) { throw failure }
            catch (failure: HistoryMediaFileException) { fail(failure.reason) }
            catch (_: SecurityException) { fail(HistoryMediaFailure.ACCESS_DENIED) }
            catch (_: Exception) { fail(HistoryMediaFailure.STORAGE) }
        }
    }
    private fun fail(reason: HistoryMediaFailure): Nothing = throw HistoryMediaException(reason)
}
