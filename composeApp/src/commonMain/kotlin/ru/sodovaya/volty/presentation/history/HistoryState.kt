package ru.sodovaya.volty.presentation.history

enum class HistoryDestination { CATALOG, RIDE, CHARTS }
class HistoryWindowGestureToken internal constructor()

/** Independent read quality, one selected ride and one shared detail range/cursor. */
data class HistoryUiState(
    val destination: HistoryDestination = HistoryDestination.CATALOG,
    val selectedRideId: String? = null,
    val catalog: HistoryCatalogState = HistoryCatalogState(),
    val detail: HistoryDetailState = HistoryDetailState(),
    val route: HistoryRouteState = HistoryRouteState(),
    val previews: HistoryPreviewsState = HistoryPreviewsState(),
    val presets: HistoryPresetsState = HistoryPresetsState(),
    val favorites: HistoryFavoritesState = HistoryFavoritesState(),
    val selectedFavorite: ru.sodovaya.volty.domain.history.HistoryFavorite? = null,
    val media: HistoryMediaState = HistoryMediaState(),
    val previewImages: HistoryPreviewImagesState = HistoryPreviewImagesState(),
    val previewRevision: Long = 0,
    val windowPreview: ru.sodovaya.volty.domain.history.HistoryRange? = null,
    val mapMetricKey: ru.sodovaya.volty.domain.history.HistorySeriesKey? = null,
)
