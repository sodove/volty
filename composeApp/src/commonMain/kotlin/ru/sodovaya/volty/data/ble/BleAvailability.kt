package ru.sodovaya.volty.data.ble

/** Platform-owned BLE adapter state, kept injectable so repository policy is testable. */
fun interface BleAdapterStateProvider {
    fun isBluetoothEnabled(): Boolean
}

internal enum class BleAvailability {
    READY,
    BLUETOOTH_DISABLED,
}

internal const val BLUETOOTH_DISABLED_REASON = "Bluetooth is turned off"

internal fun bleAvailability(bluetoothEnabled: Boolean): BleAvailability =
    if (bluetoothEnabled) BleAvailability.READY else BleAvailability.BLUETOOTH_DISABLED

internal fun bleUnavailableReason(availability: BleAvailability): String? = when (availability) {
    BleAvailability.READY -> null
    BleAvailability.BLUETOOTH_DISABLED -> BLUETOOTH_DISABLED_REASON
}

/** Converts Kable's Android-specific state exception into a stable user-facing reason. */
internal fun readableBleFailureReason(error: Throwable): String =
    if (error.message.orEmpty().contains("Bluetooth", ignoreCase = true) &&
        error.message.orEmpty().contains("STATE_OFF", ignoreCase = true)
    ) {
        BLUETOOTH_DISABLED_REASON
    } else {
        error.message?.takeIf { it.isNotBlank() } ?: "Bluetooth connection failed"
    }
