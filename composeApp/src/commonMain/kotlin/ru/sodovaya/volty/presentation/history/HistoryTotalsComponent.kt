package ru.sodovaya.volty.presentation.history

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.sodovaya.volty.domain.history.*

data class HistoryTotalsState(val rideId: String? = null, val totals: List<HistoryTotal> = emptyList(), val loading: Boolean = false)

/** Whole-ride headline numbers, independent of the selected metrics and window.
 * A growing recording reloads at most every [refreshMillis]; a failed stat only drops its tiles.
 */
class HistoryTotalsComponent(componentContext: ComponentContext, parentScope: CoroutineScope,
    private val repository: HistoryReadRepository, private val refreshMillis: Long = 15_000,
    private val maxGpsPages: Int = 16) {
    private val lifetime = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + lifetime)
    private val mutableState = MutableStateFlow(HistoryTotalsState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    private var requested: Triple<String, List<HistorySource>, HistoryRange>? = null

    init { componentContext.lifecycle.doOnDestroy { close() } }

    fun open(rideId: String, sources: List<HistorySource>, range: HistoryRange) {
        if (!lifetime.isActive) return
        val next = Triple(rideId, sources, range)
        if (next == requested) return
        val sameRide = requested?.first == rideId && mutableState.value.rideId == rideId
        requested = next
        job?.cancel()
        if (!sameRide) mutableState.value = HistoryTotalsState(rideId = rideId, loading = true)
        job = scope.launch {
            // The same ride growing while recorded: keep the shown tiles and coalesce the updates.
            if (sameRide) delay(refreshMillis)
            val totals = readHistoryTotals(repository, rideId, sources, range, maxGpsPages)
            if (requested == next) mutableState.value = HistoryTotalsState(rideId = rideId, totals = totals)
        }
    }

    fun clear() { job?.cancel(); requested = null; mutableState.value = HistoryTotalsState() }
    fun close() { job?.cancel(); lifetime.cancel() }
}

/** Whole-ride totals read from storage; a failed stat only drops its tiles, a capped GPS read gives no distance. */
internal suspend fun readHistoryTotals(repository: HistoryReadRepository, rideId: String, sources: List<HistorySource>,
    range: HistoryRange, maxGpsPages: Int = 16): List<HistoryTotal> {
    val stats = historyTotalsKeys(sources).mapNotNull { key ->
        try { key to repository.stats(rideId, key, range) }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { null }
    }.toMap()
    val duration = range.endEpochMillis - range.startEpochMillis
    val counted = historyRideTotals(sources, stats, duration, null)
    if (counted.any { it.kind == HistoryTotalKind.DISTANCE_KM }) return historyVisibleTotals(counted)
    val gps = try {
        val fixes = readHistoryRouteFixes(repository, rideId, range, maxGpsPages)
        fixes?.let(::historyGpsDistanceKm)
    } catch (cancel: CancellationException) { throw cancel }
    catch (_: Exception) { null }
    return historyVisibleTotals(historyRideTotals(sources, stats, duration, gps))
}

/** Every recorded fix of a range, or null when the page budget ran out first (a partial track would mislead). */
internal suspend fun readHistoryRouteFixes(repository: HistoryReadRepository, rideId: String, range: HistoryRange,
    maxPages: Int): List<HistoryGpsFix>? {
    val fixes = mutableListOf<HistoryGpsFix>()
    var after: HistoryCursor? = null
    repeat(maxPages) {
        val page = repository.route(rideId, range, after)
        fixes += page.fixes.items
        after = page.fixes.next ?: return fixes
    }
    return null
}
