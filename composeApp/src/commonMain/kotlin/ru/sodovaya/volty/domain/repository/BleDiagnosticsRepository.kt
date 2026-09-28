package ru.sodovaya.volty.domain.repository

import kotlinx.coroutines.flow.StateFlow
import ru.sodovaya.volty.domain.model.BleDiagnosticsState

/** Volatile, user-controlled BLE notification capture. */
interface BleDiagnosticsRepository {
    val state: StateFlow<BleDiagnosticsState>
    fun startCapture()
    fun stopCapture()
    fun clearCapture()
}
