package ru.sodovaya.volty.domain.history

internal data class HistoryRideDeletionEntry(val ride: HistoryRide, val favoriteCount: Long, val mediaCount: Long) {
    init { require(favoriteCount >= 0 && mediaCount >= 0) }
}

/** Confirmation covers the exact closed recordings and their displayed child counts. */
class HistoryRideDeletionRequest internal constructor(entries: List<HistoryRideDeletionEntry>, internal val issuer: Any) {
    internal val entries = entries.toList()
    val rides = this.entries.map { it.ride }
    val favoriteCount = this.entries.sumOf { it.favoriteCount }
    val mediaCount = this.entries.sumOf { it.mediaCount }
}

data class HistoryRideDeletionResult(val sources: Set<HistoryFavoriteSource>, val cleanupPending: Boolean)
enum class HistoryRideDeletionFailure { NOT_FOUND, ACTIVE_RECORDING, SOURCE_CHANGED, CONFLICT, STORAGE }
class HistoryRideDeletionException(val reason: HistoryRideDeletionFailure) : IllegalStateException(reason.name)

interface HistoryRideDeletionRepository {
    suspend fun prepare(ride: HistoryRide): HistoryRideDeletionRequest = prepare(listOf(ride))
    suspend fun prepare(rides: List<HistoryRide>): HistoryRideDeletionRequest
    suspend fun confirm(request: HistoryRideDeletionRequest): HistoryRideDeletionResult
}
