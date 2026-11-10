package ru.sodovaya.volty.presentation.vehicle

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import ru.sodovaya.volty.domain.history.*
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.repository.VehicleRepository
import ru.sodovaya.volty.presentation.history.HistoryTotal
import ru.sodovaya.volty.util.formatFixed

/** A shared charge is available only when every branch displays the same whole percent. */
internal fun sharedBatteryCharge(branches: List<VehicleBatterySnapshot>): Double? {
    val charge = branches.firstOrNull()?.value("soc_percent") ?: return null
    if (branches.size == 1) return charge
    val displayedCharge = formatFixed(charge.toFloat(), 0)
    return displayedCharge.toDouble().takeIf {
        branches.drop(1).all { branch ->
            branch.value("soc_percent")?.let { formatFixed(it.toFloat(), 0) } == displayedCharge
        }
    }
}

data class VehicleOverviewSeed(val vehicle: Vehicle, val glance: VehicleGlance?)

data class VehicleOverviewState(
    val vehicle: Vehicle? = null,
    val loading: Boolean = true,
    val rides: List<HistoryRide> = emptyList(),
    val lastRide: HistoryRide? = null,
    val battery: List<VehicleBatterySnapshot> = emptyList(),
    val lastTotals: List<HistoryTotal> = emptyList(),
    val lastRoute: List<Pair<Double, Double>> = emptyList(),
    /** The last ride's totals arrive with the overview, then older totals arrive together. */
    val rideTotals: Map<String, List<HistoryTotal>> = emptyMap(),
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

/**
 * A saved vehicle seen without connecting: its last known battery state, the totals and route of its
 * last ride and its recent rides, all from recorded history. Reachable while the vehicle is away.
 */
class DefaultVehicleOverviewComponent(
    componentContext: ComponentContext,
    parentScope: CoroutineScope,
    private val vehicleId: String,
    private val vehicles: VehicleRepository,
    private val reader: VehicleHistoryReader,
    private val onConnectRequested: (String) -> Unit,
    private val onOpenRideRequested: (String) -> Unit,
    private val onEditRequested: (String) -> Unit,
    private val onAlertsRequested: (String) -> Unit,
    private val onBackRequested: () -> Unit,
    private val maxRides: Int = 10,
    seed: VehicleOverviewSeed? = null,
) : VehicleOverviewComponent, ComponentContext by componentContext {
    private val lifetime = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + lifetime)
    private val mutableState = MutableStateFlow(run {
        val initial = seed?.takeIf { it.vehicle.id == vehicleId }
        val glance = initial?.glance?.takeIf { it.ride.vehicleId == vehicleId }
        VehicleOverviewState(
            vehicle = initial?.vehicle,
            lastRide = glance?.ride,
            battery = glance?.ride?.let(reader::cachedOverviewBattery) ?: listOfNotNull(glance?.battery),
            lastTotals = glance?.totals.orEmpty(),
        )
    })
    override val state: StateFlow<VehicleOverviewState> = mutableState.asStateFlow()

    init {
        lifecycle.doOnDestroy { lifetime.cancel() }
        scope.launch { vehicles.vehicles.collect { list -> mutableState.update { it.copy(vehicle = list.firstOrNull { v -> v.id == vehicleId }) } } }
        scope.launch { load() }
    }

    private suspend fun load(): Unit = coroutineScope {
        var readFailed = false
        suspend fun <T> read(block: suspend () -> T): T? = try {
            block()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            readFailed = true
            null
        }

        val rides = read { reader.newestRides(setOf(vehicleId), maxRides)[vehicleId].orEmpty() }
        if (rides == null) {
            mutableState.update { it.copy(loading = false, error = true) }
            return@coroutineScope
        }
        val last = rides.firstOrNull()
        val cached = mutableState.value.takeIf { last != null && it.lastRide == last }
        val knownBattery = last?.let(reader::cachedOverviewBattery) ?: cached?.battery.orEmpty()
        val cachedRoute = last?.let(reader::cachedRoute)
        val knownTotals = cached?.lastTotals.orEmpty()
        mutableState.update {
            it.copy(lastRide = last, battery = knownBattery, lastTotals = knownTotals, lastRoute = cachedRoute.orEmpty())
        }

        val recorded = last?.let { read { reader.recorded(it) } }
        // The map can start rendering as soon as its track arrives, alongside the metric reads.
        val routeLoad = if (last != null && recorded != null && cachedRoute == null) launch {
            val points = reader.route(last, recorded)
            mutableState.update { it.copy(lastRoute = points) }
        } else null
        val battery = if (last != null && recorded != null) {
            read { reader.overviewBattery(last, recorded) } ?: knownBattery
        } else knownBattery

        mutableState.update { it.copy(battery = battery) }
        val totals = if (last != null && recorded != null) {
            read { reader.totals(last.id, recorded) } ?: knownTotals
        } else knownTotals
        routeLoad?.join()

        // The list appears with the latest ride's totals. Older totals stay batched below.
        mutableState.update {
            it.copy(
                loading = false,
                rides = rides,
                lastTotals = totals,
                rideTotals = if (last != null) mapOf(last.id to totals) else emptyMap(),
                error = readFailed,
            )
        }

        val olderTotals = mutableMapOf<String, List<HistoryTotal>>()
        for (ride in rides.drop(1)) {
            try {
                olderTotals[ride.id] = reader.totals(ride.id, reader.recorded(ride))
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                // An unreadable ride has no totals to publish.
            }
        }
        if (olderTotals.isNotEmpty()) {
            mutableState.update { it.copy(rideTotals = it.rideTotals + olderTotals) }
        }
    }

    override fun onConnect() = onConnectRequested(vehicleId)
    override fun onOpenRide(rideId: String) = onOpenRideRequested(rideId)
    override fun onEdit() = onEditRequested(vehicleId)
    override fun onAlerts() = onAlertsRequested(vehicleId)
    override fun onBack() = onBackRequested()
}
