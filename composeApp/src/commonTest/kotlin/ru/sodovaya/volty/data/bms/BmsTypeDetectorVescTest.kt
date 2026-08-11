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

    @Test fun kelly_kls_style_names_are_kelly_candidates_without_the_service_uuid() {
        listOf("Kelly Controller", "KLS-7240", "KBLS-8080").forEach { name ->
            assertEquals(ControllerType.KELLY, BmsTypeDetector.detectController(name, emptyList()), name)
        }
    }

    @Test fun generic_nordic_uart_devices_remain_vesc_candidates_not_kelly() {
        assertEquals(ControllerType.VESC, BmsTypeDetector.detectController("NUS", nus))
    }

    @Test fun wheel_family_names_select_the_matching_read_only_protocol() {
        assertEquals(ControllerType.NINEBOT, BmsTypeDetector.detectController("Ninebot Z10", nus))
        assertEquals(
            ControllerType.NINEBOT_LEGACY,
            BmsTypeDetector.detectController("Ninebot One", listOf("0000ffe0-0000-1000-8000-00805f9b34fb"))
        )
        assertEquals(ControllerType.KINGSONG, BmsTypeDetector.detectController("KS-16X", emptyList()))
        assertEquals(ControllerType.INMOTION, BmsTypeDetector.detectController("InMotion V12", emptyList()))
        assertEquals(ControllerType.VETERAN, BmsTypeDetector.detectController("Veteran Lynx", emptyList()))
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

    @Test fun a_vesc_retrofit_renamed_into_a_begode_name_is_never_double_classified() {
        // "GW-VESC": a real retrofit — a VESC controller wired into a Begode
        // wheel, renamed by the user. "GW" matches the Begode name prefix AND
        // the string contains "VESC", so this device must resolve as a BMS
        // (the wheel's own Begode identity) and NOT also as a controller —
        // the known-BMS bail-out in detectController must win first.
        assertEquals(BmsType.BEGODE, BmsTypeDetector.detect("GW-VESC", emptyList()))
        assertNull(BmsTypeDetector.detectController("GW-VESC", emptyList()))
    }

    @Test fun a_known_battery_name_wins_over_a_kelly_candidate() {
        assertEquals(BmsType.ANT_BMS, BmsTypeDetector.detect("ANT-Kelly", emptyList()))
        assertNull(BmsTypeDetector.detectController("ANT-Kelly", emptyList()))
    }
}
