package ru.sodovaya.volty.presentation.history

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import ru.sodovaya.volty.domain.history.*

interface HistoryComponent {
    val state: StateFlow<HistoryUiState>
    fun refresh()
    fun loadOlder()
    fun openRide(id: String)
    fun showCharts()
    fun loadSourceMetrics(sourceId: String)
    fun addMetric(key: HistorySeriesKey)
    fun removeMetric(key: HistorySeriesKey)
    fun moveMetric(key: HistorySeriesKey, index: Int)
    fun selectTime(epochMillis: Long?)
    fun selectRange(range: HistoryRange)
    fun selectMapMetric(key: HistorySeriesKey?)
    fun beginWindowGesture(): HistoryWindowGestureToken?
    fun transformWindowGesture(token: HistoryWindowGestureToken, zoom: Double, anchor: Double, pan: Double)
    fun finishWindowGesture(token: HistoryWindowGestureToken)
    fun cancelWindowGesture(token: HistoryWindowGestureToken)
    fun loadMoreRoute()
    fun retryRoute()
    fun setVisibleRides(ids: List<String>)
    fun retryPreview(id: String)
    fun loadPresets()
    fun savePreset(name: String)
    fun applyPreset(id: String)
    fun deletePreset(id: String)
    fun loadFavorites()
    fun loadOlderFavorites()
    fun saveFavorite(name: String, range: HistoryRange, editing: HistoryFavorite? = null)
    fun deleteFavorite(favorite: HistoryFavorite)
    fun openFavorite(favorite: HistoryFavorite)
    fun retrySelectedRide()
    fun loadMedia()
    fun beginMediaPick(): HistoryMediaPickerToken?
    fun mediaPicked(token: HistoryMediaPickerToken, uri: String?, mime: String?)
    fun deleteMedia(item: HistoryMediaRecord)
    fun openMedia(item: HistoryMediaRecord)
    fun consumeMediaOpen()
    fun cancelMediaImport()
    fun requestRideDeletion(ride: HistoryRide)
    fun confirmRideDeletion()
    fun dismissRideDeletion()
    fun dismissRideDeletionResult()
    fun setVisiblePreviewImages(keys: List<HistoryPreviewImageKey>)
    fun back()
}

/** One screen selection and cursor. Child read owners share its UI dispatcher
 * and lifecycle; catalog errors and reads do not replace selected detail/route.
 */
class DefaultHistoryComponent(componentContext: ComponentContext, parentScope: CoroutineScope,
    repository: HistoryReadRepository, readTick: () -> HistoryTick, private val onBack: () -> Unit,
    presetStore: HistoryMetricPresetStore? = null, imageLoader: HistoryPreviewImageLoader? = null,
    previewDispatcher: CoroutineDispatcher = Dispatchers.Default,
    favoriteStore: HistoryFavoriteRepository? = null,
    mediaRepository: HistoryMediaRepository? = null,
    deletionRepository: HistoryRideDeletionRepository? = null) : HistoryComponent {
    private val lifetime = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + lifetime)
    private val catalog = HistoryCatalogComponent(componentContext, scope, repository)
    private val detail = HistoryDetailComponent(componentContext, scope, repository, readTick)
    private val route = HistoryRouteComponent(componentContext, scope, repository)
    private val previews = HistoryPreviewComponent(componentContext, scope, repository, PREVIEW_GPS_SILENCE_MILLIS,previewDispatcher)
    private val presets = HistoryPresetsComponent(componentContext, scope, presetStore)
    private val favorites = HistoryFavoritesComponent(componentContext, scope, favoriteStore)
    private val media = HistoryMediaComponent(componentContext, scope, mediaRepository)
    private val deletion = HistoryRideDeletionComponent(componentContext, scope, deletionRepository)
    override fun requestRideDeletion(ride: HistoryRide) {
        if (!lifetime.isActive) return
        val current = state.value
        if (current.detail.ride?.favoriteSource() == ride.favoriteSource() ||
            current.catalog.rides.any { it.favoriteSource() == ride.favoriteSource() }) deletion.prepare(ride)
    }
    override fun confirmRideDeletion() { if (lifetime.isActive) deletion.confirm() }
    override fun dismissRideDeletion() { if (lifetime.isActive) deletion.dismiss() }
    override fun dismissRideDeletionResult() {
        if (!lifetime.isActive) return
        deletion.consumeResult()
        mutableState.value = mutableState.value.copy(deletion = deletion.state.value)
    }
    override fun loadMedia() { if (qualifiedMediaFavorite() != null) media.refresh() }
    override fun beginMediaPick(): HistoryMediaPickerToken? = if (qualifiedMediaFavorite() != null) media.beginPick() else null
    override fun mediaPicked(token: HistoryMediaPickerToken, uri: String?, mime: String?) {
        if (qualifiedMediaFavorite() != null) media.picked(token, uri, mime)
    }
    override fun deleteMedia(item: HistoryMediaRecord) { if (qualifiedMediaFavorite() != null) media.delete(item) }
    override fun openMedia(item: HistoryMediaRecord) { if (qualifiedMediaFavorite() != null) media.open(item) }
    override fun consumeMediaOpen() { if (lifetime.isActive) media.consumeOpen() }
    override fun cancelMediaImport() { if (lifetime.isActive) media.cancelImport() }

    private fun qualifiedMediaFavorite(): String? {
        if (!lifetime.isActive || selectedRideId == null) return null
        val current = mutableState.value
        val favorite = current.selectedFavorite ?: return null
        val detail = current.detail
        return favorite.id.takeIf { !detail.loading && detail.error == null && detail.ride?.id == selectedRideId &&
            detail.ride?.favoriteSource() == favorite.source }
    }
    private fun syncMediaFavorite() { media.selectFavorite(qualifiedMediaFavorite()) }
    override fun loadFavorites() { if (lifetime.isActive) favorites.refresh() }
    override fun loadOlderFavorites() { if (lifetime.isActive) favorites.loadOlder() }
    @OptIn(kotlin.uuid.ExperimentalUuidApi::class)
    override fun saveFavorite(name: String, range: HistoryRange, editing: HistoryFavorite?) = withSelection {
        if (state.value.favorites.busy || !state.value.favorites.available) return@withSelection
        val ride = state.value.detail.ride ?: return@withSelection
        if (editing != null && editing.source != ride.favoriteSource()) return@withSelection
        val id = editing?.id ?: kotlin.uuid.Uuid.random().toString()
        pendingFavoriteSave = PendingFavoriteSave(id, selection, windowIntent, ride.favoriteSource())
        favorites.save(id, ride.favoriteSource(), range, name, editing?.revision)
    }
    override fun deleteFavorite(favorite: HistoryFavorite) { if (lifetime.isActive) favorites.delete(favorite) }
    override fun openFavorite(favorite: HistoryFavorite) { openSelection(favorite.source.rideId, favorite) }
    override fun retrySelectedRide() {
        if (!lifetime.isActive) return
        val id = selectedRideId ?: return
        openSelection(id, state.value.selectedFavorite)
    }
    private val previewImages = imageLoader?.let { HistoryPreviewImagesComponent(componentContext,scope,it) }
    private val mutableState = MutableStateFlow(HistoryUiState())
    override val state = mutableState.asStateFlow()
    private var selectedRideId: String? = null
    private var selectedSource: HistoryFavoriteSource? = null
    private var selection = Any()
    private var previewRevision = 0L
    private var visiblePreviewIds = emptyList<String>()
    private var catalogIntent = Any()
    private var windowIntent = Any()
    private data class PendingFavoriteSave(val id: String, val selection: Any, val windowIntent: Any, val source: HistoryFavoriteSource)
    private var pendingFavoriteSave: PendingFavoriteSave? = null
    private var handledDeletion: HistoryRideDeletionResult? = null
    private var windowGesture: WindowGesture? = null
    private class WindowGesture(val token: HistoryWindowGestureToken, val selection: Any, val intent: Any,
        val rideId: String, val base: HistoryRange, val bounds: HistoryRange)

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            deletion.state.collect { next ->
                if (!lifetime.isActive) return@collect
                mutableState.value = mutableState.value.copy(deletion = next)
                val result = next.result
                if (result != null && result !== handledDeletion) {
                    handledDeletion = result
                    if (selectedSource == result.source) clearSelection()
                    visiblePreviewIds = visiblePreviewIds.filterNot { it == result.source.rideId }
                    previewImages?.forget(result.source.rideId)
                    catalog.forget(result.source)
                    refresh()
                    favorites.refresh()
                }
            }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            catalog.state.collect { next ->
                if (lifetime.isActive) {
                    mutableState.value = mutableState.value.copy(catalog = next)
                    syncVisiblePreviews()
                }
            }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) { detail.state.collect(::onDetail) }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            presets.state.collect { if (lifetime.isActive) mutableState.value = mutableState.value.copy(presets = it) }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            favorites.state.collect { next ->
                if (lifetime.isActive) {
                    val old = mutableState.value.selectedFavorite
                    val captured = selection
                    val pending = pendingFavoriteSave
                    val activate = pending != null && pending.id == next.savedId && selection === pending.selection &&
                        windowIntent === pending.windowIntent && mutableState.value.detail.ride?.favoriteSource() == pending.source
                    val selected = (if (activate) next.items.singleOrNull { it.id == next.savedId } else null)
                        ?: old?.takeUnless { it.id == next.deletedId }?.let { favorite ->
                        next.items.singleOrNull { it.id == favorite.id } ?: favorite }
                    mutableState.value = mutableState.value.copy(favorites = next, selectedFavorite = selected)
                    syncMediaFavorite()
                    if (lifetime.isActive && selection === captured && activate && selected != null && selected.id == next.savedId &&
                        mutableState.value.detail.ride?.favoriteSource() == selected.source && mutableState.value.detail.range != selected.range)
                        detail.selectRange(selected.range)
                    if (!next.busy) pendingFavoriteSave = null
                }
            }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            media.state.collect { next ->
                if (lifetime.isActive && next.favoriteId == qualifiedMediaFavorite())
                    mutableState.value = mutableState.value.copy(media = next)
            }
        }
        previewImages?.let { owner -> scope.launch(start = CoroutineStart.UNDISPATCHED) {
            owner.state.collect { if (lifetime.isActive) mutableState.value = mutableState.value.copy(previewImages = it) }
        } }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            previews.state.collect { if (lifetime.isActive) mutableState.value = mutableState.value.copy(previews = it) }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            route.state.collect { next ->
                val current = mutableState.value
                if (lifetime.isActive && selectedRideId != null && next.rideId == selectedRideId && next.range == current.detail.range)
                    mutableState.value = current.copy(route = next)
            }
        }
        componentContext.lifecycle.doOnDestroy { close() }
    }

    private fun onDetail(next: HistoryDetailState) {
        if (!lifetime.isActive || selectedRideId == null || next.rideId != selectedRideId) return
        if (next.error == null && next.ride != null) selectedSource = next.ride.favoriteSource()
        val captured = selection
        val ride = next.ride; val range = next.range
        val needsRoute = ride != null && range != null && (route.state.value.rideId != ride.id || route.state.value.range != range)
        val current = mutableState.value
        mutableState.value = current.copy(detail = next, mapMetricKey = current.mapMetricKey?.takeIf { it in next.selectedKeys }, route = if (needsRoute)
            HistoryRouteState(rideId = ride?.id, range = range, loading = true) else current.route)
        syncMediaFavorite()
        // UI publication can synchronously choose another ride or leave history.
        if (lifetime.isActive && selection === captured && needsRoute) route.open(checkNotNull(ride), checkNotNull(range))
    }

    override fun refresh() {
        if (!lifetime.isActive) return
        val captured = Any(); catalogIntent = captured
        previewRevision = if (previewRevision == Long.MAX_VALUE) 0 else previewRevision + 1
        mutableState.value = mutableState.value.copy(previewRevision = previewRevision)
        syncVisiblePreviews()
        if (lifetime.isActive && catalogIntent === captured) catalog.refresh()
    }
    override fun loadOlder() { if (lifetime.isActive) { catalogIntent = Any(); catalog.loadOlder() } }

    override fun openRide(id: String) { openSelection(id, null) }
    private fun openSelection(id: String, favorite: HistoryFavorite?) {
        if (!lifetime.isActive) return
        require(id.isNotBlank())
        deletion.dismiss()
        selectedSource = favorite?.source ?: state.value.catalog.rides.singleOrNull { it.id == id }?.favoriteSource()
            ?: state.value.detail.ride?.takeIf { it.id == id }?.favoriteSource()
        selectedRideId = id; selection = Any()
        windowIntent = Any(); windowGesture = null
        val captured = selection
        mutableState.value = mutableState.value.copy(destination = HistoryDestination.RIDE, selectedRideId = id,
            detail = HistoryDetailState(rideId = id, loading = true), route = HistoryRouteState(), windowPreview = null,
            mapMetricKey = null, selectedFavorite = favorite, media = HistoryMediaState(available = media.state.value.available))
        media.selectFavorite(null)
        if (!lifetime.isActive || selection !== captured) return
        route.clear()
        if (lifetime.isActive && selection === captured) detail.openRide(id, favorite)
    }

    override fun showCharts() = withSelection {
        if (mutableState.value.detail.ride != null) {
            windowIntent = Any(); windowGesture = null
            mutableState.value = mutableState.value.copy(destination = HistoryDestination.CHARTS, windowPreview = null)
        }
    }
    override fun loadSourceMetrics(sourceId: String) = withSelection { detail.loadSourceMetrics(sourceId) }
    override fun addMetric(key: HistorySeriesKey) = withSelection { detail.addMetric(key) }
    override fun removeMetric(key: HistorySeriesKey) = withSelection { detail.removeMetric(key) }
    override fun moveMetric(key: HistorySeriesKey, index: Int) = withSelection { detail.moveMetric(key, index) }
    override fun selectTime(epochMillis: Long?) = withSelection { detail.selectTime(epochMillis) }
    override fun selectMapMetric(key: HistorySeriesKey?) = withSelection {
        val current = mutableState.value
        if (key == null || key in current.detail.selectedKeys) mutableState.value = current.copy(mapMetricKey = key)
    }
    override fun selectRange(range: HistoryRange) = withSelection {
        val captured = selection; val intent = Any(); windowIntent = intent; windowGesture = null
        mutableState.value = mutableState.value.copy(windowPreview = null)
        if (lifetime.isActive && selection === captured && windowIntent === intent) detail.selectRange(range)
    }
    override fun beginWindowGesture(): HistoryWindowGestureToken? {
        if (!lifetime.isActive) return null
        val current = mutableState.value
        val id = selectedRideId ?: return null
        val base = current.detail.range ?: return null
        val bounds = current.detail.availableRange ?: return null
        if (current.detail.ride?.id != id || historyTransformWindow(base, bounds) == null) return null
        val gesture = WindowGesture(HistoryWindowGestureToken(), selection, Any(), id, base, bounds)
        windowIntent = gesture.intent; windowGesture = gesture
        mutableState.value = current.copy(windowPreview = base)
        return gesture.token.takeIf { currentGesture(it) === gesture }
    }
    override fun transformWindowGesture(token: HistoryWindowGestureToken, zoom: Double, anchor: Double, pan: Double) {
        val gesture = currentGesture(token) ?: return
        val current = mutableState.value
        val next = historyTransformWindow(current.windowPreview ?: gesture.base, gesture.bounds, zoom, anchor, pan) ?: return
        mutableState.value = current.copy(windowPreview = next)
    }
    override fun finishWindowGesture(token: HistoryWindowGestureToken) {
        val gesture = currentGesture(token) ?: return
        val next = mutableState.value.windowPreview ?: gesture.base
        val intent = Any(); windowIntent = intent; windowGesture = null
        mutableState.value = mutableState.value.copy(windowPreview = null)
        // Clearing preview can synchronously navigate or begin a newer gesture.
        if (lifetime.isActive && windowIntent === intent && selection === gesture.selection &&
            detail.state.value.range == gesture.base && detail.state.value.availableRange == gesture.bounds)
            detail.selectRange(next)
    }
    override fun cancelWindowGesture(token: HistoryWindowGestureToken) {
        if (currentGesture(token) == null) return
        windowIntent = Any(); windowGesture = null
        mutableState.value = mutableState.value.copy(windowPreview = null)
    }
    private fun currentGesture(token: HistoryWindowGestureToken): WindowGesture? = windowGesture?.takeIf {
        lifetime.isActive && it.token === token && it.selection === selection && it.intent === windowIntent &&
            selectedRideId == it.rideId && detail.state.value.range == it.base && detail.state.value.availableRange == it.bounds
    }
    override fun loadMoreRoute() = withSelection { route.loadMore() }
    override fun retryRoute() = withSelection { route.retry() }
    override fun setVisibleRides(ids: List<String>) {
        if (!lifetime.isActive) return
        visiblePreviewIds = ids.distinct().take(20)
        syncVisiblePreviews()
    }
    override fun retryPreview(id: String) { if (lifetime.isActive) previews.retry(id) }
    override fun loadPresets() { if (lifetime.isActive) presets.refresh() }
    override fun savePreset(name: String) = withSelection {
        val current = detail.state.value
        val ride = current.ride ?: return@withSelection
        val title = name.trim()
        if (title.isEmpty() || title.length > 80 || current.selectedKeys.size > 128) return@withSelection
        // A name identifies one layout within one vehicle. Saving that name
        // again updates its layout; equal names on another vehicle are separate.
        val id = "history-preset-v1:${ride.vehicleId.length}:${ride.vehicleId}:$title"
        presets.save(captureHistoryMetricPreset(id, title, ride.vehicleId, current.selectedKeys, current.sources))
    }
    override fun applyPreset(id: String) = withSelection {
        val current = detail.state.value
        val ride = current.ride ?: return@withSelection
        val preset = presets.state.value.presets.singleOrNull { it.id == id } ?: return@withSelection
        if (preset.vehicleId != ride.vehicleId) return@withSelection
        val resolved = resolveHistoryMetricPreset(preset, ride.vehicleId, current.sources)
        if (resolved.keys.isEmpty() && resolved.unavailable.isNotEmpty()) return@withSelection
        detail.replaceMetrics(resolved.keys)
    }
    override fun deletePreset(id: String) { if (lifetime.isActive) presets.delete(id) }
    override fun setVisiblePreviewImages(keys: List<HistoryPreviewImageKey>) {
        if (!lifetime.isActive) return
        require(keys.size <= 20 && keys.map { it.rideId }.distinct().size == keys.size)
        val data = previews.state.value.previews
        previewImages?.setVisible(keys.filter { it.rideId in visiblePreviewIds && it.revision == previewRevision &&
            data[it.rideId]?.segments == it.segments })
    }

    private fun syncVisiblePreviews() {
        if (!lifetime.isActive) return
        val models = mutableState.value.catalog.rides.associateBy { it.id }
        previews.setVisible(visiblePreviewIds.mapNotNull(models::get), previewRevision)
    }

    private fun withSelection(action: () -> Unit) {
        if (lifetime.isActive && selectedRideId != null) action()
    }

    override fun back() {
        if (!lifetime.isActive) return
        deletion.dismiss()
        when (mutableState.value.destination) {
            HistoryDestination.CHARTS -> {
                windowIntent = Any(); windowGesture = null
                mutableState.value = mutableState.value.copy(destination = HistoryDestination.RIDE, windowPreview = null)
            }
            HistoryDestination.CATALOG -> onBack()
            HistoryDestination.RIDE -> clearSelection()
        }
    }

    private fun clearSelection() {
        selectedRideId = null; selectedSource = null; selection = Any(); pendingFavoriteSave = null
        windowIntent = Any(); windowGesture = null
        val captured = selection
        mutableState.value = mutableState.value.copy(destination = HistoryDestination.CATALOG, selectedRideId = null,
            detail = HistoryDetailState(), route = HistoryRouteState(), windowPreview = null, mapMetricKey = null,
            selectedFavorite = null, media = HistoryMediaState(available = media.state.value.available))
        media.selectFavorite(null)
        if (lifetime.isActive && selection === captured) { detail.clear(); route.clear() }
    }

    fun close() {
        selection = Any(); catalogIntent = Any(); selectedRideId = null; selectedSource = null
        windowIntent = Any(); windowGesture = null
        catalog.close(); detail.close(); route.close(); previews.close(); presets.close(); favorites.close(); media.close(); deletion.close(); previewImages?.close(); lifetime.cancel()
    }
}

// Display-only silence bound; explicit recorded GPS/continuity markers always veto a bridge.
private const val PREVIEW_GPS_SILENCE_MILLIS = 5_000L
