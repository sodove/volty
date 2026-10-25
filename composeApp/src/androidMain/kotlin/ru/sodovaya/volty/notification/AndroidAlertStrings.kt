package ru.sodovaya.volty.notification

import android.content.Context
import androidx.annotation.StringRes
import ru.sodovaya.volty.R

/**
 * The Android half of alert localisation: [AlertString] -> `res/values{,-ru}/alert_notifications.xml`.
 *
 * An exhaustive `when` rather than `getIdentifier(name)`, so a string can neither be added without a
 * resource nor be stripped by resource shrinking for want of a static reference. `internal` and
 * [resId] split out so the unit test can walk the same mapping against the XML.
 */
class AndroidAlertStrings(private val context: Context) : AlertStrings {

    override fun get(string: AlertString, vararg args: String): String =
        context.getString(resId(string), *args)

    companion object {
        @StringRes
        fun resId(string: AlertString): Int = when (string) {
            AlertString.CELL_HIGH_TITLE -> R.string.alert_cell_high_title
            AlertString.CELL_HIGH_TEXT -> R.string.alert_cell_high_text
            AlertString.CELL_LOW_TITLE -> R.string.alert_cell_low_title
            AlertString.CELL_LOW_TEXT -> R.string.alert_cell_low_text
            AlertString.CELL_DELTA_TITLE -> R.string.alert_cell_delta_title
            AlertString.CELL_DELTA_TEXT -> R.string.alert_cell_delta_text
            AlertString.TEMPERATURE_WARN_TITLE -> R.string.alert_temperature_warn_title
            AlertString.TEMPERATURE_WARN_TEXT -> R.string.alert_temperature_warn_text
            AlertString.TEMPERATURE_HIGH_TITLE -> R.string.alert_temperature_high_title
            AlertString.TEMPERATURE_HIGH_TEXT -> R.string.alert_temperature_high_text
            AlertString.MOTOR_TEMP_TITLE -> R.string.alert_motor_temp_title
            AlertString.MOTOR_TEMP_TEXT -> R.string.alert_motor_temp_text
            AlertString.ESC_TEMP_TITLE -> R.string.alert_esc_temp_title
            AlertString.ESC_TEMP_TEXT -> R.string.alert_esc_temp_text
            AlertString.SOC_LOW_TITLE -> R.string.alert_soc_low_title
            AlertString.SOC_LOW_TEXT -> R.string.alert_soc_low_text
            AlertString.SOC_CUTOFF_TITLE -> R.string.alert_soc_cutoff_title
            AlertString.SOC_CUTOFF_TEXT -> R.string.alert_soc_cutoff_text
            AlertString.DUTY_TITLE -> R.string.alert_duty_title
            AlertString.DUTY_TEXT -> R.string.alert_duty_text
            AlertString.CHARGE_COMPLETE_TITLE -> R.string.alert_charge_complete_title
            AlertString.CHARGE_COMPLETE_TEXT -> R.string.alert_charge_complete_text
            AlertString.DISCONNECT_TITLE -> R.string.alert_disconnect_title
            AlertString.DISCONNECT_TEXT -> R.string.alert_disconnect_text
            AlertString.CONTROLLER_FAULT_TITLE -> R.string.alert_controller_fault_title
            AlertString.CONTROLLER_FAULT_TEXT -> R.string.alert_controller_fault_text
            AlertString.BMS_FAULT_TITLE -> R.string.alert_bms_fault_title
            AlertString.BMS_FAULT_TEXT -> R.string.alert_bms_fault_text
            AlertString.SPEED_TITLE -> R.string.alert_speed_title
            AlertString.SPEED_TEXT -> R.string.alert_speed_text
            AlertString.UNIT_KMH -> R.string.alert_unit_kmh
            AlertString.UNIT_MPH -> R.string.alert_unit_mph
        }
    }
}
