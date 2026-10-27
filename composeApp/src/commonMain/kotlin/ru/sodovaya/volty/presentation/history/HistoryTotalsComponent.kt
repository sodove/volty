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
    private val maxGpsPages: Int = 16, private val maxDetailPages: Int = 16) {
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
            val totals = readHistoryTotals(repository, rideId, sources, range, maxGpsPages, maxDetailPages)
            if (requested == next) mutableState.value = HistoryTotalsState(rideId = rideId, totals = totals)
        }
    }

    fun clear() { job?.cancel(); requested = null; mutableState.value = HistoryTotalsState() }
    fun close() { job?.cancel(); lifetime.cancel() }
}

/**
 * Whole-ride totals read from storage; a failed stat only drops its tiles.
 *
 * The distance is the part that must be earned. The GPS track is always read; the counter (trip, else odometer, of the
 * motion tier) is paged as detail samples through at most [maxDetailPages] pages and walked by [historyRideDistance].
 * When the detail cannot be paged (a reader that returns null, a ride missing from storage, a page budget that ran out)
 * the stats path [historyStatsDistance] decides, cross-checked against the ride's top speed all the same. A budget that
 * ran out makes that distance approximate; a capped GPS read gives no GPS at all (a partial track would mislead).
 */
internal suspend fun readHistoryTotals(repository: HistoryReadRepository, rideId: String, sources: List<HistorySource>,
    range: HistoryRange, maxGpsPages: Int = 16, maxDetailPages: Int = 16): List<HistoryTotal> {
    val stats = historyTotalsKeys(sources).mapNotNull { key ->
        try { key to repository.stats(rideId, key, range) }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { null }
    }.toMap()
    val duration = range.endEpochMillis - range.startEpochMillis
    val route = try { readHistoryRoute(repository, rideId, range, maxGpsPages) }
    catch (cancel: CancellationException) { throw cancel }
    catch (_: Exception) { null }
    val peak = historyPeakSpeedKmh(sources, stats)

    var budgetRanOut = false
    var walked: HistoryRideWalk? = null
    try {
        val origin = repository.ride(rideId)?.started
        if (origin != null) {
            // Trip and odometer of the motion tier, both gated. A series the cross-check judged unearned loses to any that
            // was not; among the rest the larger accepted sum wins (a trip that restarted and rose a few metres must not
            // beat a healthy odometer).
            val walks = ArrayList<HistoryRideWalk>()
            search@ for (metric in listOf("trip_distance_km", "odometer_km")) {
                for (source in tier(sources, motionTiers)) {
                    val read = readHistoryDetail(repository, rideId, HistorySeriesKey(source.id, metric), range, maxDetailPages)
                        ?: break@search // this reader cannot page detail: the stats path decides
                    if (!read.complete) { budgetRanOut = true; break@search }
                    if (read.samples.mapNotNull { it.value }.distinct().size < 2) continue
                    walks += historyRideWalk(read.samples, route?.fixes.orEmpty(), origin, peak, duration, route?.markers.orEmpty())
                }
            }
            if (!budgetRanOut) {
                walked = walks.filter { !it.crossChecked && it.acceptedM > 0.0 }.maxByOrNull { it.acceptedM }
                    ?: walks.firstOrNull { it.crossChecked }
            }
        }
    } catch (cancel: CancellationException) { throw cancel }
    catch (_: Exception) { walked = null }

    // A counter the cross-check judged unearned is final: GPS alone or no tile, never the stats' max - min.
    val distance = if (walked != null) walked.distance
    else historyStatsDistance(sources, stats, duration, route?.fixes?.let(::historyGpsDistanceKm))
        ?.let { if (budgetRanOut) it.copy(approximate = true) else it }
    val moving = try { readMovingTime(repository, rideId, sources, range, maxDetailPages) }
    catch (cancel: CancellationException) { throw cancel }
    catch (_: Exception) { null }
    val batteryUsed = try { readBatteryUsed(repository, rideId, sources, range, maxDetailPages) }
    catch (cancel: CancellationException) { throw cancel }
    catch (_: Exception) { null }
    return historyVisibleTotals(historyRideTotals(sources, stats, duration, distance, moving, batteryUsed))
}

/**
 * Charge used per battery source of the battery tier from paged `soc_percent` detail ([historyBatteryUsed]), through the
 * same pager and budget as the counter. The current to judge calm stretches by is the source's own `current_a`, else the
 * motion tier's `battery_current_a` (a T4's pack current is unknown), the latter never an own-current. A source whose
 * detail cannot be paged, ran out of budget, or has no known charge sample contributes nothing and makes the total
 * approximate; no source at all gives no tile.
 */
private suspend fun readBatteryUsed(repository: HistoryReadRepository, rideId: String, sources: List<HistorySource>,
    range: HistoryRange, maxPages: Int): HistoryBatteryUsed? {
    val parts = ArrayList<HistoryBatteryUsed>()
    var skipped = false
    for (source in tier(sources, batteryTiers)) {
        val soc = readHistoryDetail(repository, rideId, HistorySeriesKey(source.id, "soc_percent"), range, maxPages)
        if (soc == null || !soc.complete) { skipped = true; continue }
        if (soc.samples.none { it.value != null }) continue
        var current = readHistoryDetail(repository, rideId, HistorySeriesKey(source.id, "current_a"), range, maxPages)
            ?.takeIf { it.complete && it.samples.any { s -> s.value != null } }
        val own = current != null
        if (current == null) for (motion in tier(sources, motionTiers)) {
            current = readHistoryDetail(repository, rideId, HistorySeriesKey(motion.id, "battery_current_a"), range, maxPages)
                ?.takeIf { it.complete && it.samples.any { s -> s.value != null } }
            if (current != null) break
        }
        historyBatteryUsed(soc.samples, current?.samples, own)?.let(parts::add)
    }
    return historyBatteryUsedTotal(parts)?.let { if (skipped) it.copy(approximate = true) else it }
}

/**
 * Moving time from the motion tier's paged `speed_kmh` detail, through the same pager and budget as the counter. Null (the
 * average then falls back to wall duration, marked approximate) when the reader cannot page detail, the budget ran out
 * (a prefix is not the ride) or no source has a known speed sample.
 */
private suspend fun readMovingTime(repository: HistoryReadRepository, rideId: String, sources: List<HistorySource>,
    range: HistoryRange, maxPages: Int): HistoryMovingTime? {
    for (source in tier(sources, motionTiers)) {
        val read = readHistoryDetail(repository, rideId, HistorySeriesKey(source.id, "speed_kmh"), range, maxPages) ?: return null
        if (!read.complete) return null
        if (read.samples.any { it.value != null }) return historyMovingMillis(read.samples)
    }
    return null
}

/**
 * Pages one detail series through [HistoryReadRepository.samples] with a page budget, so another series (speed, charge,
 * current) can reuse it. Null when the reader cannot page detail; [HistoryDetailRead.complete] is false when the budget
 * ran out before the series ended (the samples read so far are a prefix, not the ride).
 */
internal suspend fun readHistoryDetail(repository: HistoryReadRepository, rideId: String, key: HistorySeriesKey,
    range: HistoryRange, maxPages: Int): HistoryDetailRead? {
    val samples = ArrayList<HistorySample>()
    var after: HistoryCursor? = null
    repeat(maxPages) {
        val page = repository.samples(rideId, key, range, after, HISTORY_DETAIL_PAGE_SIZE) ?: return null
        samples += page.items
        after = page.next ?: return HistoryDetailRead(samples, true)
    }
    return HistoryDetailRead(samples, false)
}

internal class HistoryDetailRead(val samples: List<HistorySample>, val complete: Boolean)

/** The whole recorded track and its markers of a range. */
internal class HistoryRouteRead(val fixes: List<HistoryGpsFix>, val markers: List<HistoryMarker>)

/** Every recorded fix and marker of a range, or null when the page budget ran out first (a partial track would mislead). */
internal suspend fun readHistoryRoute(repository: HistoryReadRepository, rideId: String, range: HistoryRange,
    maxPages: Int): HistoryRouteRead? {
    val fixes = mutableListOf<HistoryGpsFix>()
    val markers = LinkedHashSet<HistoryMarker>()
    var after: HistoryCursor? = null
    repeat(maxPages) {
        val page = repository.route(rideId, range, after)
        fixes += page.fixes.items
        markers += page.markers
        after = page.fixes.next ?: return HistoryRouteRead(fixes, markers.toList())
    }
    return null
}

/** Every recorded fix of a range, or null when the page budget ran out first (a partial track would mislead). */
internal suspend fun readHistoryRouteFixes(repository: HistoryReadRepository, rideId: String, range: HistoryRange,
    maxPages: Int): List<HistoryGpsFix>? = readHistoryRoute(repository, rideId, range, maxPages)?.fixes
