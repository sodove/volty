package ru.sodovaya.volty.notification

/**
 * The English wording the engine used to build inline, kept as a test-side table so the engine's
 * long-standing assertions ("Controller fault", "ESC 95°C on Test") still say what a notification
 * means. The real resources are checked against the same wording, in both locales, by
 * `AlertNotificationTextResourcesTest` (androidUnitTest).
 *
 * Templates use Android's positional `%1$s`; `%%` is a literal percent sign.
 */
object TestAlertStrings : AlertStrings {

    val ENGLISH: Map<AlertString, String> = mapOf(
        AlertString.CELL_HIGH_TITLE to "Cell voltage high",
        AlertString.CELL_HIGH_TEXT to "Max cell %1\$s V on %2\$s",
        AlertString.CELL_LOW_TITLE to "Cell voltage low",
        AlertString.CELL_LOW_TEXT to "Min cell %1\$s V on %2\$s",
        AlertString.CELL_DELTA_TITLE to "Cell imbalance",
        AlertString.CELL_DELTA_TEXT to "Δ %1\$s mV on %2\$s",
        AlertString.TEMPERATURE_WARN_TITLE to "Temperature warning",
        AlertString.TEMPERATURE_WARN_TEXT to "%1\$s°C on %2\$s",
        AlertString.TEMPERATURE_HIGH_TITLE to "Temperature high",
        AlertString.TEMPERATURE_HIGH_TEXT to "%1\$s°C on %2\$s",
        AlertString.MOTOR_TEMP_TITLE to "Motor temperature high",
        AlertString.MOTOR_TEMP_TEXT to "Motor %1\$s°C on %2\$s",
        AlertString.ESC_TEMP_TITLE to "Controller temperature high",
        AlertString.ESC_TEMP_TEXT to "ESC %1\$s°C on %2\$s",
        AlertString.SOC_LOW_TITLE to "Battery low",
        AlertString.SOC_LOW_TEXT to "%1\$s%% on %2\$s",
        AlertString.SOC_CUTOFF_TITLE to "Discharge cutoff",
        AlertString.SOC_CUTOFF_TEXT to "%1\$s%% — stop now (%2\$s)",
        AlertString.DUTY_TITLE to "Duty high",
        AlertString.DUTY_TEXT to "Duty %1\$s%% on %2\$s",
        AlertString.CHARGE_COMPLETE_TITLE to "Charge complete",
        AlertString.CHARGE_COMPLETE_TEXT to "%1\$s reached 100%%",
        AlertString.DISCONNECT_TITLE to "Disconnected",
        AlertString.DISCONNECT_TEXT to "%1\$s disconnected",
        AlertString.CONTROLLER_FAULT_TITLE to "Controller fault",
        AlertString.CONTROLLER_FAULT_TEXT to "%1\$s on %2\$s",
        AlertString.BMS_FAULT_TITLE to "Battery fault",
        AlertString.BMS_FAULT_TEXT to "%1\$s on %2\$s",
        AlertString.SPEED_TITLE to "Speed high",
        AlertString.SPEED_TEXT to "%1\$s %2\$s on %3\$s",
        AlertString.UNIT_KMH to "km/h",
        AlertString.UNIT_MPH to "mph"
    )

    override fun get(string: AlertString, vararg args: String): String =
        substitute(ENGLISH.getValue(string), args)

    /** `%n$s` positional substitution and `%%`, which is all the templates use. */
    fun substitute(template: String, args: Array<out String>): String {
        val out = StringBuilder()
        var i = 0
        while (i < template.length) {
            val c = template[i]
            if (c == '%' && i + 1 < template.length) {
                if (template[i + 1] == '%') { out.append('%'); i += 2; continue }
                val m = Regex("^%([0-9])[$]s").find(template.substring(i))
                if (m != null) {
                    out.append(args[m.groupValues[1].toInt() - 1])
                    i += m.value.length
                    continue
                }
            }
            out.append(c); i++
        }
        return out.toString()
    }
}
