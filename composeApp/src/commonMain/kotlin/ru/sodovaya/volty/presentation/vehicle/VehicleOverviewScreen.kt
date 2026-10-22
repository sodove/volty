package ru.sodovaya.volty.presentation.vehicle

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import ru.sodovaya.volty.domain.history.HistoryUnit
import ru.sodovaya.volty.presentation.history.HistoryTotalsRow
import ru.sodovaya.volty.presentation.history.historyRideDate
import ru.sodovaya.volty.presentation.history.historyUnitText
import ru.sodovaya.volty.util.formatFixed
import volty.composeapp.generated.resources.*

/** Renders [VehicleOverviewState]; every number here is a recorded one, labelled with its time. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VehicleOverviewScreen(component: VehicleOverviewComponent) {
    val state by component.state.collectAsState()
    Scaffold(topBar = {
        TopAppBar(
            title = {
                Column {
                    Text(state.vehicle?.name ?: "", style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(Res.string.vehicle_overview_offline), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            navigationIcon = { IconButton(onClick = component::onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } },
        )
    }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = component::onConnect) { Text(stringResource(Res.string.vehicle_overview_connect)) }
                    OutlinedButton(onClick = component::onEdit) { Text(stringResource(Res.string.vehicle_overview_settings)) }
                    OutlinedButton(onClick = component::onAlerts) { Text(stringResource(Res.string.vehicle_overview_alerts)) }
                }
            }
            if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (state.error) item {
                Text(stringResource(Res.string.vehicle_overview_error), color = MaterialTheme.colorScheme.error)
            }
            items(state.battery, key = { "battery-" + it.label }) { snapshot ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val title = stringResource(Res.string.vehicle_overview_battery, historyRideDate(snapshot.atEpochMillis))
                        Text(if (state.battery.size > 1) "${snapshot.label} · $title" else title, style = MaterialTheme.typography.titleSmall)
                        @OptIn(ExperimentalLayoutApi::class)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            snapshot.readings.forEach { reading -> Reading(reading) }
                        }
                    }
                }
            }
            state.lastRide?.let { ride ->
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(Res.string.vehicle_overview_last_ride, historyRideDate(ride.started.epochMillis)),
                            style = MaterialTheme.typography.titleSmall)
                        HistoryTotalsRow(state.totals, Modifier.fillMaxWidth())
                    }
                }
            }
            if (!state.loading && !state.error && state.rides.isEmpty()) item {
                Text(stringResource(Res.string.vehicle_overview_no_rides), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state.rides.isNotEmpty()) item {
                Text(stringResource(Res.string.vehicle_overview_rides), style = MaterialTheme.typography.titleSmall)
            }
            items(state.rides, key = { "ride-" + it.id }) { ride ->
                Row(Modifier.fillMaxWidth().clickable { component.onOpenRide(ride.id) }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(historyRideDate(ride.started.epochMillis), Modifier.weight(1f))
                    ride.ended?.let { ended ->
                        val minutes = ((ended.epochMillis - ride.started.epochMillis) / 60_000).toInt()
                        Text(if (minutes >= 60) stringResource(Res.string.history_duration_hours, minutes / 60, minutes % 60)
                            else stringResource(Res.string.history_duration_minutes, minutes),
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun Reading(reading: VehicleReading) {
    Column(Modifier.widthIn(min = 80.dp)) {
        Text(stringResource(when (reading.metricId) {
            "soc_percent" -> Res.string.vehicle_reading_soc
            "voltage_v" -> Res.string.vehicle_reading_voltage
            "cell_min_v" -> Res.string.vehicle_reading_cell_min
            "cell_max_v" -> Res.string.vehicle_reading_cell_max
            "cell_delta_mv" -> Res.string.vehicle_reading_cell_delta
            else -> Res.string.vehicle_reading_temperature
        }), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val decimals = when (reading.unit) {
            HistoryUnit.VOLT -> if (reading.metricId.startsWith("cell_")) 3 else 1
            else -> 0
        }
        Text("${formatFixed(reading.value.toFloat(), decimals)} ${historyUnitText(reading.unit)}", style = MaterialTheme.typography.titleMedium)
    }
}
