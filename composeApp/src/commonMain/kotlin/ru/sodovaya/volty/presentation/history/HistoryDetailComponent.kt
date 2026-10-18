package ru.sodovaya.volty.presentation.history

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.sodovaya.volty.domain.history.*

enum class HistoryReadError { NOT_FOUND, STORAGE }

data class HistoryDetailState(
    val rideId: String? = null,
    val ride: HistoryRide? = null,
    val sources: List<HistorySource> = emptyList(),
    val range: HistoryRange? = null,
    val cursorEpochMillis: Long? = null,
    val selectedKeys: List<HistorySeriesKey> = emptyList(),
    val series: Map<HistorySeriesKey, HistorySeries> = emptyMap(),
    val stats: Map<HistorySeriesKey, HistoryStats> = emptyMap(),
    val loadingKeys: Set<HistorySeriesKey> = emptySet(),
    val metricErrors: Map<HistorySeriesKey, HistoryReadError> = emptyMap(),
    val recordedKeys: Map<String, HistoryRecordedKeys> = emptyMap(),
    val loadingSources: Set<String> = emptySet(),
    val sourceErrors: Map<String, HistoryReadError> = emptyMap(),
    val loading: Boolean = false,
    val error: HistoryReadError? = null,
)

/** Read-only detail ownership; commands run on the parent scope's UI dispatcher.
 * No catalog, route or numeric series is preloaded until explicitly requested.
 */
class HistoryDetailComponent(
    componentContext: ComponentContext,
    parentScope: CoroutineScope,
    private val repository: HistoryReadRepository,
    private val readTick: () -> HistoryTick,
) {
    private val lifetime = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + lifetime)
    private val mutableState = MutableStateFlow(HistoryDetailState())
    val state: StateFlow<HistoryDetailState> = mutableState.asStateFlow()
    private var generation = Any()
    private var detailJob: Job? = null
    private val metricJobs = mutableMapOf<HistorySeriesKey, Job>()
    private val metricTokens = mutableMapOf<HistorySeriesKey, Any>()
    private val sourceJobs = mutableMapOf<String, Job>()
    private val sourceTokens = mutableMapOf<String, Any>()
    private var availableRange: HistoryRange? = null

    init { componentContext.lifecycle.doOnDestroy { close() } }

    fun openRide(id: String) {
        if (!lifetime.isActive) return
        require(id.isNotBlank())
        retireReads()
        retireSourceReads()
        availableRange = null
        val request = generation
        mutableState.value = HistoryDetailState(rideId = id, loading = true)
        // Publishing can synchronously invoke a UI observer that selects another ride.
        if (!lifetime.isActive || request !== generation) return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val ride = repository.ride(id)
                currentCoroutineContext().ensureActive()
                if (request !== generation) return@launch
                if (ride == null) {
                    mutableState.value = mutableState.value.copy(loading = false, error = HistoryReadError.NOT_FOUND)
                    return@launch
                }
                check(ride.id == id) { "Mismatched recorded ride" }
                val sources = repository.sources(id).toList()
                currentCoroutineContext().ensureActive()
                if (request !== generation) return@launch
                check(sources.map { it.id }.distinct().size == sources.size)
                val end = ride.ended?.let {
                    if (ride.status == HistoryRideStatus.LEGACY) it.epochMillis
                    else checkNotNull(historyAxisEpochMillis(ride.started, it))
                } ?: readTick().let { now ->
                    if (ride.status == HistoryRideStatus.LEGACY) maxOf(ride.started.epochMillis, now.epochMillis)
                    else checkNotNull(historyAxisEpochMillis(ride.started, now))
                }
                val range = HistoryRange(ride.started.epochMillis, end)
                availableRange = range
                mutableState.value = mutableState.value.copy(ride = ride, sources = sources, range = range, loading = false)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                if (isActive && request === generation) {
                    mutableState.value = mutableState.value.copy(loading = false, error = HistoryReadError.STORAGE)
                }
            }
        }
        detailJob = job
        job.start()
    }

    /** Metadata belongs to the ride, so viewport changes do not cancel it. */
    fun loadSourceMetrics(sourceId: String) {
        if (!lifetime.isActive) return
        val current = mutableState.value
        val rideId = current.ride?.id ?: return
        if (sourceId in current.recordedKeys || sourceId in current.loadingSources) return
        val source = current.sources.singleOrNull { it.id == sourceId } ?: return
        val token = Any()
        sourceTokens[sourceId] = token
        mutableState.value = current.copy(loadingSources = current.loadingSources + sourceId,
            sourceErrors = current.sourceErrors - sourceId)
        if (!lifetime.isActive || sourceTokens[sourceId] !== token) return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val keys = repository.recordedKeys(rideId, sourceId)
                currentCoroutineContext().ensureActive()
                if (sourceTokens[sourceId] !== token) return@launch
                check(keys.keys.size <= 20000 && keys.keys.distinct().size == keys.keys.size)
                check(keys.keys.all { key ->
                    val metric = HistoryMetricRegistry.descriptor(key.metricId)
                    key.sourceId == sourceId && metric != null && source.kind in metric.sourceKinds &&
                        metric.indexedByCell == (key.cellIndex != null)
                })
                val latest = mutableState.value
                mutableState.value = latest.copy(recordedKeys = latest.recordedKeys +
                    (sourceId to keys.copy(keys = keys.keys.toList())), loadingSources = latest.loadingSources - sourceId)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                if (isActive && sourceTokens[sourceId] === token) {
                    val latest = mutableState.value
                    mutableState.value = latest.copy(loadingSources = latest.loadingSources - sourceId,
                        sourceErrors = latest.sourceErrors + (sourceId to HistoryReadError.STORAGE))
                }
            } finally {
                if (sourceTokens[sourceId] === token) {
                    sourceTokens.remove(sourceId)
                    sourceJobs.remove(sourceId)
                }
            }
        }
        sourceJobs[sourceId] = job
        job.start()
    }

    fun addMetric(key: HistorySeriesKey) {
        if (!lifetime.isActive) return
        val current = mutableState.value
        if (current.ride == null || key in current.selectedKeys) return
        val source = current.sources.singleOrNull { it.id == key.sourceId } ?: return
        val descriptor = HistoryMetricRegistry.descriptor(key.metricId) ?: return
        if (source.kind !in descriptor.sourceKinds || descriptor.indexedByCell != (key.cellIndex != null)) return
        val request = generation
        mutableState.value = current.copy(selectedKeys = current.selectedKeys + key)
        if (request === generation) readMetric(key)
    }

    fun removeMetric(key: HistorySeriesKey) {
        if (!lifetime.isActive) return
        metricTokens.remove(key)
        metricJobs.remove(key)?.cancel()
        val current = mutableState.value
        mutableState.value = current.copy(selectedKeys = current.selectedKeys - key,
            series = current.series - key, stats = current.stats - key,
            loadingKeys = current.loadingKeys - key, metricErrors = current.metricErrors - key)
    }

    fun moveMetric(key: HistorySeriesKey, index: Int) {
        if (!lifetime.isActive) return
        val current = mutableState.value
        if (key !in current.selectedKeys) return
        mutableState.value = current.copy(selectedKeys = reorderHistoryMetric(current.selectedKeys, key, index))
    }

    fun selectRange(range: HistoryRange) {
        if (!lifetime.isActive) return
        val current = mutableState.value
        val full = availableRange ?: return
        if (range == current.range || range.startEpochMillis < full.startEpochMillis || range.endEpochMillis > full.endEpochMillis) return
        retireReads()
        val request = generation
        mutableState.value = current.copy(range = range, series = emptyMap(), stats = emptyMap(),
            loadingKeys = emptySet(), metricErrors = emptyMap(),
            cursorEpochMillis = current.cursorEpochMillis?.takeIf { it in range.startEpochMillis..range.endEpochMillis })
        if (request === generation) current.selectedKeys.forEach(::readMetric)
    }

    fun selectTime(epochMillis: Long?) {
        if (!lifetime.isActive) return
        val current = mutableState.value
        val range = current.range ?: return
        mutableState.value = current.copy(cursorEpochMillis = epochMillis?.takeIf { it in range.startEpochMillis..range.endEpochMillis })
    }

    private fun readMetric(key: HistorySeriesKey) {
        val current = mutableState.value
        if (!lifetime.isActive || key !in current.selectedKeys || key in metricTokens || key in current.series) return
        val id = current.ride?.id ?: return
        val range = current.range ?: return
        val request = generation
        val token = Any()
        metricTokens[key] = token
        mutableState.value = current.copy(loadingKeys = current.loadingKeys + key, metricErrors = current.metricErrors - key)
        if (!lifetime.isActive || request !== generation || metricTokens[key] !== token) return
        // Install before launch: even an immediate dispatcher must not leave a completed job in the map.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val series = repository.series(id, key, range)
                currentCoroutineContext().ensureActive()
                if (request !== generation || metricTokens[key] !== token) return@launch
                check(series.key == key)
                val stats = repository.stats(id, key, range)
                currentCoroutineContext().ensureActive()
                if (request !== generation || metricTokens[key] !== token) return@launch
                val latest = mutableState.value
                mutableState.value = latest.copy(series = latest.series + (key to series.copy(
                    samples = series.samples.toList(), markers = series.markers.toList())),
                    stats = latest.stats + (key to stats), loadingKeys = latest.loadingKeys - key)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                if (isActive && request === generation && metricTokens[key] === token) {
                    val latest = mutableState.value
                    mutableState.value = latest.copy(loadingKeys = latest.loadingKeys - key,
                        metricErrors = latest.metricErrors + (key to HistoryReadError.STORAGE))
                }
            } finally {
                if (metricTokens[key] === token) {
                    metricTokens.remove(key)
                    metricJobs.remove(key)
                }
            }
        }
        metricJobs[key] = job
        job.start()
    }

    private fun retireReads() {
        generation = Any()
        detailJob?.cancel()
        detailJob = null
        metricTokens.clear()
        metricJobs.values.forEach { it.cancel() }
        metricJobs.clear()
    }

    private fun retireSourceReads() {
        sourceTokens.clear()
        sourceJobs.values.forEach { it.cancel() }
        sourceJobs.clear()
    }

    fun clear() {
        if (!lifetime.isActive) return
        retireReads()
        retireSourceReads()
        availableRange = null
        mutableState.value = HistoryDetailState()
    }

    fun close() {
        retireReads()
        retireSourceReads()
        lifetime.cancel()
    }
}
