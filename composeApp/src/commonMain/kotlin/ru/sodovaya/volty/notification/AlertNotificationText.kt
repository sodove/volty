package ru.sodovaya.volty.notification

import ru.sodovaya.volty.domain.usecase.AlertKind
import ru.sodovaya.volty.domain.usecase.AlertMessage
import ru.sodovaya.volty.util.UnitFormatter
import ru.sodovaya.volty.util.UnitSystem
import ru.sodovaya.volty.util.formatFixed
import kotlin.math.roundToInt

/**
 * Every string an alert notification can say. The platform maps each to a localised resource
 * (`R.string.alert_*` on Android, in `values/` and `values-ru/`); the formatter below is the
 * pure half and only ever names strings, so it is testable with any lookup.
 *
 * Templates take positional arguments (`%1$s`), documented per entry.
 */
enum class AlertString(val resourceName: String) {
    // Arguments: voltage, vehicle
    CELL_HIGH_TITLE("alert_cell_high_title"), CELL_HIGH_TEXT("alert_cell_high_text"),
    CELL_LOW_TITLE("alert_cell_low_title"), CELL_LOW_TEXT("alert_cell_low_text"),
    // Arguments: millivolts, vehicle
    CELL_DELTA_TITLE("alert_cell_delta_title"), CELL_DELTA_TEXT("alert_cell_delta_text"),
    // Arguments: degrees, vehicle
    TEMPERATURE_WARN_TITLE("alert_temperature_warn_title"), TEMPERATURE_WARN_TEXT("alert_temperature_warn_text"),
    TEMPERATURE_HIGH_TITLE("alert_temperature_high_title"), TEMPERATURE_HIGH_TEXT("alert_temperature_high_text"),
    MOTOR_TEMP_TITLE("alert_motor_temp_title"), MOTOR_TEMP_TEXT("alert_motor_temp_text"),
    ESC_TEMP_TITLE("alert_esc_temp_title"), ESC_TEMP_TEXT("alert_esc_temp_text"),
    // Arguments: percent, vehicle
    SOC_LOW_TITLE("alert_soc_low_title"), SOC_LOW_TEXT("alert_soc_low_text"),
    SOC_CUTOFF_TITLE("alert_soc_cutoff_title"), SOC_CUTOFF_TEXT("alert_soc_cutoff_text"),
    DUTY_TITLE("alert_duty_title"), DUTY_TEXT("alert_duty_text"),
    // Arguments: vehicle
    CHARGE_COMPLETE_TITLE("alert_charge_complete_title"), CHARGE_COMPLETE_TEXT("alert_charge_complete_text"),
    DISCONNECT_TITLE("alert_disconnect_title"), DISCONNECT_TEXT("alert_disconnect_text"),
    // Arguments: fault list, vehicle
    CONTROLLER_FAULT_TITLE("alert_controller_fault_title"), CONTROLLER_FAULT_TEXT("alert_controller_fault_text"),
    BMS_FAULT_TITLE("alert_bms_fault_title"), BMS_FAULT_TEXT("alert_bms_fault_text"),
    // Arguments: speed, speed unit, vehicle
    SPEED_TITLE("alert_speed_title"), SPEED_TEXT("alert_speed_text"),
    // Speed units, passed as an argument to SPEED_TEXT
    UNIT_KMH("alert_unit_kmh"), UNIT_MPH("alert_unit_mph")
}

/** Looks up a localised template and fills it with [args] (positional, `%1$s` style). */
fun interface AlertStrings {
    fun get(string: AlertString, vararg args: String): String
}

data class AlertNotificationText(val title: String, val text: String)

/**
 * Turns the engine's structured [AlertMessage] into words (rider-approved decision 7).
 *
 * Pure: the only platform input is [AlertStrings], so both locales are tested against the real
 * resource files (`AlertNotificationTextResourcesTest`) and the engine tests render through a
 * stub.
 *
 * **Units.** Speed arrives in km/h and is shown in [units] — the notifier reads the rider's unit
 * preference when it formats. Temperatures stay °C and voltages V, as everywhere else in the app
 * (the unit system covers speed and distance only).
 */
object AlertNotificationFormatter {

    fun format(message: AlertMessage, units: UnitSystem, strings: AlertStrings): AlertNotificationText {
        val name = message.vehicleName
        val value = message.value ?: 0f
        fun pair(title: AlertString, text: AlertString, vararg args: String) =
            AlertNotificationText(strings.get(title), strings.get(text, *args))
        return when (message.kind) {
            AlertKind.CELL_HIGH -> pair(AlertString.CELL_HIGH_TITLE, AlertString.CELL_HIGH_TEXT, formatFixed(value, 2), name)
            AlertKind.CELL_LOW -> pair(AlertString.CELL_LOW_TITLE, AlertString.CELL_LOW_TEXT, formatFixed(value, 2), name)
            AlertKind.CELL_DELTA -> pair(AlertString.CELL_DELTA_TITLE, AlertString.CELL_DELTA_TEXT, value.toInt().toString(), name)
            AlertKind.TEMPERATURE_WARN -> pair(AlertString.TEMPERATURE_WARN_TITLE, AlertString.TEMPERATURE_WARN_TEXT, value.toInt().toString(), name)
            AlertKind.TEMPERATURE_HIGH -> pair(AlertString.TEMPERATURE_HIGH_TITLE, AlertString.TEMPERATURE_HIGH_TEXT, value.toInt().toString(), name)
            AlertKind.MOTOR_TEMP_HIGH -> pair(AlertString.MOTOR_TEMP_TITLE, AlertString.MOTOR_TEMP_TEXT, value.toInt().toString(), name)
            AlertKind.ESC_TEMP_HIGH -> pair(AlertString.ESC_TEMP_TITLE, AlertString.ESC_TEMP_TEXT, value.toInt().toString(), name)
            AlertKind.SOC_LOW -> pair(AlertString.SOC_LOW_TITLE, AlertString.SOC_LOW_TEXT, value.toInt().toString(), name)
            AlertKind.SOC_CUTOFF -> pair(AlertString.SOC_CUTOFF_TITLE, AlertString.SOC_CUTOFF_TEXT, value.toInt().toString(), name)
            AlertKind.DUTY_HIGH -> pair(AlertString.DUTY_TITLE, AlertString.DUTY_TEXT, value.roundToInt().toString(), name)
            AlertKind.CHARGE_COMPLETE -> pair(AlertString.CHARGE_COMPLETE_TITLE, AlertString.CHARGE_COMPLETE_TEXT, name)
            AlertKind.DISCONNECT -> pair(AlertString.DISCONNECT_TITLE, AlertString.DISCONNECT_TEXT, name)
            AlertKind.CONTROLLER_FAULT -> pair(AlertString.CONTROLLER_FAULT_TITLE, AlertString.CONTROLLER_FAULT_TEXT, message.details.joinToString(", "), name)
            AlertKind.BMS_FAULT -> pair(AlertString.BMS_FAULT_TITLE, AlertString.BMS_FAULT_TEXT, message.details.joinToString(", "), name)
            AlertKind.SPEED_HIGH -> {
                val unit = strings.get(if (units == UnitSystem.IMPERIAL) AlertString.UNIT_MPH else AlertString.UNIT_KMH)
                pair(AlertString.SPEED_TITLE, AlertString.SPEED_TEXT, UnitFormatter.speed(value, units), unit, name)
            }
        }
    }
}
