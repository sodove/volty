package ru.sodovaya.volty.presentation.history

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.sodovaya.volty.domain.history.*

data class HistoryRouteState(
    val rideId: String? = null, val range: HistoryRange? = null,
    val fixes: List<HistoryGpsFix> = emptyList(), val markers: List<HistoryMarker> = emptyList(),
    val nextCursor: HistoryCursor? = null, val loaded: Boolean = false, val loading: Boolean = false,
    val truncated: Boolean = false, val capped: Boolean = false, val error: HistoryReadError? = null,
) { val noRoute: Boolean get() = loaded && !truncated && fixes.isEmpty() && nextCursor == null && error == null }

/** Selected recorded route only. Explicit paging has a bounded retention budget;
 * narrowing the viewport starts a new budget rather than inventing a full route.
 * Commands share the parent's UI dispatcher. No live location is consulted.
 */
class HistoryRouteComponent(componentContext: ComponentContext, parentScope: CoroutineScope,
    private val repository: HistoryReadRepository, private val maxPages: Int = 8) {
    private val lifetime = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + lifetime)
    private val mutableState = MutableStateFlow(HistoryRouteState())
    val state = mutableState.asStateFlow()
    private var selected: HistoryRide? = null
    private var selection = Any()
    private var request: Any? = null
    private var job: Job? = null
    private var pages = 0

    init {
        require(maxPages in 1..64)
        componentContext.lifecycle.doOnDestroy { close() }
    }

    fun open(ride: HistoryRide, range: HistoryRange) {
        if (!lifetime.isActive) return
        val legacy = ride.status == HistoryRideStatus.LEGACY
        // Unclosed legacy bounds come from captured wall timestamps, which may
        // precede metadata after a clock rollback. They never imply GPS exists.
        if (!(legacy && ride.ended == null)) require(range.startEpochMillis >= ride.started.epochMillis)
        ride.ended?.let { ended ->
            val end = if (ride.status == HistoryRideStatus.LEGACY) ended.epochMillis else checkNotNull(historyAxisEpochMillis(ride.started, ended))
            require(range.endEpochMillis <= end)
        }
        retire()
        selected = ride; pages = 0
        val captured = selection
        mutableState.value = HistoryRouteState(rideId = ride.id, range = range, loaded = legacy, loading = !legacy)
        if (!legacy && lifetime.isActive && selection === captured) readPage(null)
    }

    fun loadMore() {
        val current = mutableState.value
        if (!lifetime.isActive || !current.loaded || current.loading || current.capped) return
        val after = current.nextCursor ?: return
        readPage(after)
    }

    fun retry() {
        if (!lifetime.isActive || mutableState.value.loading || mutableState.value.error == null) return
        if (mutableState.value.loaded) loadMore() else readPage(null)
    }

    private fun readPage(after: HistoryCursor?) {
        val ride = selected ?: return
        val range = mutableState.value.range ?: return
        val captured = selection
        val token = Any(); request = token
        job?.cancel(); job = null
        mutableState.value = mutableState.value.copy(loading = true, error = null)
        if (!lifetime.isActive || selection !== captured || request !== token) return
        val next = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val snapshot = repository.route(ride.id, range, after)
                currentCoroutineContext().ensureActive()
                if (selection !== captured || request !== token) return@launch
                if (snapshot.origin == null) {
                    mutableState.value = mutableState.value.copy(loading = false, error = HistoryReadError.NOT_FOUND)
                    return@launch
                }
                validate(snapshot, ride, range, after)
                pages++
                val current = mutableState.value
                val fixes = (if (after == null) emptyList() else current.fixes) + snapshot.fixes.items
                val markers = ((if (after == null) emptyList() else current.markers) + snapshot.markers).distinct()
                    .sortedBy { checkNotNull(historyAxisEpochMillis(ride.started, it.tick)) }
                val cursor = snapshot.fixes.next
                mutableState.value = current.copy(fixes = fixes, markers = markers, nextCursor = cursor,
                    loaded = true, loading = false, truncated = snapshot.fixes.truncated || cursor != null,
                    capped = pages >= maxPages && cursor != null, error = null)
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) {
                if (isActive && selection === captured && request === token) mutableState.value = mutableState.value.copy(
                    loading = false, error = HistoryReadError.STORAGE)
            } finally { if (request === token) { request = null; job = null } }
        }
        job = next; next.start()
    }

    private fun validate(snapshot: HistoryRoutePage, ride: HistoryRide, range: HistoryRange, after: HistoryCursor?) {
        check(snapshot.origin == ride.started)
        check(snapshot.fixes.items.size <= 20000 && snapshot.markers.size <= 20000)
        var previous: Long? = after?.epochMillis
        snapshot.fixes.items.forEach { fix ->
            val axis = checkNotNull(historyAxisEpochMillis(ride.started, fix.tick))
            check(axis in range.startEpochMillis..range.endEpochMillis && (previous == null || axis >= checkNotNull(previous)))
            previous = axis
        }
        snapshot.markers.forEach { marker ->
            val axis = checkNotNull(historyAxisEpochMillis(ride.started, marker.tick))
            check(axis in range.startEpochMillis..range.endEpochMillis)
        }
        snapshot.fixes.next?.let { next ->
            val sequence = checkNotNull(next.stableId.toLongOrNull()); check(sequence >= 0)
            check(snapshot.fixes.items.isNotEmpty() && next.epochMillis == previous)
            if (after != null) check(next.epochMillis > after.epochMillis ||
                (next.epochMillis == after.epochMillis && sequence > checkNotNull(after.stableId.toLongOrNull())))
        }
    }

    fun clear() {
        if (!lifetime.isActive) return
        retire(); selected = null; pages = 0
        mutableState.value = HistoryRouteState()
    }

    private fun retire() {
        selection = Any(); request = null
        job?.cancel(); job = null
    }

    fun close() { retire(); lifetime.cancel() }
}
