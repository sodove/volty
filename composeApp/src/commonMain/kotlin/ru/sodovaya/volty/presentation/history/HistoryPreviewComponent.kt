package ru.sodovaya.volty.presentation.history

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import ru.sodovaya.volty.domain.history.*

data class HistoryPreviewsState(
    val previews: Map<String, HistoryPreviewData> = emptyMap(),
    val loading: Set<String> = emptySet(),
    val errors: Set<String> = emptySet(),
)

/** Commands/publication share the parent's UI dispatcher; validation/reduction run off it.
 * One bounded atomic GPS page per visible ride, never a per-row numeric preload.
 */
class HistoryPreviewComponent(componentContext: ComponentContext, parentScope: CoroutineScope,
    private val repository: HistoryReadRepository, private val maxFixAgeMillis: Long,
    private val projectionDispatcher: CoroutineDispatcher = Dispatchers.Default,
    maxConcurrentReads: Int = 2, private val cacheSize: Int = 16, private val pointBudget: Int = 128) {
    private val lifetime = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + lifetime)
    private val permits = Semaphore(maxConcurrentReads)
    private val mutableState = MutableStateFlow(HistoryPreviewsState())
    val state: StateFlow<HistoryPreviewsState> = mutableState.asStateFlow()
    private var visible = emptyMap<String, HistoryRide>()
    private var revision = 0L
    private var transition = Any()
    private val requests = mutableMapOf<String, Request>()
    private val cache = linkedMapOf<Key, HistoryPreviewData>()

    init {
        require(maxFixAgeMillis >= 0 && maxConcurrentReads in 1..2 && cacheSize in 1..16 && pointBudget in 2..128)
        componentContext.lifecycle.doOnDestroy { close() }
    }

    fun setVisible(rides: List<HistoryRide>, revision: Long = 0) {
        if (!lifetime.isActive) return
        require(rides.size <= 20 && rides.map { it.id }.distinct().size == rides.size && revision >= 0)
        val captured = Any(); transition = captured
        val sameRevision = revision == this.revision
        val previousModels = visible; val previous = mutableState.value
        visible = rides.associateBy { it.id }; this.revision = revision
        requests.values.toList().filter { !sameRevision || visible[it.key.ride.id] != it.key.ride }.forEach(::retire)
        if (!sameRevision) cache.clear()
        val data = linkedMapOf<String, HistoryPreviewData>(); val loading = mutableSetOf<String>(); val errors = mutableSetOf<String>()
        for (ride in visible.values) {
            val key = Key(ride, revision)
            val unchanged = sameRevision && previousModels[ride.id] == ride
            val cached = cached(key) ?: previous.previews[ride.id]?.takeIf { unchanged }
            when {
                cached != null -> data[ride.id] = cached
                unchanged && ride.id in previous.errors -> errors += ride.id
                ride.status == HistoryRideStatus.LEGACY -> {
                    val empty = HistoryPreviewData(emptyList(), false); put(key, empty); data[ride.id] = empty
                }
                else -> {
                    if (requests[ride.id]?.key != key) prepare(key)
                    loading += ride.id
                }
            }
        }
        if (!lifetime.isActive || transition !== captured) return
        mutableState.value = HistoryPreviewsState(data.toMap(), loading.toSet(), errors.toSet())
        startRequests(captured)
    }

    fun retry(rideId: String) {
        if (!lifetime.isActive || rideId !in mutableState.value.errors) return
        val ride = visible[rideId] ?: return
        val captured = Any(); transition = captured
        requests[rideId]?.let(::retire); prepare(Key(ride, revision))
        mutableState.value = mutableState.value.copy(errors = mutableState.value.errors - rideId,
            loading = mutableState.value.loading + rideId)
        startRequests(captured)
    }

    private fun prepare(key: Key) {
        val request = Request(key)
        request.job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = permits.withPermit {
                    if (!current(request)) return@launch
                    val ride = key.ride
                    val open = ride.status == HistoryRideStatus.RECORDING || ride.ended == null
                    val end = if (open) Long.MAX_VALUE else checkNotNull(historyAxisEpochMillis(ride.started, checkNotNull(ride.ended)))
                    val range = HistoryRange(ride.started.epochMillis, end)
                    val page = repository.route(ride.id, range)
                    currentCoroutineContext().ensureActive()
                    if (!current(request)) return@launch
                    withContext(projectionDispatcher) {
                        validate(page, ride, range)
                        val scene = historyMapScene(HistoryMapIdentity(ride.id, key.revision), ride.started,
                            page.fixes.items, page.markers, null, maxFixAgeMillis)
                        check(page.fixes.items.isEmpty() || scene.segments.isNotEmpty())
                        val reduced = reduceHistoryPreview(scene.segments, pointBudget)
                        reduced.copy(partial = reduced.partial || open || page.fixes.truncated || page.fixes.next != null)
                    }
                }
                currentCoroutineContext().ensureActive()
                if (current(request)) {
                    put(key, result)
                    mutableState.value = mutableState.value.copy(previews = mutableState.value.previews + (key.ride.id to result),
                        loading = mutableState.value.loading - key.ride.id, errors = mutableState.value.errors - key.ride.id)
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) {
                if (isActive && current(request)) mutableState.value = mutableState.value.copy(
                    loading = mutableState.value.loading - key.ride.id, errors = mutableState.value.errors + key.ride.id)
            } finally {
                if (requests[key.ride.id] === request) requests.remove(key.ride.id)
            }
        }
        requests[key.ride.id] = request
    }

    private fun startRequests(captured: Any) {
        // A publication may synchronously replace the viewport. Starting all still
        // current lazy requests also handles a reentrant identical setVisible call.
        for (request in requests.values.toList()) {
            if (!lifetime.isActive || transition !== captured) return
            if (current(request)) request.job.start()
        }
    }

    private fun current(request: Request) = lifetime.isActive && requests[request.key.ride.id] === request &&
        revision == request.key.revision && visible[request.key.ride.id] == request.key.ride

    private fun retire(request: Request) {
        if (requests[request.key.ride.id] === request) requests.remove(request.key.ride.id)
        request.job.cancel()
    }

    private fun cached(key: Key): HistoryPreviewData? = cache.remove(key)?.also { cache[key] = it }
    private fun put(key: Key, data: HistoryPreviewData) {
        cache.remove(key); cache[key] = data
        while (cache.size > cacheSize) cache.remove(cache.keys.first())
    }

    private fun validate(page: HistoryRoutePage, ride: HistoryRide, range: HistoryRange) {
        check(page.origin == ride.started && page.fixes.items.size <= 20000 && page.markers.size <= 20000)
        var previous: Long? = null
        for (fix in page.fixes.items) {
            val axis = checkNotNull(historyAxisEpochMillis(ride.started, fix.tick))
            check(axis in range.startEpochMillis..range.endEpochMillis && (previous == null || axis >= checkNotNull(previous)))
            previous = axis
        }
        for (marker in page.markers) {
            val axis = checkNotNull(historyAxisEpochMillis(ride.started, marker.tick))
            check(axis in range.startEpochMillis..range.endEpochMillis)
        }
        page.fixes.next?.let { cursor ->
            check(previous != null && cursor.epochMillis == previous)
            check(cursor.stableId.toLongOrNull()?.let { it >= 0 } == true)
        }
    }

    fun close() {
        transition = Any(); lifetime.cancel()
        requests.values.toList().forEach(::retire); cache.clear(); visible = emptyMap()
    }

    private data class Key(val ride: HistoryRide, val revision: Long)
    private class Request(val key: Key) { lateinit var job: Job }
}
