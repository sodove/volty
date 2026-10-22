package ru.sodovaya.volty.presentation.history

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import ru.sodovaya.volty.util.formatFixed
import volty.composeapp.generated.resources.*

/** Whole-ride headline tiles; renders what [historyRideTotals] decided and nothing while empty. */
@Composable
fun HistoryTotalsRow(state: HistoryTotalsState, modifier: Modifier = Modifier) {
    if (state.totals.isEmpty()) return
    LazyRow(modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(state.totals, key = { it.kind }) { total ->
            Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                    Text(stringResource(total.kind.title()), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text((if (total.approximate) "≈ " else "") + total.text(), style = MaterialTheme.typography.titleSmall)
                }
            }
        }
    }
}

private fun HistoryTotalKind.title() = when (this) {
    HistoryTotalKind.DISTANCE_KM -> Res.string.history_total_distance
    HistoryTotalKind.DURATION_MILLIS -> Res.string.history_total_duration
    HistoryTotalKind.AVERAGE_KMH -> Res.string.history_total_average
    HistoryTotalKind.MAX_KMH -> Res.string.history_total_max_speed
    HistoryTotalKind.ENERGY_WH -> Res.string.history_total_energy
    HistoryTotalKind.WH_PER_KM -> Res.string.history_total_consumption
    HistoryTotalKind.BATTERY_USED_PERCENT -> Res.string.history_total_battery
    HistoryTotalKind.MAX_POWER_W -> Res.string.history_total_power
    HistoryTotalKind.MOTOR_TEMP_C -> Res.string.history_total_motor_temp
    HistoryTotalKind.CONTROLLER_TEMP_C -> Res.string.history_total_controller_temp
    HistoryTotalKind.BATTERY_TEMP_C -> Res.string.history_total_battery_temp
}

@Composable
private fun HistoryTotal.text(): String {
    val v = value.toFloat()
    return when (kind) {
        HistoryTotalKind.DISTANCE_KM -> "${formatFixed(v, if (value < 100) 1 else 0)} ${stringResource(Res.string.history_unit_km)}"
        HistoryTotalKind.DURATION_MILLIS -> {
            val minutes = (value / 60_000).toLong()
            if (minutes >= 60) stringResource(Res.string.history_duration_hours, (minutes / 60).toInt(), (minutes % 60).toInt())
            else stringResource(Res.string.history_duration_minutes, minutes.toInt())
        }
        HistoryTotalKind.AVERAGE_KMH, HistoryTotalKind.MAX_KMH -> "${formatFixed(v, 1)} ${stringResource(Res.string.unit_kmh)}"
        HistoryTotalKind.ENERGY_WH -> "${formatFixed(v, 0)} ${stringResource(Res.string.history_unit_wh)}"
        HistoryTotalKind.WH_PER_KM -> "${formatFixed(v, 1)} ${stringResource(Res.string.history_unit_wh_per_km)}"
        HistoryTotalKind.BATTERY_USED_PERCENT -> "−${formatFixed(v, 0)} ${stringResource(Res.string.unit_percent)}"
        HistoryTotalKind.MAX_POWER_W -> "${formatFixed(v, 0)} ${stringResource(Res.string.history_unit_watt)}"
        HistoryTotalKind.MOTOR_TEMP_C, HistoryTotalKind.CONTROLLER_TEMP_C, HistoryTotalKind.BATTERY_TEMP_C ->
            "${formatFixed(v, 0)} ${stringResource(Res.string.unit_celsius)}"
    }
}
