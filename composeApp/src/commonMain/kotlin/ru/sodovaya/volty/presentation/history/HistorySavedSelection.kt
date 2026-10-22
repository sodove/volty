package ru.sodovaya.volty.presentation.history

import kotlinx.serialization.Serializable
import ru.sodovaya.volty.domain.history.*

/** View intent only. Data, export grants and in-flight work are always re-read. */
@Serializable
internal data class HistorySavedSelection(
    val rideId: String, val vehicleId: String, val storageKind: String,
    val originEpoch: Long, val originElapsed: Long, val originGeneration: Long,
    val from: Long, val through: Long, val cursor: Long?, val charts: Boolean,
    val metrics: List<SavedHistoryMetric>, val mapMetric: SavedHistoryMetric?,
    val favoriteFrom: Long?, val favoriteThrough: Long?,
    val favoriteId: String?, val favoriteName: String?, val favoriteRevision: Long?, val favoriteCreated: Long?,
) {
    fun source() = HistoryFavoriteSource(rideId, vehicleId, HistoryStorageKind.valueOf(storageKind),
        HistoryTick(originEpoch, originElapsed, originGeneration))
    fun range() = HistoryRange(from, through)
    fun favorite(): HistoryFavorite? = favoriteId?.let { HistoryFavorite(it, source(), HistoryRange(checkNotNull(favoriteFrom), checkNotNull(favoriteThrough)),
        checkNotNull(favoriteName), checkNotNull(favoriteRevision), checkNotNull(favoriteCreated)) }

    companion object {
        fun capture(state: HistoryUiState): HistorySavedSelection? {
            val detail = state.detail
            val ride = detail.ride?.takeIf { !detail.loading && detail.error == null && it.id == state.selectedRideId } ?: return null
            val range = detail.range ?: return null
            val source = ride.favoriteSource()
            val favorite = state.selectedFavorite?.takeIf { it.source == source }
            return HistorySavedSelection(ride.id, ride.vehicleId, source.kind.name,
                ride.started.epochMillis, ride.started.elapsedMillis, ride.started.clockGeneration,
                range.startEpochMillis, range.endEpochMillis, detail.cursorEpochMillis,
                state.destination == HistoryDestination.CHARTS, detail.selectedKeys.map(SavedHistoryMetric::capture),
                state.mapMetricKey?.let(SavedHistoryMetric::capture), favorite?.range?.startEpochMillis, favorite?.range?.endEpochMillis, favorite?.id, favorite?.name,
                favorite?.revision, favorite?.createdEpochMillis)
        }
    }
}

@Serializable
internal data class SavedHistoryMetric(val source: String, val metric: String, val cell: Int?) {
    fun key() = HistorySeriesKey(source, metric, cell)
    companion object {
        fun capture(key: HistorySeriesKey) = SavedHistoryMetric(key.sourceId, key.metricId, key.cellIndex)
    }
}
