package ru.sodovaya.volty.presentation.dashboard

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.ConnectionState
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.repository.BmsRepository
import ru.sodovaya.volty.domain.repository.VehicleRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

interface DashboardComponent {
    val state: StateFlow<State>
    fun onPillClicked()
    fun onSheetDismiss()
    fun onSwitchVehicle(v: Vehicle)
    fun onAddBattery()
    fun onDisconnect()
    fun onTabClicked(tab: Tab)

    enum class Tab { Live, Graph, Settings }

    data class State(
        val vehicle: Vehicle? = null,
        val data: BmsData = BmsData(),
        val avgPowerW: Float = 0f,
        val avgCurrentA: Float = 0f,
        // 30 s window — used only for the charge/discharge direction decision so
        // brief regen bursts during a long discharge don't flip the label, but
        // plugging in a charger flips it within ~30 s.
        val recentAvgPowerW: Float = 0f,
        val powerMin: Float = 0f,
        val powerPeak: Float = 0f,
        val sparkline: List<Float> = emptyList(),
        val cellsMinV: Float = 0f,
        val cellsMaxV: Float = 0f,
        val cellsDeltaMv: Int = 0,
        val savedVehicles: List<Vehicle> = emptyList(),
        val sheetOpen: Boolean = false,
        val isCharging: Boolean = false,
        val connection: ConnectionState = ConnectionState.Idle
    )
}

class DefaultDashboardComponent(
    componentContext: ComponentContext,
    private val bmsRepository: BmsRepository,
    private val vehicleRepository: VehicleRepository,
    private val onOpenGraph: () -> Unit,
    private val onOpenSettings: () -> Unit,
    private val onOpenAddBattery: () -> Unit,
    private val onDisconnectRequested: () -> Unit
) : DashboardComponent, ComponentContext by componentContext {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val _state: MutableStateFlow<DashboardComponent.State> = run {
        val initialData = bmsRepository.activeData.value
        val initialVehicle = bmsRepository.activeVehicle.value
        val cells = initialData.cellVoltages
        val minV = if (cells.isEmpty()) 0f else cells.min()
        val maxV = if (cells.isEmpty()) 0f else cells.max()
        // movingAverage is now a cold Flow — its initial seed comes from the
        // collector launched in `init`. Until then we display 0, which is what
        // the previous code effectively rendered anyway (the StateFlow seed
        // was MovingAvg(0f, 0f, window)).
        MutableStateFlow(
            DashboardComponent.State(
                data = initialData,
                vehicle = initialVehicle,
                cellsMinV = minV,
                cellsMaxV = maxV,
                cellsDeltaMv = ((maxV - minV) * 1000f).toInt(),
                isCharging = initialData.current > 0.05f,
                connection = bmsRepository.connectionState.value,
            )
        )
    }
    override val state: StateFlow<DashboardComponent.State> = _state.asStateFlow()

    init {
        lifecycle.doOnDestroy { scope.coroutineContext[Job]?.cancel() }

        scope.launch {
            bmsRepository.activeData
                .combine(bmsRepository.activeVehicle) { d, v -> d to v }
                .collect { (data, vehicle) ->
                    _state.update { current ->
                        val cells = data.cellVoltages
                        val minV = if (cells.isEmpty()) 0f else cells.min()
                        val maxV = if (cells.isEmpty()) 0f else cells.max()
                        current.copy(
                            data = data,
                            vehicle = vehicle,
                            cellsMinV = minV,
                            cellsMaxV = maxV,
                            cellsDeltaMv = ((maxV - minV) * 1000f).toInt(),
                            isCharging = data.current > 0.05f
                        )
                    }
                }
        }

        scope.launch {
            vehicleRepository.vehicles.collect { list ->
                _state.update { it.copy(savedVehicles = list) }
            }
        }

        scope.launch {
            bmsRepository.connectionState.collect { c ->
                _state.update { it.copy(connection = c) }
            }
        }

        scope.launch {
            val window = (_state.value.vehicle?.averagingWindowMin ?: 5).minutes
            bmsRepository.movingAverage(window).collect { avg ->
                _state.update { it.copy(avgPowerW = avg.avgPowerW, avgCurrentA = avg.avgCurrentA) }
            }
        }

        // Second moving-average subscription with a SHORT window. Drives only the
        // charge/discharge direction decision in HeroCard — fast enough to flip
        // within ~30 s of plugging in / unplugging, slow enough to ignore brief
        // regen blips during a long discharge.
        scope.launch {
            bmsRepository.movingAverage(30.seconds).collect { avg ->
                _state.update { it.copy(recentAvgPowerW = avg.avgPowerW) }
            }
        }

        scope.launch {
            bmsRepository.samples(5.minutes).collect { samples ->
                val powers = samples.map { it.power }
                _state.update {
                    it.copy(
                        sparkline = powers,
                        powerMin = if (powers.isEmpty()) 0f else powers.min(),
                        powerPeak = if (powers.isEmpty()) 0f else powers.max()
                    )
                }
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

    override fun onAddBattery() { onOpenAddBattery() }

    override fun onDisconnect() {
        scope.launch {
            bmsRepository.disconnect()
            onDisconnectRequested()
        }
    }

    override fun onTabClicked(tab: DashboardComponent.Tab) {
        when (tab) {
            DashboardComponent.Tab.Live -> {} // already on Live
            DashboardComponent.Tab.Graph -> onOpenGraph()
            DashboardComponent.Tab.Settings -> onOpenSettings()
        }
    }
}

// Helper math: time remaining (to-empty when discharging, to-full when charging)
fun timeRemainingDescription(
    isCharging: Boolean,
    remainingAh: Float,
    capacityAh: Float,
    avgPowerW: Float,
    nominalV: Float
): String {
    val power = kotlin.math.abs(avgPowerW)
    if (power < 1f || nominalV <= 0f) return "—"
    val avgCurrentA = power / nominalV
    val targetAh = if (isCharging) (capacityAh - remainingAh) else remainingAh
    if (targetAh <= 0f) return "—"
    val hoursLeft = targetAh / avgCurrentA
    val totalMinutes = (hoursLeft * 60).toInt()
    val h = totalMinutes / 60
    val m = totalMinutes % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}
