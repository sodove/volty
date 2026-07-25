package ru.sodovaya.volty.presentation.picker

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.Chemistry
import ru.sodovaya.volty.domain.model.ConnectionState
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.model.bmsTypeOrNull
import ru.sodovaya.volty.domain.model.isGuest
import ru.sodovaya.volty.domain.model.primaryAddress
import ru.sodovaya.volty.domain.model.primaryController
import ru.sodovaya.volty.domain.model.singlePackVehicle
import ru.sodovaya.volty.domain.model.vehiclesByAddress
import ru.sodovaya.volty.domain.repository.BmsRepository
import ru.sodovaya.volty.domain.repository.DiscoveredDevice
import ru.sodovaya.volty.domain.repository.VehicleRepository
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
    fun onToggleShowAll()
    fun onDeviceTapped(device: DiscoveredDevice)
    fun onTypeSheetDismissed()
    fun onConnectWithType(device: DiscoveredDevice, choice: SourceChoice)
    fun onAddNewBattery()
    fun onTryDemo()
    fun onBack()

    data class State(
        val mode: String = "cold",
        val myInRange: List<Vehicle> = emptyList(),
        val otherNearby: List<DiscoveredDevice> = emptyList(),   // detected (bmsType != null)
        val otherDevices: List<DiscoveredDevice> = emptyList(),  // undetected (bmsType == null)
        val showAll: Boolean = false,
        val typePickerFor: DiscoveredDevice? = null,             // device whose type sheet is open
        val connecting: String? = null,
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
    private val onDemoConnected: () -> Unit,
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
        // Indexed by every address of every vehicle, not just the primary pack's:
        // a controller-only vehicle has no pack address, so keying on that alone
        // meant its own advertisement never matched it. See [vehiclesByAddress].
        val savedByAddress: Map<String, Vehicle> = vehiclesByAddress(saved)
        // BLE peripherals don't advertise while we hold an active connection,
        // so a scan would render an empty list and the user is left wondering
        // what's wrong. Seed the picker with the currently-connected device so
        // it's always visible. Saved vehicles land in myInRange; guests fall
        // back to a synthetic DiscoveredDevice in otherNearby.
        val activeConn = bmsRepository.connectionState.value
        val activeVehicle = bmsRepository.activeVehicle.value
        if (activeVehicle != null &&
            (activeConn is ConnectionState.Connected || activeConn is ConnectionState.Reconnecting)
        ) {
            // primaryAddress, not the primary pack's: it already prefers the
            // controller and is defined for a vehicle with zero packs.
            val savedMatch = savedByAddress[activeVehicle.primaryAddress]
            _state.update { s ->
                if (savedMatch != null && !activeVehicle.isGuest) {
                    s.copy(myInRange = listOf(savedMatch))
                } else {
                    s.copy(
                        otherNearby = listOf(
                            DiscoveredDevice(
                                address = activeVehicle.primaryAddress,
                                name = activeVehicle.name,
                                rssi = 0,
                                // Both nullable and both carried, so the row can
                                // name a controller-only vehicle by its
                                // controller instead of falling through to
                                // "unknown type". A pack-only vehicle keeps
                                // exactly the old pair (type, null).
                                bmsType = activeVehicle.bmsTypeOrNull,
                                controllerType = activeVehicle.primaryController?.controllerType,
                                knownVehicle = savedMatch
                            )
                        )
                    )
                }
            }
        }
        scanJob = scope.launch {
            bmsRepository.scanAll().collect { dev ->
                val matched = savedByAddress[dev.address]
                _state.update { s ->
                    when {
                        matched != null -> {
                            val myInRange = if (s.myInRange.any { it.id == matched.id }) s.myInRange
                                            else s.myInRange + matched
                            s.copy(myInRange = myInRange)
                        }
                        dev.bmsType != null -> {
                            if (s.otherNearby.any { it.address == dev.address }) s
                            // A device first seen as undetected may later resolve to a type —
                            // drop it from otherDevices so it can't appear in both lists.
                            else s.copy(
                                otherNearby = s.otherNearby + dev,
                                otherDevices = s.otherDevices.filterNot { it.address == dev.address }
                            )
                        }
                        else -> {
                            if (s.otherDevices.any { it.address == dev.address } ||
                                s.otherNearby.any { it.address == dev.address }) s
                            else s.copy(otherDevices = (s.otherDevices + dev).sortedByDescending { it.rssi })
                        }
                    }
                }
            }
        }
    }

    override fun onConnectKnown(vehicle: Vehicle) {
        scope.launch {
            // Must stay the same expression PickerScreen's row compares against,
            // and must be defined for a vehicle with zero packs — primaryAddress
            // is both, and is also what BmsRepository.connect() identifies by.
            _state.update { it.copy(connecting = vehicle.primaryAddress, error = null) }
            scanJob?.cancel()
            val result = bmsRepository.connect(vehicle)
            if (result.isSuccess) onConnectedKnown()
            else _state.update { it.copy(connecting = null, error = result.exceptionOrNull()?.message) }
        }
    }

    override fun onToggleShowAll() {
        _state.update { it.copy(showAll = !it.showAll) }
    }

    override fun onDeviceTapped(device: DiscoveredDevice) {
        if (_state.value.connecting != null) return
        _state.update { it.copy(typePickerFor = device) }
    }

    override fun onTypeSheetDismissed() {
        _state.update { it.copy(typePickerFor = null) }
    }

    override fun onConnectWithType(device: DiscoveredDevice, choice: SourceChoice) {
        if (_state.value.connecting != null) return
        when (choice) {
            // Building and connecting a controller vehicle is not this component's
            // job yet: controllerVehicle(...) (Task 4) and the creation/connect/
            // rollback sequence for it (Task 5) don't exist. Until they land, a
            // Controller pick is an explicit, inert no-op — it just closes the
            // sheet, same as dismissing it, and starts nothing.
            is SourceChoice.Controller -> _state.update { it.copy(typePickerFor = null) }
            is SourceChoice.Battery -> connectWithBmsType(device, choice.type)
        }
    }

    private fun connectWithBmsType(device: DiscoveredDevice, type: BmsType) {
        scope.launch {
            _state.update { it.copy(typePickerFor = null, connecting = device.address, error = null) }
            scanJob?.cancel()
            if (mode == "add") {
                val v = singlePackVehicle(
                    id = "v-" + kotlin.random.Random.nextLong().toString(16).removePrefix("-"),
                    name = device.name ?: "BMS ${device.address.takeLast(4)}",
                    iconKey = "generic",
                    bmsType = type,
                    bmsAddress = device.address,
                    chemistry = Chemistry.LI_ION_NMC,
                    createdAt = Clock.System.now()
                )
                vehicleRepository.upsert(v)
                val result = bmsRepository.connect(v)
                if (result.isSuccess) onConnectedForEdit(v.id)
                else {
                    vehicleRepository.delete(v.id)
                    _state.update { it.copy(connecting = null, error = result.exceptionOrNull()?.message) }
                }
            } else {
                val result = bmsRepository.connectGuest(device.address, type)
                if (result.isSuccess) onConnectedGuestNoSave()
                else _state.update { it.copy(connecting = null, error = result.exceptionOrNull()?.message) }
            }
        }
    }

    override fun onAddNewBattery() { onAddNewBatteryRequested() }

    override fun onTryDemo() {
        scope.launch {
            _state.update { it.copy(connecting = "demo", error = null) }
            scanJob?.cancel()
            val result = bmsRepository.connectDemo()
            if (result.isSuccess) onDemoConnected()
            else _state.update { it.copy(connecting = null, error = result.exceptionOrNull()?.message) }
        }
    }

    override fun onBack() { onCancelled() }
}
