package ru.sodovaya.volty.domain.history

/** Confirmation covers this exact closed recording and the displayed child counts. */
class HistoryRideDeletionRequest internal constructor(val ride: HistoryRide, val favoriteCount: Long,
    val mediaCount: Long, internal val issuer: Any) {
    init { require(favoriteCount >= 0 && mediaCount >= 0) }
}
data class HistoryRideDeletionResult(val source: HistoryFavoriteSource, val cleanupPending: Boolean)
enum class HistoryRideDeletionFailure { NOT_FOUND, ACTIVE_RECORDING, SOURCE_CHANGED, CONFLICT, STORAGE }
class HistoryRideDeletionException(val reason: HistoryRideDeletionFailure) : IllegalStateException(reason.name)

interface HistoryRideDeletionRepository {
    suspend fun prepare(ride: HistoryRide): HistoryRideDeletionRequest
    suspend fun confirm(request: HistoryRideDeletionRequest): HistoryRideDeletionResult
}
