package ru.sodovaya.volty.presentation.history

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.stringResource
import ru.sodovaya.volty.domain.history.*
import volty.composeapp.generated.resources.*

internal data class HistoryMetricText(
    val titles: Map<String, String>, val groups: Map<HistoryMetricGroup, String>,
    val kinds: Map<HistorySourceKind, String>, val unknown: String, val other: String,
) {
    fun title(key: HistorySeriesKey): String = (titles[key.metricId] ?: unknown) + (key.cellIndex?.let { " ${it.toLong() + 1}" } ?: "")
}

@Composable
internal fun historyMetricText() = HistoryMetricText(
    titles = mapOf(
        "soc_percent" to stringResource(Res.string.history_metric_soc),
        "power_w" to stringResource(Res.string.history_metric_power),
        "current_a" to stringResource(Res.string.history_metric_current),
        "voltage_v" to stringResource(Res.string.history_metric_voltage),
        "battery_temperature_c" to stringResource(Res.string.history_metric_battery_temp),
        "cell_min_v" to stringResource(Res.string.history_metric_cell_min),
        "cell_max_v" to stringResource(Res.string.history_metric_cell_max),
        "cell_delta_mv" to stringResource(Res.string.history_metric_cell_delta),
        "cell_v" to stringResource(Res.string.history_metric_cell),
        "speed_kmh" to stringResource(Res.string.history_metric_speed),
        "duty_percent" to stringResource(Res.string.history_metric_duty),
        "duty_estimated_percent" to stringResource(Res.string.history_metric_duty_estimated),
        "motor_current_a" to stringResource(Res.string.history_metric_motor_current),
        "battery_current_a" to stringResource(Res.string.history_metric_battery_current),
        "input_voltage_v" to stringResource(Res.string.history_metric_input_voltage),
        "controller_power_w" to stringResource(Res.string.history_metric_controller_power),
        "erpm" to stringResource(Res.string.history_metric_erpm),
        "esc_temperature_c" to stringResource(Res.string.history_metric_esc_temp),
        "motor_temperature_c" to stringResource(Res.string.history_metric_motor_temp),
        "odometer_km" to stringResource(Res.string.history_metric_odometer),
        "trip_distance_km" to stringResource(Res.string.history_metric_trip),
    ),
    groups = mapOf(HistoryMetricGroup.MOVEMENT to stringResource(Res.string.history_group_movement),
        HistoryMetricGroup.CONTROLLER to stringResource(Res.string.history_group_controller),
        HistoryMetricGroup.BATTERY to stringResource(Res.string.history_group_battery),
        HistoryMetricGroup.CELL to stringResource(Res.string.history_group_cell)),
    kinds = mapOf(HistorySourceKind.VEHICLE_BATTERY to stringResource(Res.string.history_source_vehicle_battery),
        HistorySourceKind.VEHICLE_MOTION to stringResource(Res.string.history_source_vehicle_motion),
        HistorySourceKind.BATTERY to stringResource(Res.string.history_source_battery),
        HistorySourceKind.CONTROLLER to stringResource(Res.string.history_source_controller),
        HistorySourceKind.BATTERY_ASSEMBLY to stringResource(Res.string.history_source_assembly),
        HistorySourceKind.DERIVED_BATTERY to stringResource(Res.string.history_source_derived),
        HistorySourceKind.REPORTED_CAN to stringResource(Res.string.history_source_reported_can)),
    unknown = stringResource(Res.string.history_metric_unknown), other = stringResource(Res.string.history_group_other),
)
