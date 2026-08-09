package ru.sodovaya.volty.data.ble

import kotlin.test.Test
import kotlin.test.assertEquals

class BleConnectionTuningTest {

    @Test
    fun `foreground connection asks for high priority`() {
        assertEquals(
            BleConnectionPriority.HIGH,
            BleConnectionTuning.priorityFor(foreground = true)
        )
    }

    @Test
    fun `background connection returns to balanced priority`() {
        assertEquals(
            BleConnectionPriority.BALANCED,
            BleConnectionTuning.priorityFor(foreground = false)
        )
    }
}
