package ru.sodovaya.volty.presentation.history

enum class HistoryScreenPhase { CATALOG, LOADING, NOT_FOUND, ERROR, READY }
data class HistoryScreenContent(val phase: HistoryScreenPhase, val detail: HistoryDetailState? = null,
    val route: HistoryRouteState? = null)
data class HistoryRideNeighbors(val previousId: String? = null, val nextId: String? = null)

/** The renderer must not pair an old route or detail with a newly selected ride/window. */
fun historyScreenContent(state: HistoryUiState): HistoryScreenContent {
    if (state.destination == HistoryDestination.CATALOG) return HistoryScreenContent(HistoryScreenPhase.CATALOG)
    val detail = state.detail
    if (detail.loading) return HistoryScreenContent(HistoryScreenPhase.LOADING)
    if (detail.error != null) return HistoryScreenContent(if (detail.error == HistoryReadError.NOT_FOUND)
        HistoryScreenPhase.NOT_FOUND else HistoryScreenPhase.ERROR)
    val id = state.selectedRideId
    if (id == null || detail.rideId != id || detail.ride?.id != id || detail.range == null)
        return HistoryScreenContent(HistoryScreenPhase.LOADING)
    return HistoryScreenContent(HistoryScreenPhase.READY, detail,
        state.route.takeIf { it.rideId == id && it.range == detail.range })
}

/** Only the loaded catalog order is known. No wrap, timestamp sorting or hidden page read. */
fun historyRideNeighbors(ids: List<String>, selectedId: String?): HistoryRideNeighbors {
    if (selectedId == null || ids.distinct().size != ids.size) return HistoryRideNeighbors()
    val index = ids.indexOf(selectedId)
    if (index < 0) return HistoryRideNeighbors()
    return HistoryRideNeighbors(ids.getOrNull(index - 1), ids.getOrNull(index + 1))
}
