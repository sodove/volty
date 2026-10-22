package ru.sodovaya.volty.data.history

import ru.sodovaya.volty.data.db.VoltyDatabaseProvider
import ru.sodovaya.volty.domain.history.*

/** Synchronous SQLite transactions; the platform import/recovery owner supplies its IO dispatcher. */
@OptIn(kotlin.time.ExperimentalTime::class)
class HistoryMediaJournal(provider: VoltyDatabaseProvider, private val limits: HistoryMediaLimits = HistoryMediaLimits(),
    private val nowEpochMillis: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() }) {
    private val database = provider.database
    private val queries = database.historyMediaQueries
    fun reserve(id: String, favoriteId: String, mime: String): HistoryMediaRecord = database.transactionWithResult {
        require(MEDIA_ID.matches(id))
        if (mime !in MIMES) fail(HistoryMediaFailure.INVALID_MEDIA)
        if (database.historyFavoriteQueries.getFavorite(favoriteId).executeAsOneOrNull() == null)
            fail(HistoryMediaFailure.FAVORITE_UNAVAILABLE)
        val entries = all()
        if (entries.any { it.id == id }) fail(HistoryMediaFailure.INVALID_MEDIA)
        val related = entries.filter { it.favoriteId == favoriteId }
        // Incomplete/deleting imports retain their full reservation until the
        // file owner confirms cleanup. They may still occupy that much disk.
        fun HistoryMediaRecord.reservedBytes() = if (bytes == 0L) limits.itemBytes else bytes
        if (entries.size >= limits.totalItems || related.size >= limits.favoriteItems ||
            entries.sumOf { it.reservedBytes() } > limits.totalBytes - limits.itemBytes ||
            related.sumOf { it.reservedBytes() } > limits.favoriteBytes - limits.itemBytes)
            fail(HistoryMediaFailure.LIMIT)
        queries.reserveMedia(id, favoriteId, mime, nowEpochMillis())
        record(id) ?: fail(HistoryMediaFailure.STORAGE)
    }
    fun complete(id: String, bytes: Long, sha256: String, capturedEpochMillis: Long?, verifiedMime: String? = null): HistoryMediaRecord = database.transactionWithResult {
        if (bytes !in 1..limits.itemBytes || !sha256.matches(Regex("[0-9a-f]{64}"))) fail(HistoryMediaFailure.INVALID_MEDIA)
        val existing = record(id) ?: fail(HistoryMediaFailure.FAVORITE_UNAVAILABLE)
        val mime = verifiedMime ?: existing.mime
        if (mime !in MIMES || mime.substringBefore('/') != existing.mime.substringBefore('/')) fail(HistoryMediaFailure.INVALID_MEDIA)
        if (existing.status == HistoryMediaStatus.DELETING ||
            database.historyFavoriteQueries.getFavorite(existing.favoriteId).executeAsOneOrNull() == null)
            fail(HistoryMediaFailure.FAVORITE_UNAVAILABLE)
        if (existing.status == HistoryMediaStatus.READY) {
            if (existing.bytes == bytes && existing.sha256 == sha256 && existing.capturedEpochMillis == capturedEpochMillis && existing.mime == mime)
                return@transactionWithResult existing
            fail(HistoryMediaFailure.INVALID_MEDIA)
        }
        queries.completeMedia(bytes, sha256, capturedEpochMillis, mime, id)
        record(id) ?: fail(HistoryMediaFailure.STORAGE)
    }
    fun all(): List<HistoryMediaRecord> {
        val rows = queries.allMedia(limits.totalItems.toLong() + 1).executeAsList()
        if (rows.size > limits.totalItems) fail(HistoryMediaFailure.STORAGE)
        return rows.map { it.toMediaRecord() }
    }
    fun ready(favoriteId: String): List<HistoryMediaRecord> {
        val rows = queries.readyMedia(favoriteId, limits.favoriteItems.toLong() + 1).executeAsList()
        if (rows.size > limits.favoriteItems) fail(HistoryMediaFailure.STORAGE)
        return rows.map { it.toMediaRecord() }
    }
    fun retire(id: String) { queries.retireMedia(id) }
    fun retireIncomplete() { queries.retireIncompleteMedia() }
    fun forgetDeleted(id: String) = database.transaction {
        val current = record(id)
        check(current == null || current.status == HistoryMediaStatus.DELETING) { "Media must be retired before cleanup" }
        queries.forgetDeletedMedia(id)
    }
    private fun record(id: String) = queries.getMedia(id).executeAsOneOrNull()?.toMediaRecord()
    private fun fail(reason: HistoryMediaFailure): Nothing = throw HistoryMediaException(reason)
    private companion object {
        val MEDIA_ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        val MIMES = setOf("image/jpeg", "image/png", "image/webp", "image/gif", "image/heic", "image/heif", "image/avif",
            "video/mp4", "video/webm", "video/quicktime", "video/x-m4v")
    }
}

private fun ru.sodovaya.volty.data.db.HistoryMedia.toMediaRecord() = HistoryMediaRecord(
    id, favoriteId, mime, capturedEpochMillis, byteCount, sha256, HistoryMediaStatus.valueOf(state), createdEpochMillis)
