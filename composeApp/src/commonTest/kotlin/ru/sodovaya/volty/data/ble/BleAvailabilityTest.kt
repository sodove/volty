package ru.sodovaya.volty.data.ble

import kotlin.test.Test
import kotlin.test.assertEquals

class BleAvailabilityTest {
    @Test
    fun bluetooth_off_blocks_ble_operations_before_kable_is_touched() {
        assertEquals(BleAvailability.BLUETOOTH_DISABLED, bleAvailability(false))
        assertEquals(BLUETOOTH_DISABLED_REASON, bleUnavailableReason(BleAvailability.BLUETOOTH_DISABLED))
    }

    @Test
    fun kable_state_error_is_presented_as_a_bluetooth_state_error() {
        val error = IllegalStateException("Bluetooth was STATE_OFF, but STATE_ON was required")

        assertEquals(BLUETOOTH_DISABLED_REASON, readableBleFailureReason(error))
    }
}
