package ru.sodovaya.volty.presentation.picker

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.Chemistry
import ru.sodovaya.volty.domain.model.ConnectionState
import ru.sodovaya.volty.domain.model.ControllerType
import ru.sodovaya.volty.domain.model.DemoProfile
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.model.bmsTypeOrNull
import ru.sodovaya.volty.domain.model.isGuest
import ru.sodovaya.volty.domain.model.pickedControllerVehicle
import ru.sodovaya.volty.domain.model.primaryAddress
import ru.sodovaya.volty.domain.model.primaryController
import ru.sodovaya.volty.domain.model.singlePackVehicle
import ru.sodovaya.volty.domain.model.vehiclesByAddress
import ru.sodovaya.volty.domain.repository.BmsRepository
import ru.sodovaya.volty.domain.repository.DeviceTypeMemory
import ru.sodovaya.volty.domain.repository.DiscoveredDevice
import ru.sodovaya.volty.domain.repository.VehicleRepository
import ru.sodovaya.volty.domain.model.isDemo
import ru.sodovaya.volty.presentation.vehicle.VehicleGlance
import ru.sodovaya.volty.presentation.vehicle.VehicleOverviewSeed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.collect
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

    /** Start "Try demo" for [profile] — the two profiles are different vehicles. */
    fun onTryDemo(profile: DemoProfile)
    fun onOpenSaved(vehicle: Vehicle)
    /** Connect a saved vehicle that has not been seen yet: scans for it rather than failing at once. */
    fun onConnectSaved(vehicle: Vehicle)
    fun onBack()

    data class State(
        val mode: String = "cold",
        val myInRange: List<Vehicle> = emptyList(),
        /** Every saved vehicle; those not in [myInRange] are offered for an offline look. */
        val saved: List<Vehicle> = emptyList(),
        val savedLoaded: Boolean = false,
        /** Newest recorded ride of each saved vehicle, for the garage; absent while loading or never ridden. */
        val glances: Map<String, VehicleGlance> = emptyMap(),
        val glancesLoaded: Boolean = false,
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
    private val onCancelled: () -> Unit,
    private val onOpenSavedRequested: (VehicleOverviewSeed) -> Unit = {},
    private val onConnectSavedRequested: (vehicleId: String) -> Unit = {},
    private val loadGlances: (suspend (Set<String>) -> Map<String, VehicleGlance>)? = null
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
        val garage = saved.filterNot { v -> v.isGuest || v.isDemo }
        _state.update { it.copy(saved = garage, savedLoaded = true) }
        // History reads run beside the scan; the garage renders its vehicles before their numbers arrive.
        loadGlances?.let { load -> scope.launch {
            val glances = try { load(garage.map { it.id }.toSet()) }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { emptyMap() }
            _state.update { it.copy(glances = glances, glancesLoaded = true) }
        } }
        // Indexed by every address of every vehicle, not just the primary pack's:
        // a controller-only vehicle has no pack address, so keying on that alone
        // meant its own advertisement never matched it. See [vehiclesByAddress].
        val savedByAddress: Map<String, Vehicle> = vehiclesByAddress(saved)
        // BLE peripherals don't advertise while we hold an active connection,
        // so a scan would render an empty list and the user is left wondering
        // what's wrong. Seed the picker with the currently-connected device so
        // it's always visible. Saved vehicles land in myInRange; guests fall
        // back to a synthetic DiscoveredDevice in otherNearby.
        // A retrying connection is not proximity evidence. Only a live
        // Connected state may stand in for an advertisement, and that seed is
        // withdrawn as soon as the live state ends. Keep real scan hits as a
        // separate source so withdrawing the seed cannot hide a device that
        // the scanner actually observed during this picker session.
        val scanSeenVehicleIds = mutableSetOf<String>()
        val scanSeenAddresses = mutableSetOf<String>()
        var connectedSeedVehicleId: String? = null
        var connectedSeedAddress: String? = null
        scope.launch {
            bmsRepository.connectionState.collect { connection ->
                // Use the vehicle carried by Connected itself. activeVehicle
                // may briefly still refer to the previous target during a
                // reconnect/switch; it must not grant presence to that row.
                val liveVehicle = (connection as? ConnectionState.Connected)?.vehicle
                val addressMatch = liveVehicle?.let { savedByAddress[it.primaryAddress] }
                val savedMatch = addressMatch
                    ?.takeUnless { liveVehicle?.isGuest == true }
                val nextSeedVehicleId = savedMatch?.id
                val nextSeedAddress = liveVehicle?.primaryAddress
                val oldVehicleId = connectedSeedVehicleId
                val oldAddress = connectedSeedAddress
                _state.update { s ->
                    var myInRange = s.myInRange
                    var otherNearby = s.otherNearby
                    if (oldVehicleId != null && oldVehicleId != nextSeedVehicleId &&
                        oldVehicleId !in scanSeenVehicleIds
                    ) {
                        myInRange = myInRange.filterNot { it.id == oldVehicleId }
                    }
                    if (oldAddress != null && oldAddress != nextSeedAddress &&
                        oldAddress !in scanSeenAddresses
                    ) {
                        otherNearby = otherNearby.filterNot { it.address == oldAddress }
                    }
                    if (liveVehicle == null) {
                        s.copy(myInRange = myInRange, otherNearby = otherNearby)
                    } else if (savedMatch != null) {
                        if (myInRange.none { it.id == savedMatch.id }) myInRange = myInRange + savedMatch
                        otherNearby = otherNearby.filterNot { it.address == nextSeedAddress }
                        s.copy(myInRange = myInRange, otherNearby = otherNearby)
                    } else {
                        s.copy(
                            myInRange = myInRange,
                            otherNearby = otherNearby.withScanHit(
                                DiscoveredDevice(
                                    address = liveVehicle.primaryAddress,
                                    name = liveVehicle.name,
                                    rssi = 0,
                                    bmsType = liveVehicle.bmsTypeOrNull,
                                    controllerType = liveVehicle.primaryController?.controllerType,
                                    knownVehicle = addressMatch
                                )
                            )
                        )
                    }
                }
                connectedSeedVehicleId = nextSeedVehicleId
                connectedSeedAddress = nextSeedAddress
            }
        }
        scanJob = scope.launch {
            bmsRepository.scanAll().collect { dev ->
                val matched = savedByAddress[dev.address]
                if (matched != null) scanSeenVehicleIds += matched.id
                val beforeHit = _state.value
                if (matched == null &&
                    (dev.bmsType != null || beforeHit.otherNearby.any { it.address == dev.address })
                ) {
                    // Only this route publishes the address into otherNearby.
                    // A saved match goes to myInRange; an undetected new address
                    // goes to otherDevices and cannot keep a synthetic nearby
                    // row alive merely by sharing its address.
                    scanSeenAddresses += dev.address
                }
                _state.update { s ->
                    when {
                        matched != null -> {
                            val myInRange = if (s.myInRange.any { it.id == matched.id }) s.myInRange
                                            else s.myInRange + matched
                            s.copy(myInRange = myInRange)
                        }
                        dev.bmsType != null -> {
                            // A device first seen as undetected may later resolve to a type.
                            // Merge repeats in place so the row keeps its learned metadata but
                            // always shows current proximity, and remove the old unknown row.
                            s.copy(
                                otherNearby = s.otherNearby.withScanHit(dev),
                                otherDevices = s.otherDevices.filterNot { it.address == dev.address }
                            )
                        }
                        else -> {
                            if (s.otherNearby.any { it.address == dev.address }) {
                                s.copy(otherNearby = s.otherNearby.withScanHit(dev))
                            } else {
                                s.copy(
                                    otherDevices = s.otherDevices
                                        .withScanHit(dev)
                                        .sortedByDescending { it.rssi }
                                )
                            }
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
            is SourceChoice.Controller -> connectWithControllerType(device, choice.type)
            is SourceChoice.Battery -> connectWithBmsType(device, choice.type)
        }
    }

    /**
     * The controller twin of [connectWithBmsType]: build → upsert → connect →
     * edit-on-success / delete-on-failure, the same four steps in the same
     * order, so a controller vehicle is created and rolled back exactly like a
     * battery one and there is only one creation sequence to reason about.
     *
     * Two deliberate differences from the battery branch, both forced:
     *
     * 1. No `mode == "add"` fork. [BmsRepository.connectGuest] takes a
     *    [BmsType] and `buildGuestVehicle` always produces a one-PACK guest, so
     *    there is no unpersisted way to talk to a controller at all. Rather
     *    than leave the default ("cold") picker — the app's own entry point —
     *    with a dead tap, a Controller pick always creates. That is also the
     *    outcome the user asked for: they named the device's type, and the very
     *    next screen is its edit form.
     * 2. The connect goes through [connectController], which refuses a
     *    controller kind with no protocol behind it and shields the rollback
     *    from an escaping throw.
     */
    private fun connectWithControllerType(device: DiscoveredDevice, type: ControllerType) {
        scope.launch {
            _state.update { it.copy(typePickerFor = null, connecting = device.address, error = null) }
            scanJob?.cancel()
            vehicleRepository.rememberDeviceType(
                DeviceTypeMemory(address = device.address, controllerType = type)
            )
            // The SHAPE is the model layer's decision, not this screen's: a
            // Begode is a controller and its own battery over one link and gets
            // a stored pack, everything else is controller-only. See
            // [pickedControllerVehicle].
            val v = pickedControllerVehicle(
                id = newVehicleId(),
                // Named after the controller kind, not "BMS", when the device
                // advertises no name of its own.
                name = device.name ?: "${type.label} ${device.address.takeLast(4)}",
                iconKey = "generic",
                controllerType = type,
                address = device.address,
                chemistry = Chemistry.LI_ION_NMC,
                createdAt = Clock.System.now()
            )
            vehicleRepository.upsert(v)
            val result = connectController(v, type)
            if (result.isSuccess) onConnectedForEdit(v.id)
            else {
                // Same rollback as the battery branch: a connect that never
                // came up must not leave a row behind in the vehicle list.
                vehicleRepository.delete(v.id)
                _state.update { it.copy(connecting = null, error = result.exceptionOrNull()?.message) }
            }
        }
    }

    /**
     * [BmsRepository.connect] for a controller vehicle, made total.
     *
     * The gate runs AFTER the upsert on purpose: the unsupported case then
     * travels the identical persist → fail → roll back path an unreachable
     * device does, so there is one failure shape, one rollback, and no branch
     * that is only exercised when a radio is present.
     *
     * The catch is the other half. `KableBmsRepository.doConnect` wraps its own
     * body, but the caller-side preamble in `connect(vehicle)` does not, and a
     * controller vehicle is a shape that path has never carried before. An
     * escape here would leave the picker with `connecting` stuck and an orphan
     * vehicle row — so it is caught where the rollback can still run.
     * [CancellationException] is rethrown rather than swallowed: it means this
     * component's scope is going away, not that the connect failed.
     *
     * Deliberately NOT applied to [connectWithBmsType] — that path is unchanged
     * by this task, down to which throwables it lets through.
     */
    private suspend fun connectController(vehicle: Vehicle, type: ControllerType): Result<Unit> {
        unsupportedControllerReason(type)?.let {
            return Result.failure(UnsupportedOperationException(it))
        }
        return try {
            bmsRepository.connect(vehicle)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** The id every vehicle this picker creates gets — one expression, two callers. */
    private fun newVehicleId(): String =
        "v-" + kotlin.random.Random.nextLong().toString(16).removePrefix("-")

    private fun connectWithBmsType(device: DiscoveredDevice, type: BmsType) {
        scope.launch {
            _state.update { it.copy(typePickerFor = null, connecting = device.address, error = null) }
            scanJob?.cancel()
            vehicleRepository.rememberDeviceType(
                DeviceTypeMemory(address = device.address, bmsType = type)
            )
            if (mode == "add") {
                val v = singlePackVehicle(
                    id = newVehicleId(),
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

    override fun onTryDemo(profile: DemoProfile) {
        scope.launch {
            _state.update { it.copy(connecting = "demo", error = null) }
            scanJob?.cancel()
            val result = bmsRepository.connectDemo(profile)
            if (result.isSuccess) onDemoConnected()
            else _state.update { it.copy(connecting = null, error = result.exceptionOrNull()?.message) }
        }
    }

    override fun onBack() { onCancelled() }
    override fun onOpenSaved(vehicle: Vehicle) {
        onOpenSavedRequested(VehicleOverviewSeed(vehicle, state.value.glances[vehicle.id]))
    }
    override fun onConnectSaved(vehicle: Vehicle) { onConnectSavedRequested(vehicle.id) }
}
