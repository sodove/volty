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
            val totals = read(rideId, sources, range)
            if (requested == next) mutableState.value = HistoryTotalsState(rideId = rideId, totals = totals)
        }
    }

    private suspend fun read(rideId: String, sources: List<HistorySource>, range: HistoryRange): List<HistoryTotal> {
        val stats = historyTotalsKeys(sources).mapNotNull { key ->
            try { key to repository.stats(rideId, key, range) }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { null }
        }.toMap()
        val duration = range.endEpochMillis - range.startEpochMillis
        val counted = historyRideTotals(sources, stats, duration, null)
        if (counted.any { it.kind == HistoryTotalKind.DISTANCE_KM }) return counted
        val gps = try { gpsDistanceKm(rideId, range) }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { null }
        return historyRideTotals(sources, stats, duration, gps)
    }

    private suspend fun gpsDistanceKm(rideId: String, range: HistoryRange): Double? {
        val fixes = mutableListOf<HistoryGpsFix>()
        var after: HistoryCursor? = null
        repeat(maxGpsPages) {
            val page = repository.route(rideId, range, after)
            fixes += page.fixes.items
            after = page.fixes.next ?: return historyGpsDistanceKm(fixes)
        }
        // A capped read would understate the distance: show none rather than a short one.
        return null
    }

    fun clear() { job?.cancel(); requested = null; mutableState.value = HistoryTotalsState() }
    fun close() { job?.cancel(); lifetime.cancel() }
}
