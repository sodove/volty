package ru.sodovaya.volty.presentation.history

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.stringResource
import ru.sodovaya.volty.util.formatFixed
import volty.composeapp.generated.resources.*

/**
 * Moving time stays in the expanded view. The scrollable strip keeps the average, top speed
 * and peak PWM available alongside distance, time, consumption and battery.
 */
internal fun historyCollapsedTotals(totals: List<HistoryTotal>): List<HistoryTotal> =
    COLLAPSED_ORDER.mapNotNull { kind -> totals.firstOrNull { it.kind == kind } }

/** The expanded summary: every tile, moving time after the headline ones it qualifies. */
internal fun historyExpandedTotals(totals: List<HistoryTotal>): List<HistoryTotal> {
    val headline = historyCollapsedTotals(totals)
    val moving = totals.filter { it.kind == HistoryTotalKind.MOVING_MILLIS }
    return headline + moving + totals.filter { it !in headline && it.kind != HistoryTotalKind.MOVING_MILLIS }
}

private val COLLAPSED_ORDER = listOf(
    HistoryTotalKind.DISTANCE_KM, HistoryTotalKind.DURATION_MILLIS, HistoryTotalKind.AVERAGE_KMH,
    HistoryTotalKind.MAX_KMH, HistoryTotalKind.MAX_DUTY_PERCENT, HistoryTotalKind.WH_PER_KM,
    HistoryTotalKind.BATTERY_USED_PERCENT,
)

internal fun HistoryTotalKind.title() = when (this) {
    HistoryTotalKind.DISTANCE_KM -> Res.string.history_total_distance
    HistoryTotalKind.DURATION_MILLIS -> Res.string.history_total_duration
    HistoryTotalKind.MOVING_MILLIS -> Res.string.history_total_moving
    HistoryTotalKind.AVERAGE_KMH -> Res.string.history_total_average
    HistoryTotalKind.MAX_KMH -> Res.string.history_total_max_speed
    HistoryTotalKind.ENERGY_WH -> Res.string.history_total_energy
    HistoryTotalKind.WH_PER_KM -> Res.string.history_total_consumption
    HistoryTotalKind.BATTERY_USED_PERCENT -> Res.string.history_total_battery
    HistoryTotalKind.MAX_POWER_W -> Res.string.history_total_power
    HistoryTotalKind.MOTOR_TEMP_C -> Res.string.history_total_motor_temp
    HistoryTotalKind.CONTROLLER_TEMP_C -> Res.string.history_total_controller_temp
    HistoryTotalKind.BATTERY_TEMP_C -> Res.string.history_total_battery_temp
    HistoryTotalKind.MAX_DUTY_PERCENT -> Res.string.history_total_max_duty
}

@Composable
internal fun HistoryTotal.text(): String {
    val v = value.toFloat()
    return when (kind) {
        HistoryTotalKind.DISTANCE_KM -> "${formatFixed(v, if (value < 100) 1 else 0)} ${stringResource(Res.string.history_unit_km)}"
        HistoryTotalKind.DURATION_MILLIS, HistoryTotalKind.MOVING_MILLIS -> {
            val minutes = (value / 60_000).toLong()
            if (minutes >= 60) stringResource(Res.string.history_duration_hours, (minutes / 60).toInt(), (minutes % 60).toInt())
            else stringResource(Res.string.history_duration_minutes, minutes.toInt())
        }
        HistoryTotalKind.AVERAGE_KMH, HistoryTotalKind.MAX_KMH -> "${formatFixed(v, 1)} ${stringResource(Res.string.unit_kmh)}" +
            // Review M6: a top speed from the phone's GPS says so ([HistoryTotal.gps]).
            (if (gps) " · " + stringResource(Res.string.ride_speed_gps_tag) else "")
        HistoryTotalKind.ENERGY_WH -> "${formatFixed(v, 0)} ${stringResource(Res.string.history_unit_wh)}"
        HistoryTotalKind.WH_PER_KM -> "${formatFixed(v, 1)} ${stringResource(Res.string.history_unit_wh_per_km)}"
        HistoryTotalKind.BATTERY_USED_PERCENT -> "${historyBatteryUsedSign(value)}${historyBatteryUsedDigit(value)} ${stringResource(Res.string.unit_percent)}"
        HistoryTotalKind.MAX_DUTY_PERCENT -> "${formatFixed(v, 0)} ${stringResource(Res.string.unit_percent)}"
        HistoryTotalKind.MAX_POWER_W -> "${formatFixed(v, 0)} ${stringResource(Res.string.history_unit_watt)}"
        HistoryTotalKind.MOTOR_TEMP_C, HistoryTotalKind.CONTROLLER_TEMP_C, HistoryTotalKind.BATTERY_TEMP_C ->
            "${formatFixed(v, 0)} ${stringResource(Res.string.unit_celsius)}"
    }
}
