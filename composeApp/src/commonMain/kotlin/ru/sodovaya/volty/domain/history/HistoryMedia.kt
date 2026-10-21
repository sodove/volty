package ru.sodovaya.volty.domain.history

enum class HistoryMediaStatus { IMPORTING, READY, DELETING }
data class HistoryMediaRecord(
    val id: String,
    val favoriteId: String,
    val mime: String,
    val capturedEpochMillis: Long?,
    val bytes: Long,
    val sha256: String?,
    val status: HistoryMediaStatus,
    val createdEpochMillis: Long = 0,
) {
    val relativePath: String get() = "$id.media"
}

data class HistoryMediaLimits(
    val itemBytes: Long = 256L * 1024 * 1024,
    val totalBytes: Long = 2L * 1024 * 1024 * 1024,
    val favoriteBytes: Long = 1024L * 1024 * 1024,
    val favoriteItems: Int = 64,
    val totalItems: Int = 512,
) {
    init {
        require(itemBytes in 1..256L * 1024 * 1024)
        require(totalBytes in itemBytes..16L * 1024 * 1024 * 1024)
        require(favoriteBytes in itemBytes..totalBytes)
        require(favoriteItems in 1..64 && totalItems in favoriteItems..512)
    }
}

enum class HistoryMediaFailure { FAVORITE_UNAVAILABLE, LIMIT, INVALID_MEDIA, STORAGE, ACCESS_DENIED }
class HistoryMediaException(val reason: HistoryMediaFailure) : IllegalStateException(reason.name)

data class HistoryMediaPick(val favoriteId: String, val sourceUri: String, val mime: String)
data class HistoryMediaRecovery(val removedItems: Int = 0, val orphanFiles: Int = 0, val pendingCleanup: Int = 0)
data class HistoryMediaListing(val items: List<HistoryMediaRecord>, val recovery: HistoryMediaRecovery,
    val previewUris: Map<String, String> = emptyMap())

interface HistoryMediaRepository {
    suspend fun importItem(pick: HistoryMediaPick): HistoryMediaRecord
    suspend fun list(favoriteId: String): HistoryMediaListing
    suspend fun delete(id: String)
    suspend fun reconcile(): HistoryMediaRecovery
    suspend fun cleanupRetired(): HistoryMediaRecovery = reconcile()
    suspend fun readableUri(id: String): String
}
