package ru.sodovaya.volty.data.bms

import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.ControllerType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BmsTypeDetectorVescTest {

    private val nus = listOf("6e400001-b5a3-f393-e0a9-e50e24dcca9e")

    @Test fun nordic_uart_service_flags_a_vesc_candidate() {
        assertEquals(ControllerType.VESC, BmsTypeDetector.detectController(null, nus))
    }

    @Test fun vesc_style_names_are_detected_without_the_service_uuid() {
        assertEquals(ControllerType.VESC, BmsTypeDetector.detectController("VESC BLE UART", emptyList()))
        assertEquals(ControllerType.VESC, BmsTypeDetector.detectController("uBox-250", emptyList()))
    }

    @Test fun a_battery_is_not_reported_as_a_controller() {
        assertNull(BmsTypeDetector.detectController("ANT-BLE24", listOf("0000ffe0-0000-1000-8000-00805f9b34fb")))
    }

    @Test fun controller_detection_does_not_change_battery_detection() {
        // The NUS device must not become a BMS, and every existing battery
        // signal must still resolve exactly as before.
        assertNull(BmsTypeDetector.detect(null, nus))
        assertEquals(BmsType.JK_BMS, BmsTypeDetector.detect("JK_B2A8S20P", emptyList()))
        assertEquals(BmsType.ANT_BMS, BmsTypeDetector.detect("ANT-BLE24", emptyList()))
        assertEquals(BmsType.JBD_BMS, BmsTypeDetector.detect(null, listOf("0000ff00-0000-1000-8000-00805f9b34fb")))
    }
}
