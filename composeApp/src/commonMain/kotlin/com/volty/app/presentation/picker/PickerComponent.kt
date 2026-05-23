package com.volty.app.presentation.picker

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import com.volty.app.domain.model.Chemistry
import com.volty.app.domain.model.Vehicle
import com.volty.app.domain.repository.BmsRepository
import com.volty.app.domain.repository.DiscoveredDevice
import com.volty.app.domain.repository.VehicleRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

interface PickerComponent {
    val state: StateFlow<State>
    fun onConnectKnown(vehicle: Vehicle)
    fun onConnectOther(device: DiscoveredDevice)
    fun onAddNewBattery()
    fun onBack()

    data class State(
        val mode: String = "cold",
        val myInRange: List<Vehicle> = emptyList(),
        val otherNearby: List<DiscoveredDevice> = emptyList(),
        val connecting: String? = null,    // address being connected
        val error: String? = null
    )
}

@OptIn(ExperimentalTime::class)
class DefaultPickerComponent(
    componentContext: ComponentContext,
    private val mode: String,
    private val bmsRepository: BmsRepository,
    private val vehicleRepository: VehicleRepository,
    private val onConnectedKnown: () -> Unit,
    private val onConnectedForEdit: (vehicleId: String) -> Unit,
    private val onConnectedGuestNoSave: () -> Unit,
    private val onAddNewBatteryRequested: () -> Unit,
    private val onCancelled: () -> Unit
) : PickerComponent, ComponentContext by componentContext {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val _state = MutableStateFlow(PickerComponent.State(mode = mode))
    override val state: StateFlow<PickerComponent.State> = _state.asStateFlow()

    private var scanJob: Job? = null

    init {
        lifecycle.doOnDestroy { scope.coroutineContext[Job]?.cancel() }
        scope.launch { startScan() }
    }

    private suspend fun startScan() {
        val saved = vehicleRepository.vehicles.first()
        val savedByAddress: Map<String, Vehicle> = saved.associateBy { it.bmsAddress }
        scanJob = scope.launch {
            bmsRepository.scanAll().collect { dev ->
                val matched = savedByAddress[dev.address]
                _state.update { s ->
                    if (matched != null) {
                        val myInRange = if (s.myInRange.any { it.id == matched.id }) s.myInRange
                                        else s.myInRange + matched
                        s.copy(myInRange = myInRange)
                    } else {
                        val otherNearby = if (s.otherNearby.any { it.address == dev.address }) s.otherNearby
                                          else s.otherNearby + dev
                        s.copy(otherNearby = otherNearby)
                    }
                }
            }
        }
    }

    override fun onConnectKnown(vehicle: Vehicle) {
        scope.launch {
            _state.update { it.copy(connecting = vehicle.bmsAddress, error = null) }
            scanJob?.cancel()
            val result = bmsRepository.connect(vehicle)
            if (result.isSuccess) onConnectedKnown()
            else _state.update { it.copy(connecting = null, error = result.exceptionOrNull()?.message) }
        }
    }

    override fun onConnectOther(device: DiscoveredDevice) {
        scope.launch {
            _state.update { it.copy(connecting = device.address, error = null) }
            scanJob?.cancel()
            if (mode == "add") {
                // Create and save Vehicle, then connect as known so activeVehicle is set
                val v = Vehicle(
                    id = "v-" + kotlin.random.Random.nextLong().toString(16).removePrefix("-"),
                    name = device.name ?: "BMS ${device.address.takeLast(4)}",
                    iconKey = "generic",
                    bmsType = device.bmsType,
                    bmsAddress = device.address,
                    chemistry = Chemistry.LI_ION_NMC,
                    createdAt = Clock.System.now()
                )
                vehicleRepository.upsert(v)
                val result = bmsRepository.connect(v)
                if (result.isSuccess) onConnectedForEdit(v.id)
                else {
                    vehicleRepository.delete(v.id) // rollback so user isn't stuck with a broken save
                    _state.update { it.copy(connecting = null, error = result.exceptionOrNull()?.message) }
                }
            } else {
                // guest / cold mode: connect as guest, no save
                val result = bmsRepository.connectGuest(device.address, device.bmsType)
                if (result.isSuccess) onConnectedGuestNoSave()
                else _state.update { it.copy(connecting = null, error = result.exceptionOrNull()?.message) }
            }
        }
    }

    override fun onAddNewBattery() { onAddNewBatteryRequested() }
    override fun onBack() { onCancelled() }
}
