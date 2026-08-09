package ru.sodovaya.volty.data.ble

import com.juul.kable.AndroidPeripheral
import com.juul.kable.Peripheral

internal actual fun requestBleConnectionPriority(
    peripheral: Peripheral,
    priority: BleConnectionPriority,
): Boolean = runCatching {
    (peripheral as? AndroidPeripheral)?.requestConnectionPriority(
        when (priority) {
            BleConnectionPriority.BALANCED -> AndroidPeripheral.Priority.Balanced
            BleConnectionPriority.HIGH -> AndroidPeripheral.Priority.High
        }
    ) ?: false
}.getOrDefault(false)
