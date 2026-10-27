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
import ru.sodovaya.volty.domain.plausibility.historyRobustPeak

/** What a ride-list card shows besides the route: the peak PWM, or null when the ride has none worth showing. */
data class HistoryCardStats(val maxDuty: HistoryTotal? = null)

data class HistoryCardStatsState(
    val stats: Map<String, HistoryCardStats> = emptyMap(),
    val loading: Set<String> = emptySet(),
    val errors: Set<String> = emptySet(),
)

/**
 * The peak PWM of the visible ride cards. A sibling of [HistoryPreviewComponent] with the same contract: the caller
 * hands it the visible rides and a catalog revision, one read per visible ride (never a preload), a bounded cache keyed
 * by (ride, revision), a failure that only an explicit [retry] restarts, and a lifetime tied to the parent's.
 *
 * Reads are cheap by design: the bucket stats maximum of each duty series ([readCardMaxDuty]). A card is not worth a
 * full detail walk, so a needle check runs only when the whole series fits in ONE detail page; otherwise the stats
 * maximum is shown with the approximation sign. A ride that is still recording is read from stats only.
 */
class HistoryCardStatsComponent(componentContext: ComponentContext, parentScope: CoroutineScope,
    private val repository: HistoryReadRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
    maxConcurrentReads: Int = 2, private val cacheSize: Int = 32) {
    private val lifetime = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + lifetime)
    private val permits = Semaphore(maxConcurrentReads)
    private val mutableState = MutableStateFlow(HistoryCardStatsState())
    val state: StateFlow<HistoryCardStatsState> = mutableState.asStateFlow()
    private var visible = emptyMap<String, HistoryRide>()
    private var revision = 0L
    private var transition = Any()
    private val requests = mutableMapOf<String, Request>()
    private val cache = linkedMapOf<Key, HistoryCardStats>()

    init {
        require(maxConcurrentReads in 1..2 && cacheSize in 1..64)
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
        val data = linkedMapOf<String, HistoryCardStats>(); val loading = mutableSetOf<String>(); val errors = mutableSetOf<String>()
        for (ride in visible.values) {
            val key = Key(ride, revision)
            val unchanged = sameRevision && previousModels[ride.id] == ride
            val cached = cached(key) ?: previous.stats[ride.id]?.takeIf { unchanged }
            when {
                cached != null -> data[ride.id] = cached
                unchanged && ride.id in previous.errors -> errors += ride.id
                ride.status == HistoryRideStatus.LEGACY -> { val none = HistoryCardStats(); put(key, none); data[ride.id] = none }
                else -> {
                    if (requests[ride.id]?.key != key) prepare(key)
                    loading += ride.id
                }
            }
        }
        if (!lifetime.isActive || transition !== captured) return
        mutableState.value = HistoryCardStatsState(data.toMap(), loading.toSet(), errors.toSet())
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
                    withContext(ioDispatcher) { HistoryCardStats(readCardMaxDuty(repository, key.ride)) }
                }
                currentCoroutineContext().ensureActive()
                if (current(request)) {
                    put(key, result)
                    mutableState.value = mutableState.value.copy(stats = mutableState.value.stats + (key.ride.id to result),
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

    private fun cached(key: Key): HistoryCardStats? = cache.remove(key)?.also { cache[key] = it }
    private fun put(key: Key, data: HistoryCardStats) {
        cache.remove(key); cache[key] = data
        while (cache.size > cacheSize) cache.remove(cache.keys.first())
    }

    fun close() {
        transition = Any(); lifetime.cancel()
        requests.values.toList().forEach(::retire); cache.clear(); visible = emptyMap()
    }

    private data class Key(val ride: HistoryRide, val revision: Long)
    private class Request(val key: Key) { lateinit var job: Job }
}

/**
 * The peak PWM tile of one ride card, from bucket stats and at most ONE detail page per duty series.
 *
 * The stats maximum is the value; when the ride is finished and a series fits in one page ([readHistoryDetail] with a
 * budget of 1) the needle-free peak ([historyRobustPeak]) replaces it, otherwise the stats maximum is shown marked
 * approximate (a needle could not be ruled out). A reader that cannot page detail leaves the stats maximum as it is, the
 * same fallback the totals use. A ride that is still recording is read from stats only. The two duty series are combined
 * by [historyPeakDuty], so a tilde appears only when the estimate won. A failed read throws: the caller keeps it as a
 * retryable error rather than a missing line.
 */
internal suspend fun readCardMaxDuty(repository: HistoryReadRepository, ride: HistoryRide): HistoryTotal? {
    val open = ride.status == HistoryRideStatus.RECORDING || ride.ended == null
    val end = if (open) Long.MAX_VALUE else checkNotNull(historyAxisEpochMillis(ride.started, checkNotNull(ride.ended)))
    val range = HistoryRange(ride.started.epochMillis, end)
    val motion = tier(repository.sources(ride.id), motionTiers)
    suspend fun candidates(metricId: String, sources: List<HistorySource>): List<HistoryTotal> = sources.mapNotNull { source ->
        val key = HistorySeriesKey(source.id, metricId)
        val maximum = historyMetricSummary(key, repository.stats(ride.id, key, range)).maximum
        val value = maximum.value ?: return@mapNotNull null
        var peak = value; var approximate = maximum.quality != SummaryQuality.EXACT
        if (!open) {
            val read = readHistoryDetail(repository, ride.id, key, range, maxPages = 1)
            when {
                read == null -> Unit
                !read.complete -> approximate = true
                else -> historyRobustPeak(read.samples)?.let { peak = it.value; approximate = it.approximate }
            }
        }
        HistoryTotal(HistoryTotalKind.MAX_DUTY_PERCENT, peak, approximate)
    }
    val peak = historyPeakDuty(candidates("duty_percent", motion), candidates("duty_estimated_percent", motion)) ?: return null
    return historyVisibleTotals(listOf(peak)).singleOrNull()
}
