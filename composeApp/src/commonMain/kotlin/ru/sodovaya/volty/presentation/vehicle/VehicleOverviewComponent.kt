package ru.sodovaya.volty.presentation.vehicle

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import ru.sodovaya.volty.domain.history.*
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.repository.VehicleRepository
import ru.sodovaya.volty.presentation.history.*

/** One recorded value with the moment it was measured; never a live reading. */
data class VehicleReading(val metricId: String, val value: Double, val unit: HistoryUnit)
data class VehicleBatterySnapshot(val label: String, val readings: List<VehicleReading>, val atEpochMillis: Long)

data class VehicleOverviewState(
    val vehicle: Vehicle? = null,
    val loading: Boolean = true,
    val rides: List<HistoryRide> = emptyList(),
    val lastRide: HistoryRide? = null,
    val battery: List<VehicleBatterySnapshot> = emptyList(),
    val totals: HistoryTotalsState = HistoryTotalsState(),
    val error: Boolean = false,
)

interface VehicleOverviewComponent {
    val state: StateFlow<VehicleOverviewState>
    fun onConnect()
    fun onOpenRide(rideId: String)
    fun onEdit()
    fun onAlerts()
    fun onBack()
}

internal val vehicleBatteryMetrics = listOf("soc_percent", "voltage_v", "cell_min_v", "cell_max_v", "cell_delta_mv", "battery_temperature_c")

/** The newest non-null value of each metric in a series window, signed for display. */
internal fun vehicleBatterySnapshot(label: String, series: List<HistorySeries>): VehicleBatterySnapshot? {
    val latest = series.mapNotNull { s ->
        val descriptor = HistoryMetricRegistry.descriptor(s.key.metricId) ?: return@mapNotNull null
        val sample = s.samples.lastOrNull { it.value != null && it.value.isFinite() } ?: return@mapNotNull null
        Triple(VehicleReading(s.key.metricId, sample.value!! * descriptor.displayMultiplier, descriptor.unit), sample.tick.epochMillis, s.key.metricId)
    }
    if (latest.isEmpty()) return null
    val ordered = latest.sortedBy { vehicleBatteryMetrics.indexOf(it.third) }
    return VehicleBatterySnapshot(label, ordered.map { it.first }, ordered.maxOf { it.second })
}

/**
 * A saved vehicle seen without connecting: its last known battery state, the totals of its last
 * ride and its rides, all from recorded history. Reachable while the vehicle is away.
 */
class DefaultVehicleOverviewComponent(
    componentContext: ComponentContext,
    parentScope: CoroutineScope,
    private val vehicleId: String,
    private val vehicles: VehicleRepository,
    private val history: HistoryReadRepository,
    private val readTick: () -> HistoryTick,
    private val onConnectRequested: (String) -> Unit,
    private val onOpenRideRequested: (String) -> Unit,
    private val onEditRequested: (String) -> Unit,
    private val onAlertsRequested: (String) -> Unit,
    private val onBackRequested: () -> Unit,
    private val maxRides: Int = 10,
    private val maxCatalogPages: Int = 20,
) : VehicleOverviewComponent, ComponentContext by componentContext {
    private val lifetime = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + lifetime)
    private val totals = HistoryTotalsComponent(componentContext, scope, history)
    private val mutableState = MutableStateFlow(VehicleOverviewState())
    override val state: StateFlow<VehicleOverviewState> = mutableState.asStateFlow()

    init {
        lifecycle.doOnDestroy { lifetime.cancel() }
        scope.launch { totals.state.collect { next -> mutableState.update { it.copy(totals = next) } } }
        scope.launch { vehicles.vehicles.collect { list -> mutableState.update { it.copy(vehicle = list.firstOrNull { v -> v.id == vehicleId }) } } }
        scope.launch { load() }
    }

    private suspend fun load() {
        try {
            val rides = ridesOfVehicle()
            val last = rides.firstOrNull()
            mutableState.update { it.copy(rides = rides, lastRide = last) }
            if (last != null) {
                val sources = history.sources(last.id)
                val range = historyRecordedRange(history, last, readTick)
                totals.open(last.id, sources, range)
                mutableState.update { it.copy(battery = lastBattery(last.id, sources, range)) }
            }
            mutableState.update { it.copy(loading = false) }
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { mutableState.update { it.copy(loading = false, error = true) } }
    }

    /** The catalog is shared by all vehicles, newest first; stop at enough rides or a page budget. */
    private suspend fun ridesOfVehicle(): List<HistoryRide> {
        val found = mutableListOf<HistoryRide>()
        var before: HistoryCursor? = null
        repeat(maxCatalogPages) {
            val page = history.rides(before)
            found += page.items.filter { it.vehicleId == vehicleId }
            if (found.size >= maxRides) return found.take(maxRides)
            before = page.next ?: return found
        }
        return found
    }

    private suspend fun lastBattery(rideId: String, sources: List<HistorySource>, range: HistoryRange): List<VehicleBatterySnapshot> {
        val tail = HistoryRange(maxOf(range.startEpochMillis, range.endEpochMillis - LAST_STATE_WINDOW_MILLIS), range.endEpochMillis)
        return tier(sources, batteryTiers).mapNotNull { source ->
            val series = vehicleBatteryMetrics.mapNotNull { metric ->
                try { history.series(rideId, HistorySeriesKey(source.id, metric), tail, LAST_STATE_POINTS) }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { null }
            }
            vehicleBatterySnapshot(source.label, series)
        }
    }

    override fun onConnect() = onConnectRequested(vehicleId)
    override fun onOpenRide(rideId: String) = onOpenRideRequested(rideId)
    override fun onEdit() = onEditRequested(vehicleId)
    override fun onAlerts() = onAlertsRequested(vehicleId)
    override fun onBack() = onBackRequested()

    private companion object {
        const val LAST_STATE_WINDOW_MILLIS = 10 * 60_000L
        const val LAST_STATE_POINTS = 60
    }
}
