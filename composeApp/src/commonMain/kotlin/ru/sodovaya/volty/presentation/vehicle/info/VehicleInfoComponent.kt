package ru.sodovaya.volty.presentation.vehicle.info

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ru.sodovaya.volty.data.energy.RangeSource
import ru.sodovaya.volty.domain.alert.availabilityFor
import ru.sodovaya.volty.domain.energy.EnergyRecord
import ru.sodovaya.volty.domain.energy.VehicleEnergyStore
import ru.sodovaya.volty.domain.model.ConnectionState
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.repository.BleDiagnosticsRepository
import ru.sodovaya.volty.domain.repository.BmsRepository
import ru.sodovaya.volty.domain.repository.VehicleRepository
import ru.sodovaya.volty.util.UnitSystem
import kotlin.time.Clock

interface VehicleInfoComponent {
    /** The whole screen; null until this vehicle is known and the first snapshot is built. */
    val info: StateFlow<VehicleInfo?>

    /** The vehicle's name for the title bar; null while it is unknown. */
    val vehicleName: StateFlow<String?>
    fun onBack()
}

/**
 * «Всё о ТС» for one vehicle: gathers what the app holds (the saved vehicle, the live link, the
 * diagnostics links, the energy model, the range figures, the persisted energy record) and folds
 * it through [vehicleInfoOf].
 *
 * **At most two rebuilds a second.** The model is rebuilt on a fixed [refreshMillis] tick that reads
 * the latest value of every source, not on each source's emission: a Begode publishes ~10 frames a
 * second and the screen is a table of numbers someone is reading. The tick is also what lets a
 * stalled link go stale on screen ([LINK_STATS_MAX_AGE_MS]) with no emission to trigger it. The
 * loop lives in [scope] and ends with the component; a test must hand it a scope that is cancelled
 * at the end (`backgroundScope`) and advance a bounded amount of virtual time.
 *
 * **The stored energy record is read with `peek`**, never `load` (which deletes a row whose key does
 * not match): once on open, and again each time the connection drops, because the tracker saves as
 * it goes and the next look must show what it left.
 *
 * "Connected" means the app's active vehicle is THIS vehicle and its link is `Connected`; a screen
 * opened on another saved vehicle shows only configuration and persisted values.
 */
class DefaultVehicleInfoComponent(
    componentContext: ComponentContext,
    parentScope: CoroutineScope,
    private val vehicleId: String,
    private val vehicles: VehicleRepository,
    private val bms: BmsRepository,
    private val diagnostics: BleDiagnosticsRepository,
    private val energyStore: VehicleEnergyStore,
    private val range: RangeSource,
    private val unitSystem: StateFlow<UnitSystem>,
    private val onBackRequested: () -> Unit,
    private val refreshMillis: Long = 500L,
    private val nowEpochMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : VehicleInfoComponent, ComponentContext by componentContext {
    private val lifetime = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + lifetime)

    private val saved = MutableStateFlow<Vehicle?>(null)
    private val stored = MutableStateFlow<EnergyRecord?>(null)
    private val mutableInfo = MutableStateFlow<VehicleInfo?>(null)
    private val mutableName = MutableStateFlow<String?>(null)
    private var peeked = false

    override val info: StateFlow<VehicleInfo?> = mutableInfo.asStateFlow()
    override val vehicleName: StateFlow<String?> = mutableName.asStateFlow()

    init {
        lifecycle.doOnDestroy { lifetime.cancel() }
        scope.launch { vehicles.vehicles.collect { list -> saved.value = list.firstOrNull { it.id == vehicleId } } }
        // The first emission is "on open"; every later false is "the connection dropped".
        scope.launch {
            combine(bms.activeVehicle, bms.connectionState) { v, s -> isLinkTo(v, s) }
                .distinctUntilChanged()
                .collect { connected -> if (!connected || !peeked) { peeked = true; refreshStored(); rebuild() } }
        }
        scope.launch {
            while (isActive) {
                rebuild()
                delay(refreshMillis)
            }
        }
    }

    private fun isLinkTo(active: Vehicle?, state: ConnectionState): Boolean =
        active?.id == vehicleId && state is ConnectionState.Connected

    private suspend fun refreshStored() {
        try { stored.value = energyStore.peek(vehicleId) }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { /* an unreadable row is "nothing persisted" on screen, not a crash */ }
    }

    private fun rebuild() {
        val active = bms.activeVehicle.value
        val vehicle = saved.value ?: active?.takeIf { it.id == vehicleId }
        mutableName.value = vehicle?.name
        if (vehicle == null) { mutableInfo.value = null; return }
        val connected = isLinkTo(active, bms.connectionState.value)
        val motion = bms.activeMotion.value.takeIf { connected }
        val next = vehicleInfoOf(
            vehicle = vehicle,
            connected = connected,
            data = bms.activeVehicleData.value.takeIf { connected },
            motion = motion,
            links = diagnostics.state.value.activeLinks,
            stored = stored.value,
            range = range.detail.value,
            availability = availabilityFor(vehicle, motion),
            units = unitSystem.value,
            nowEpochMillis = nowEpochMillis(),
        )
        if (next != mutableInfo.value) mutableInfo.value = next
    }

    override fun onBack() = onBackRequested()
}
