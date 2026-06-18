package ru.sodovaya.volty.presentation.scanning

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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

interface ScanningComponent {
    val state: StateFlow<State>
    fun onSkipClicked()

    data class State(
        val secondsLeft: Int = 5,
        val knownInRange: List<Vehicle> = emptyList(),
        val savedCount: Int = 0
    )
}

class DefaultScanningComponent(
    componentContext: ComponentContext,
    private val bmsRepository: BmsRepository,
    private val vehicleRepository: VehicleRepository,
    private val appPrefs: AppPrefs,
    private val onSingleKnown: (vehicleId: String) -> Unit,
    private val onMultipleOrNone: () -> Unit
) : ScanningComponent, ComponentContext by componentContext {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val _state = MutableStateFlow(ScanningComponent.State())
    override val state: StateFlow<ScanningComponent.State> = _state.asStateFlow()

    private var timerJob: Job? = null
    private var scanJob: Job? = null

    /** One-shot guard: scan keeps emitting after we navigate away, and each
     *  repeated advertisement must not re-trigger navigation. */
    private var navigated = false

    init {
        lifecycle.doOnDestroy { scope.coroutineContext[Job]?.cancel() }
        scope.launch { runScan() }
    }

    private suspend fun runScan() {
        val timeoutSec = appPrefs.scanTimeoutSec.first()
        val saved = vehicleRepository.vehicles.first()
        val knownByAddress: Map<String, Vehicle> = saved.associateBy { it.bmsAddress }
        _state.update { it.copy(secondsLeft = timeoutSec, savedCount = saved.size) }

        timerJob = scope.launch {
            for (s in timeoutSec downTo 1) {
                _state.update { it.copy(secondsLeft = s) }
                delay(1000)
            }
            _state.update { it.copy(secondsLeft = 0) }
            scanJob?.cancel()
            handleScanCompleted()
        }

        scanJob = scope.launch {
            bmsRepository.scanAll().collect { dev ->
                val matched = knownByAddress[dev.address] ?: return@collect
                _state.update { s ->
                    if (s.knownInRange.any { it.id == matched.id }) s
                    else s.copy(knownInRange = s.knownInRange + matched)
                }
                if (_state.value.knownInRange.size >= 2) {
                    navigateOnce {
                        timerJob?.cancel()
                        scanJob?.cancel()
                        onMultipleOrNone()
                    }
                }
            }
        }
    }

    private fun handleScanCompleted() {
        val known = _state.value.knownInRange
        navigateOnce {
            when (known.size) {
                1 -> onSingleKnown(known.first().id)
                else -> onMultipleOrNone()
            }
        }
    }

    private inline fun navigateOnce(block: () -> Unit) {
        if (navigated) return
        navigated = true
        block()
    }

    override fun onSkipClicked() {
        timerJob?.cancel()
        scanJob?.cancel()
        navigateOnce { onMultipleOrNone() }
    }
}
