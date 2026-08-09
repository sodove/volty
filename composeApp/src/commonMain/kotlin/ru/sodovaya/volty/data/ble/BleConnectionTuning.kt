package ru.sodovaya.volty.data.ble

import com.juul.kable.Peripheral

/** The Android connection interval profile used while the dashboard is visible. */
internal enum class BleConnectionPriority { BALANCED, HIGH }

internal object BleConnectionTuning {
    fun priorityFor(foreground: Boolean): BleConnectionPriority =
        if (foreground) BleConnectionPriority.HIGH else BleConnectionPriority.BALANCED
}

/**
 * Best-effort Android transport hint. Platforms that do not expose this
 * control simply return false; a refusal must never affect the protocol loop.
 */
internal expect fun requestBleConnectionPriority(
    peripheral: Peripheral,
    priority: BleConnectionPriority,
): Boolean
