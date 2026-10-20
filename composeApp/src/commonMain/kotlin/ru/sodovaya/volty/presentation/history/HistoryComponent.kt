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
    fun setVisiblePreviewImages(keys: List<HistoryPreviewImageKey>)
    fun back()
}

/** One screen selection and cursor. Child read owners share its UI dispatcher
 * and lifecycle; catalog errors and reads do not replace selected detail/route.
 */
class DefaultHistoryComponent(componentContext: ComponentContext, parentScope: CoroutineScope,
    repository: HistoryReadRepository, readTick: () -> HistoryTick, private val onBack: () -> Unit,
    presetStore: HistoryMetricPresetStore? = null, imageLoader: HistoryPreviewImageLoader? = null,
    previewDispatcher: CoroutineDispatcher = Dispatchers.Default) : HistoryComponent {
    private val lifetime = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + lifetime)
    private val catalog = HistoryCatalogComponent(componentContext, scope, repository)
    private val detail = HistoryDetailComponent(componentContext, scope, repository, readTick)
    private val route = HistoryRouteComponent(componentContext, scope, repository)
    private val previews = HistoryPreviewComponent(componentContext, scope, repository, PREVIEW_GPS_SILENCE_MILLIS,previewDispatcher)
    private val presets = HistoryPresetsComponent(componentContext, scope, presetStore)
    private val previewImages = imageLoader?.let { HistoryPreviewImagesComponent(componentContext,scope,it) }
    private val mutableState = MutableStateFlow(HistoryUiState())
    override val state = mutableState.asStateFlow()
    private var selectedRideId: String? = null
    private var selection = Any()
    private var previewRevision = 0L
    private var visiblePreviewIds = emptyList<String>()
    private var catalogIntent = Any()
    private var windowIntent = Any()
    private var windowGesture: WindowGesture? = null
    private class WindowGesture(val token: HistoryWindowGestureToken, val selection: Any, val intent: Any,
        val rideId: String, val base: HistoryRange, val bounds: HistoryRange)

    init {
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
        val captured = selection
        val ride = next.ride; val range = next.range
        val needsRoute = ride != null && range != null && (route.state.value.rideId != ride.id || route.state.value.range != range)
        val current = mutableState.value
        mutableState.value = current.copy(detail = next, route = if (needsRoute)
            HistoryRouteState(rideId = ride?.id, range = range, loading = true) else current.route)
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

    override fun openRide(id: String) {
        if (!lifetime.isActive) return
        require(id.isNotBlank())
        selectedRideId = id; selection = Any()
        windowIntent = Any(); windowGesture = null
        val captured = selection
        mutableState.value = mutableState.value.copy(destination = HistoryDestination.RIDE, selectedRideId = id,
            detail = HistoryDetailState(rideId = id, loading = true), route = HistoryRouteState(), windowPreview = null)
        if (!lifetime.isActive || selection !== captured) return
        route.clear()
        if (lifetime.isActive && selection === captured) detail.openRide(id)
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
        when (mutableState.value.destination) {
            HistoryDestination.CHARTS -> {
                windowIntent = Any(); windowGesture = null
                mutableState.value = mutableState.value.copy(destination = HistoryDestination.RIDE, windowPreview = null)
            }
            HistoryDestination.CATALOG -> onBack()
            HistoryDestination.RIDE -> {
                selectedRideId = null; selection = Any()
                windowIntent = Any(); windowGesture = null
                val captured = selection
                mutableState.value = mutableState.value.copy(destination = HistoryDestination.CATALOG, selectedRideId = null,
                    detail = HistoryDetailState(), route = HistoryRouteState(), windowPreview = null)
                if (lifetime.isActive && selection === captured) { detail.clear(); route.clear() }
            }
        }
    }

    fun close() {
        selection = Any(); catalogIntent = Any(); selectedRideId = null
        windowIntent = Any(); windowGesture = null
        catalog.close(); detail.close(); route.close(); previews.close(); presets.close(); previewImages?.close(); lifetime.cancel()
    }
}

// Display-only silence bound; explicit recorded GPS/continuity markers always veto a bridge.
private const val PREVIEW_GPS_SILENCE_MILLIS = 5_000L
