package ru.sodovaya.volty.presentation.autoconnect

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import ru.sodovaya.volty.data.prefs.AppPrefs
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.repository.BmsRepository
import ru.sodovaya.volty.domain.repository.VehicleRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

interface AutoConnectComponent {
    val state: StateFlow<State>
    fun onConnectNow()
    fun onCancel()
    fun onRetry()

    data class State(
        val vehicle: Vehicle? = null,
        val countdownSec: Int = 3,
        val phase: Phase = Phase.Counting,
        val failure: String? = null
    )

    enum class Phase { Counting, Connecting, Connected, Failed }
}

class DefaultAutoConnectComponent(
    componentContext: ComponentContext,
    private val vehicleId: String,
    private val bmsRepository: BmsRepository,
    private val vehicleRepository: VehicleRepository,
    private val appPrefs: AppPrefs,
    private val onConnected: () -> Unit,
    private val onCancelled: () -> Unit
) : AutoConnectComponent, ComponentContext by componentContext {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val _state = MutableStateFlow(AutoConnectComponent.State())
    override val state: StateFlow<AutoConnectComponent.State> = _state.asStateFlow()

    private var countdownJob: Job? = null
    private var connectJob: Job? = null

    /** Countdown from prefs; [onRetry] restarts from this, not the decayed
     *  state value (which has already ticked down to 0). */
    private var configuredCountdownSec: Int = 3

    init {
        lifecycle.doOnDestroy { scope.coroutineContext[Job]?.cancel() }
        scope.launch { loadVehicleAndStart() }
    }

    private suspend fun loadVehicleAndStart() {
        val vehicle = vehicleRepository.get(vehicleId)
        if (vehicle == null) {
            _state.update { it.copy(phase = AutoConnectComponent.Phase.Failed, failure = "Vehicle not found") }
            return
        }
        val countdown = appPrefs.autoConnectCountdownSec.first()
        configuredCountdownSec = countdown
        _state.update { it.copy(vehicle = vehicle, countdownSec = countdown) }
        startCountdown(countdown)
    }

    private fun startCountdown(seconds: Int) {
        countdownJob?.cancel()
        countdownJob = scope.launch {
            for (s in seconds downTo 1) {
                _state.update { it.copy(countdownSec = s) }
                delay(1000)
            }
            _state.update { it.copy(countdownSec = 0) }
            performConnect()
        }
    }

    private fun performConnect() {
        val vehicle = _state.value.vehicle ?: return
        connectJob?.cancel()
        connectJob = scope.launch {
            _state.update { it.copy(phase = AutoConnectComponent.Phase.Connecting) }
            val result = bmsRepository.connect(vehicle)
            if (result.isSuccess) {
                _state.update { it.copy(phase = AutoConnectComponent.Phase.Connected) }
                onConnected()
            } else {
                _state.update {
                    it.copy(
                        phase = AutoConnectComponent.Phase.Failed,
                        failure = result.exceptionOrNull()?.message ?: "Connection failed"
                    )
                }
            }
        }
    }

    override fun onConnectNow() {
        countdownJob?.cancel()
        _state.update { it.copy(countdownSec = 0) }
        performConnect()
    }

    override fun onCancel() {
        countdownJob?.cancel()
        connectJob?.cancel()
        onCancelled()
    }

    override fun onRetry() {
        _state.update { it.copy(phase = AutoConnectComponent.Phase.Counting, failure = null) }
        startCountdown(configuredCountdownSec.coerceAtLeast(1))
    }
}
