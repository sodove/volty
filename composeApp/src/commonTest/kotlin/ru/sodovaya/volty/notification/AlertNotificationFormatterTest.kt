package ru.sodovaya.volty.notification

import ru.sodovaya.volty.domain.usecase.AlertKind
import ru.sodovaya.volty.domain.usecase.AlertMessage
import ru.sodovaya.volty.util.UnitSystem
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The pure half of notification localisation. Only English is exercised here, through a stub
 * lookup; the real `values/` and `values-ru/` resources are driven through this same formatter by
 * `AlertNotificationTextResourcesTest` in androidUnitTest.
 */
class AlertNotificationFormatterTest {

    private fun text(message: AlertMessage, units: UnitSystem = UnitSystem.METRIC) =
        AlertNotificationFormatter.format(message, units, TestAlertStrings)

    private fun assertText(title: String, body: String, message: AlertMessage, units: UnitSystem = UnitSystem.METRIC) {
        val t = text(message, units)
        assertEquals(title, t.title)
        assertEquals(body, t.text)
    }

    @Test
    fun `the stub table names every string the formatter can ask for`() {
        assertEquals(AlertString.entries.toSet(), TestAlertStrings.ENGLISH.keys)
    }

    @Test
    fun `every alert kind has a formatting`() {
        // Exhaustive by construction: a new AlertKind that the formatter does not handle fails to
        // compile there; this asserts none of them renders blank or throws.
        for (kind in AlertKind.entries) {
            val t = text(AlertMessage(kind, "Bike", value = 42f, details = listOf("F")))
            assertEquals(false, t.title.isBlank(), "$kind title")
            assertEquals(false, t.text.isBlank(), "$kind text")
        }
    }

    @Test
    fun `cell high`() = assertText(
        "Cell voltage high", "Max cell 4.25 V on Bike",
        AlertMessage(AlertKind.CELL_HIGH, "Bike", value = 4.25f)
    )

    @Test
    fun `cell low`() = assertText(
        "Cell voltage low", "Min cell 2.80 V on Bike",
        AlertMessage(AlertKind.CELL_LOW, "Bike", value = 2.8f)
    )

    @Test
    fun `cell delta`() = assertText(
        "Cell imbalance", "Δ 250 mV on Bike",
        AlertMessage(AlertKind.CELL_DELTA, "Bike", value = 250f)
    )

    @Test
    fun `temperature warning and high`() {
        assertText(
            "Temperature warning", "52°C on Bike",
            AlertMessage(AlertKind.TEMPERATURE_WARN, "Bike", value = 52.9f)
        )
        assertText(
            "Temperature high", "61°C on Bike",
            AlertMessage(AlertKind.TEMPERATURE_HIGH, "Bike", value = 61f)
        )
    }

    @Test
    fun `motor and controller temperature`() {
        assertText(
            "Motor temperature high", "Motor 118°C on Bike",
            AlertMessage(AlertKind.MOTOR_TEMP_HIGH, "Bike", value = 118f)
        )
        assertText(
            "Controller temperature high", "ESC 95°C on Bike",
            AlertMessage(AlertKind.ESC_TEMP_HIGH, "Bike", value = 95f)
        )
    }

    @Test
    fun `battery soc kinds`() {
        assertText("Battery low", "14% on Bike", AlertMessage(AlertKind.SOC_LOW, "Bike", value = 14.7f))
        assertText(
            "Discharge cutoff", "8% — stop now (Bike)",
            AlertMessage(AlertKind.SOC_CUTOFF, "Bike", value = 8f)
        )
        assertText("Charge complete", "Bike reached 100%", AlertMessage(AlertKind.CHARGE_COMPLETE, "Bike"))
    }

    @Test
    fun `faults list every fault`() {
        assertText(
            "Controller fault", "ESC0: OVER_TEMP_FET, ESC1: DRV on Bike",
            AlertMessage(AlertKind.CONTROLLER_FAULT, "Bike", details = listOf("ESC0: OVER_TEMP_FET", "ESC1: DRV"))
        )
        assertText(
            "Battery fault", "discharge disabled, cell OVP on Bike",
            AlertMessage(AlertKind.BMS_FAULT, "Bike", details = listOf("discharge disabled", "cell OVP"))
        )
    }

    @Test
    fun `duty`() = assertText(
        "Duty high", "Duty 87% on Bike",
        AlertMessage(AlertKind.DUTY_HIGH, "Bike", value = 86.6f)
    )

    @Test
    fun `speed is shown in the riders units`() {
        val message = AlertMessage(AlertKind.SPEED_HIGH, "Bike", value = 48.28f)
        assertText("Speed high", "48 km/h on Bike", message, UnitSystem.METRIC)
        assertText("Speed high", "30 mph on Bike", message, UnitSystem.IMPERIAL)
    }
}
