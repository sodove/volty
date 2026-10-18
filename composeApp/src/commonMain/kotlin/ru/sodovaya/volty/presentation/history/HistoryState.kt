package ru.sodovaya.volty.presentation.history

enum class HistoryDestination { CATALOG, RIDE, CHARTS }

/** Independent read quality, one selected ride and one shared detail range/cursor. */
data class HistoryUiState(
    val destination: HistoryDestination = HistoryDestination.CATALOG,
    val selectedRideId: String? = null,
    val catalog: HistoryCatalogState = HistoryCatalogState(),
    val detail: HistoryDetailState = HistoryDetailState(),
    val route: HistoryRouteState = HistoryRouteState(),
    val previews: HistoryPreviewsState = HistoryPreviewsState(),
)
