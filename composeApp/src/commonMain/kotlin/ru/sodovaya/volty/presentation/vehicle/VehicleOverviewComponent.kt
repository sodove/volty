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
) : VehicleOverviewComponent, ComponentContext by componentContext {
    private val lifetime = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + lifetime)
    private val mutableState = MutableStateFlow(VehicleOverviewState())
    override val state: StateFlow<VehicleOverviewState> = mutableState.asStateFlow()

    init {
        lifecycle.doOnDestroy { lifetime.cancel() }
        scope.launch { vehicles.vehicles.collect { list -> mutableState.update { it.copy(vehicle = list.firstOrNull { v -> v.id == vehicleId }) } } }
        scope.launch { load() }
    }

    private suspend fun load() {
        var readFailed = false
        suspend fun <T> read(block: suspend () -> T): T? = try {
            block()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            readFailed = true
            null
        }

        val rides = read { reader.newestRides(setOf(vehicleId), maxRides)[vehicleId] }.orEmpty()
        val last = rides.firstOrNull()
        val recorded = last?.let { read { reader.recorded(it) } }
        val ready = VehicleOverviewState(loading = false, rides = rides, lastRide = last)
        val overview = if (last != null && recorded != null) {
            val battery = read { reader.battery(last.id, recorded, withCells = true) }
            val totals = read { reader.totals(last.id, recorded) }
            val route = read { reader.route(last.id, recorded) }
            ready.copy(
                battery = battery.orEmpty(),
                lastTotals = totals.orEmpty(),
                lastRoute = route.orEmpty(),
                rideTotals = totals?.let { mapOf(last.id to it) }.orEmpty(),
            )
        } else ready
        // Publish the layout together so a late route or battery card cannot move the ride list.
        mutableState.update { overview.copy(vehicle = it.vehicle, error = readFailed) }

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
