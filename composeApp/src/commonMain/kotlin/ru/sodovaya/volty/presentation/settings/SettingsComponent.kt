package ru.sodovaya.volty.presentation.settings

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import ru.sodovaya.volty.data.prefs.AppPrefs
import ru.sodovaya.volty.diagnostics.LogExporter
import ru.sodovaya.volty.domain.model.DashboardStyle
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.repository.VehicleRepository
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

interface SettingsComponent {
    val state: StateFlow<State>

    fun onThemeChanged(theme: String)
    fun onDynamicColorChanged(enabled: Boolean)
    fun onScanTimeoutChanged(sec: Int)
    fun onAutoConnectCountdownChanged(sec: Int)
    fun onUnitSystemChanged(system: UnitSystem)
    fun onDefaultDashboardStyleChanged(style: DashboardStyle)
    fun onFaultDisplayDurationChanged(seconds: Int)
    fun onEditVehicle(id: String)
    fun onDeleteVehicle(id: String)
    fun onAddBattery()
    fun onSendLogs()
    fun onBack()

    data class State(
        val themeMode: String = "system",
        val dynamicColor: Boolean = true,
        val scanTimeoutSec: Int = 5,
        val autoConnectCountdownSec: Int = 3,
        val unitSystem: UnitSystem = UnitSystem.METRIC,
        val defaultDashboardStyle: DashboardStyle = DashboardStyle.CLEAN,
        val faultDisplayDurationSec: Int = 60,
        val vehicles: List<Vehicle> = emptyList()
    )
}

class DefaultSettingsComponent(
    componentContext: ComponentContext,
    private val appPrefs: AppPrefs,
    private val vehicleRepository: VehicleRepository,
    private val logExporter: LogExporter,
    private val onEditVehicleRequested: (String) -> Unit,
    private val onAddBatteryRequested: () -> Unit,
    private val onBackRequested: () -> Unit
) : SettingsComponent, ComponentContext by componentContext {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val _state = MutableStateFlow(
        SettingsComponent.State(
            themeMode = appPrefs.themeMode.value,
            dynamicColor = appPrefs.dynamicColorEnabled.value,
            scanTimeoutSec = appPrefs.scanTimeoutSec.value,
            autoConnectCountdownSec = appPrefs.autoConnectCountdownSec.value,
            unitSystem = appPrefs.unitSystem.value,
            defaultDashboardStyle = appPrefs.defaultDashboardStyle.value,
            faultDisplayDurationSec = appPrefs.faultDisplayDurationSec.value
        )
    )
    override val state: StateFlow<SettingsComponent.State> = _state.asStateFlow()

    init {
        lifecycle.doOnDestroy { scope.coroutineContext[Job]?.cancel() }

        scope.launch {
            vehicleRepository.vehicles.collect { list ->
                _state.update { it.copy(vehicles = list) }
            }
        }
        scope.launch { appPrefs.themeMode.collect { v -> _state.update { it.copy(themeMode = v) } } }
        scope.launch { appPrefs.dynamicColorEnabled.collect { v -> _state.update { it.copy(dynamicColor = v) } } }
        scope.launch { appPrefs.scanTimeoutSec.collect { v -> _state.update { it.copy(scanTimeoutSec = v) } } }
        scope.launch { appPrefs.autoConnectCountdownSec.collect { v -> _state.update { it.copy(autoConnectCountdownSec = v) } } }
        scope.launch { appPrefs.unitSystem.collect { v -> _state.update { it.copy(unitSystem = v) } } }
        scope.launch { appPrefs.defaultDashboardStyle.collect { v -> _state.update { it.copy(defaultDashboardStyle = v) } } }
        scope.launch { appPrefs.faultDisplayDurationSec.collect { v -> _state.update { it.copy(faultDisplayDurationSec = v) } } }
    }

    override fun onThemeChanged(theme: String) { scope.launch { appPrefs.setThemeMode(theme) } }
    override fun onDynamicColorChanged(enabled: Boolean) { scope.launch { appPrefs.setDynamicColorEnabled(enabled) } }
    override fun onScanTimeoutChanged(sec: Int) { scope.launch { appPrefs.setScanTimeoutSec(sec) } }
    override fun onAutoConnectCountdownChanged(sec: Int) { scope.launch { appPrefs.setAutoConnectCountdownSec(sec) } }
    override fun onUnitSystemChanged(system: UnitSystem) { scope.launch { appPrefs.setUnitSystem(system) } }
    override fun onDefaultDashboardStyleChanged(style: DashboardStyle) { scope.launch { appPrefs.setDefaultDashboardStyle(style) } }
    override fun onFaultDisplayDurationChanged(seconds: Int) { scope.launch { appPrefs.setFaultDisplayDurationSec(seconds) } }
    override fun onEditVehicle(id: String) { onEditVehicleRequested(id) }
    override fun onDeleteVehicle(id: String) { scope.launch { vehicleRepository.delete(id) } }
    override fun onAddBattery() { onAddBatteryRequested() }
    override fun onSendLogs() { logExporter.exportLogs() }
    override fun onBack() { onBackRequested() }
}
