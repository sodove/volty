package ru.sodovaya.volty.domain.history

enum class HistoryStorageKind { LEGACY, V2 }

/** Immutable recording identity. A later end tick does not create a different recording. */
data class HistoryFavoriteSource(
    val rideId: String,
    val vehicleId: String,
    val kind: HistoryStorageKind,
    val started: HistoryTick,
)

fun HistoryRide.favoriteSource() = HistoryFavoriteSource(id, vehicleId,
    if (status == HistoryRideStatus.LEGACY) HistoryStorageKind.LEGACY else HistoryStorageKind.V2, started)

data class HistoryFavorite(
    val id: String,
    val source: HistoryFavoriteSource,
    val range: HistoryRange,
    val name: String,
    val revision: Long,
    val createdEpochMillis: Long,
)

enum class HistoryFavoriteFailure { INVALID_NAME, INVALID_RANGE, SOURCE_UNAVAILABLE, SOURCE_CHANGED, CONFLICT }
class HistoryFavoriteException(val reason: HistoryFavoriteFailure) : IllegalStateException(reason.name)

interface HistoryFavoriteRepository {
    /** Null revision creates; a matching revision edits. Source identity cannot be edited. */
    suspend fun save(id: String, source: HistoryFavoriteSource, range: HistoryRange, name: String,
        expectedRevision: Long? = null): HistoryFavorite
    suspend fun get(id: String): HistoryFavorite?
    suspend fun page(before: HistoryCursor? = null): HistoryPage<HistoryFavorite>
    suspend fun delete(id: String, expectedRevision: Long)
}
