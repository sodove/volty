package com.volty.app.presentation.debug

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import com.volty.app.domain.model.BmsData
import com.volty.app.domain.model.Chemistry
import com.volty.app.domain.model.ConnectionState
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

interface DebugComponent {
    val state: StateFlow<State>

    fun onScanClicked()
    fun onConnect(device: DiscoveredDevice)
    fun onDisconnect()

    data class State(
        val devices: List<DiscoveredDevice> = emptyList(),
        val connection: ConnectionState = ConnectionState.Idle,
        val data: BmsData = BmsData(),
        val isScanning: Boolean = false
    )
}

@OptIn(ExperimentalTime::class)
class DefaultDebugComponent(
    componentContext: ComponentContext,
    private val bmsRepository: BmsRepository,
    private val vehicleRepository: VehicleRepository
) : DebugComponent, ComponentContext by componentContext {

    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val _state = MutableStateFlow(DebugComponent.State())
    override val state: StateFlow<DebugComponent.State> = _state.asStateFlow()

    private var scanJob: Job? = null

    init {
        scope.launch {
            bmsRepository.connectionState.collect { c ->
                _state.update { it.copy(connection = c) }
            }
        }
        scope.launch {
            bmsRepository.activeData.collect { d ->
                _state.update { it.copy(data = d) }
            }
        }
        lifecycle.doOnDestroy { scope.coroutineContext[Job]?.cancel() }
    }

    override fun onScanClicked() {
        if (scanJob?.isActive == true) {
            scanJob?.cancel()
            _state.update { it.copy(isScanning = false) }
            return
        }
        _state.update { it.copy(devices = emptyList(), isScanning = true) }
        scanJob = scope.launch {
            bmsRepository.scanAll().collect { d ->
                _state.update { s ->
                    val merged = (s.devices.filterNot { it.address == d.address } + d)
                        .sortedByDescending { it.rssi }
                    s.copy(devices = merged)
                }
            }
        }
    }

    override fun onConnect(device: DiscoveredDevice) {
        scope.launch {
            scanJob?.cancel()
            _state.update { it.copy(isScanning = false) }
            val v = Vehicle(
                id = "auto-${Random.nextLong()}",
                name = device.name ?: "BMS-${device.address.takeLast(4)}",
                iconKey = "generic",
                bmsType = device.bmsType,
                bmsAddress = device.address,
                chemistry = Chemistry.LI_ION_NMC,
                createdAt = Clock.System.now()
            )
            vehicleRepository.upsert(v)
            bmsRepository.connect(v)
        }
    }

    override fun onDisconnect() {
        scope.launch { bmsRepository.disconnect() }
    }
}
