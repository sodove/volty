package ru.sodovaya.volty.presentation.vehicle

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import ru.sodovaya.volty.domain.history.*
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.repository.VehicleRepository
import ru.sodovaya.volty.presentation.history.HistoryTotal

data class VehicleOverviewState(
    val vehicle: Vehicle? = null,
    val loading: Boolean = true,
    val rides: List<HistoryRide> = emptyList(),
    val lastRide: HistoryRide? = null,
    val battery: List<VehicleBatterySnapshot> = emptyList(),
    val lastTotals: List<HistoryTotal> = emptyList(),
    val lastRoute: List<Pair<Double, Double>> = emptyList(),
    /** Filled ride by ride after the main content; a ride missing here is still loading or unreadable. */
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
        val rides = try {
            reader.newestRides(setOf(vehicleId), maxRides)[vehicleId].orEmpty()
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { mutableState.update { it.copy(loading = false, error = true) }; return }
        val last = rides.firstOrNull()
        mutableState.update { it.copy(rides = rides, lastRide = last) }
        if (last != null) try {
            val recorded = reader.recorded(last)
            mutableState.update { it.copy(battery = reader.battery(last.id, recorded, withCells = true)) }
            val totals = reader.totals(last.id, recorded)
            mutableState.update { it.copy(lastTotals = totals, rideTotals = it.rideTotals + (last.id to totals)) }
            mutableState.update { it.copy(lastRoute = reader.route(last.id, recorded)) }
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { mutableState.update { it.copy(error = true) } }
        mutableState.update { it.copy(loading = false) }
        // The list's own numbers come last and one at a time, so the screen is usable first.
        rides.drop(1).forEach { ride ->
            try {
                val totals = reader.totals(ride.id, reader.recorded(ride))
                mutableState.update { it.copy(rideTotals = it.rideTotals + (ride.id to totals)) }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { }
        }
    }

    override fun onConnect() = onConnectRequested(vehicleId)
    override fun onOpenRide(rideId: String) = onOpenRideRequested(rideId)
    override fun onEdit() = onEditRequested(vehicleId)
    override fun onAlerts() = onAlertsRequested(vehicleId)
    override fun onBack() = onBackRequested()
}
