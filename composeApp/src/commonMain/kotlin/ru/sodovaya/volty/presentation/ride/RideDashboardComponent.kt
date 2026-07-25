package ru.sodovaya.volty.presentation.ride

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import ru.sodovaya.volty.data.prefs.AppPrefs
import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.ConnectionState
import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.DashboardStyle
import ru.sodovaya.volty.domain.model.SecondaryGauge
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.repository.BmsRepository
import ru.sodovaya.volty.domain.repository.VehicleRepository
import ru.sodovaya.volty.domain.stats.RideMetrics
import ru.sodovaya.volty.util.UnitSystem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

interface RideDashboardComponent {
    val state: StateFlow<State>
    fun onPillClicked()
    fun onSheetDismiss()
    fun onSwitchVehicle(v: Vehicle)
    fun onAddVehicle()
    /** Graph is no longer a top-level tab — every dashboard carries a button to it. */
    fun onOpenGraph()
    fun onOpenSettings()
    fun onDisconnect()

    @OptIn(ExperimentalTime::class)
    data class State(
        val vehicle: Vehicle? = null,
        val motion: ControllerData = ControllerData(),
        val battery: BmsData = BmsData(),
        /** true when some controller is offline and [motion] covers only the rest. */
        val motionPartial: Boolean = false,
        val connection: ConnectionState = ConnectionState.Idle,
        val units: UnitSystem = UnitSystem.METRIC,
        val style: DashboardStyle = DashboardStyle.CLEAN,
        val secondary: SecondaryGauge = SecondaryGauge.DUTY,
        val secondaryReadout: SecondaryReadout = SecondaryGaugeMapper.map(
            SecondaryGauge.DUTY, ControllerData(), BmsData(), UnitSystem.METRIC
        ),
        val sessionWhPerKm: Float? = null,
        /**
         * Elapsed time since the first motion sample of the current
         * connection — no ticker involved. See [DefaultRideDashboardComponent].
         */
        val uptimeSeconds: Long = 0L,
        val savedVehicles: List<Vehicle> = emptyList(),
        val sheetOpen: Boolean = false
    )
}

@OptIn(ExperimentalTime::class)
class DefaultRideDashboardComponent(
    componentContext: ComponentContext,
    private val bmsRepository: BmsRepository,
    private val vehicleRepository: VehicleRepository,
    private val appPrefs: AppPrefs,
    private val onOpenGraphRequested: () -> Unit,
    private val onOpenSettingsRequested: () -> Unit,
    private val onAddVehicleRequested: () -> Unit,
    private val onDisconnectRequested: () -> Unit
) : RideDashboardComponent, ComponentContext by componentContext {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /** Latest app-level default, kept alongside [RideDashboardComponent.State.style] so either
     * the vehicle collector or the app-prefs collector can recompute the winning style without
     * waiting on the other. */
    private var appDefaultStyle: DashboardStyle = appPrefs.defaultDashboardStyle.value

    /**
     * Instant of the first motion sample seen since the vehicle was last
     * [ConnectionState.Connected]. Null before any sample has arrived for this
     * connection. Reset whenever [ConnectionState] leaves Connected, so a
     * reconnect starts the uptime clock over.
     */
    private var sessionStartedAt: Instant? =
        if (bmsRepository.connectionState.value is ConnectionState.Connected) {
            bmsRepository.activeMotion.value.timestamp
        } else {
            null
        }

    private val _state: MutableStateFlow<RideDashboardComponent.State> = run {
        val initialVehicle = bmsRepository.activeVehicle.value
        val initialMotion = bmsRepository.activeMotion.value
        val initialVehicleData = bmsRepository.activeVehicleData.value
        val initialUnits = appPrefs.unitSystem.value
        val initialSecondary = initialVehicle?.secondaryGauge ?: SecondaryGauge.DUTY
        val initialStyle = initialVehicle?.dashboardStyle ?: appDefaultStyle
        MutableStateFlow(
            RideDashboardComponent.State(
                vehicle = initialVehicle,
                motion = initialMotion,
                battery = initialVehicleData.aggregate,
                motionPartial = initialVehicleData.motionPartial,
                connection = bmsRepository.connectionState.value,
                units = initialUnits,
                style = initialStyle,
                secondary = initialSecondary,
                secondaryReadout = SecondaryGaugeMapper.map(
                    initialSecondary, initialMotion, initialVehicleData.aggregate, initialUnits
                ),
                sessionWhPerKm = RideMetrics.sessionWhPerKm(initialMotion.consumedWh, initialMotion.tripKm),
                uptimeSeconds = sessionStartedAt?.let { (initialMotion.timestamp - it).inWholeSeconds.coerceAtLeast(0) } ?: 0L
            )
        )
    }
    override val state: StateFlow<RideDashboardComponent.State> = _state.asStateFlow()

    init {
        lifecycle.doOnDestroy { scope.coroutineContext[Job]?.cancel() }

        scope.launch {
            bmsRepository.activeMotion.collect { motion ->
                if (sessionStartedAt == null) sessionStartedAt = motion.timestamp
                val uptime = (motion.timestamp - sessionStartedAt!!).inWholeSeconds.coerceAtLeast(0)
                _state.update { current ->
                    current.copy(
                        motion = motion,
                        uptimeSeconds = uptime,
                        sessionWhPerKm = RideMetrics.sessionWhPerKm(motion.consumedWh, motion.tripKm),
                        secondaryReadout = SecondaryGaugeMapper.map(current.secondary, motion, current.battery, current.units)
                    )
                }
            }
        }

        scope.launch {
            bmsRepository.activeVehicleData.collect { vd ->
                _state.update { current ->
                    current.copy(
                        battery = vd.aggregate,
                        motionPartial = vd.motionPartial,
                        secondaryReadout = SecondaryGaugeMapper.map(current.secondary, current.motion, vd.aggregate, current.units)
                    )
                }
            }
        }

        scope.launch {
            bmsRepository.activeVehicle.collect { vehicle ->
                _state.update { current ->
                    val secondary = vehicle?.secondaryGauge ?: SecondaryGauge.DUTY
                    current.copy(
                        vehicle = vehicle,
                        style = vehicle?.dashboardStyle ?: appDefaultStyle,
                        secondary = secondary,
                        secondaryReadout = SecondaryGaugeMapper.map(secondary, current.motion, current.battery, current.units)
                    )
                }
            }
        }

        scope.launch {
            bmsRepository.connectionState.collect { c ->
                if (c !is ConnectionState.Connected) sessionStartedAt = null
                _state.update { it.copy(connection = c) }
            }
        }

        scope.launch {
            vehicleRepository.vehicles.collect { list ->
                _state.update { it.copy(savedVehicles = list) }
            }
        }

        scope.launch {
            appPrefs.unitSystem.collect { units ->
                _state.update { current ->
                    current.copy(
                        units = units,
                        secondaryReadout = SecondaryGaugeMapper.map(current.secondary, current.motion, current.battery, units)
                    )
                }
            }
        }

        scope.launch {
            appPrefs.defaultDashboardStyle.collect { appDefault ->
                appDefaultStyle = appDefault
                _state.update { current -> current.copy(style = current.vehicle?.dashboardStyle ?: appDefault) }
            }
        }
    }

    override fun onPillClicked() { _state.update { it.copy(sheetOpen = !it.sheetOpen) } }
    override fun onSheetDismiss() { _state.update { it.copy(sheetOpen = false) } }

    override fun onSwitchVehicle(v: Vehicle) {
        scope.launch {
            _state.update { it.copy(sheetOpen = false) }
            bmsRepository.disconnect()
            bmsRepository.connect(v)
        }
    }

    override fun onAddVehicle() {
        // Mirrors DashboardComponent.onAddBattery — a real navigation hook the
        // sheet's "+ Add battery" affordance can invoke, rather than a
        // relabeled Settings shortcut. Clears the sheet first: it's about to
        // navigate away, same as onSwitchVehicle does before it (dis)connects.
        _state.update { it.copy(sheetOpen = false) }
        onAddVehicleRequested()
    }

    override fun onOpenGraph() {
        // Same reasoning as onOpenSettings — we're about to navigate away.
        _state.update { it.copy(sheetOpen = false) }
        onOpenGraphRequested()
    }

    override fun onOpenSettings() {
        // Returning from Settings must not find the vehicle sheet still open.
        _state.update { it.copy(sheetOpen = false) }
        onOpenSettingsRequested()
    }

    override fun onDisconnect() {
        scope.launch {
            bmsRepository.disconnect()
            onDisconnectRequested()
        }
    }
}
