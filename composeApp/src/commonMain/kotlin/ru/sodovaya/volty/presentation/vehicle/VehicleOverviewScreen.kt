package ru.sodovaya.volty.presentation.vehicle

import ru.sodovaya.volty.presentation.common.LocalVoltyDarkTheme
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import org.jetbrains.compose.resources.stringResource
import ru.sodovaya.volty.domain.history.HistoryRide
import ru.sodovaya.volty.domain.history.HistoryUnit
import ru.sodovaya.volty.presentation.history.HistoryTotal
import ru.sodovaya.volty.presentation.history.HistoryTotalKind
import ru.sodovaya.volty.presentation.history.historyUnitText
import ru.sodovaya.volty.presentation.history.text
import ru.sodovaya.volty.util.formatFixed
import volty.composeapp.generated.resources.*

/** Renders [VehicleOverviewState]; every number here is a recorded one, labelled with its time. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalTime::class)
@Composable
fun VehicleOverviewScreen(component: VehicleOverviewComponent) {
    val state by component.state.collectAsState()
    val now = remember { Clock.System.now().toEpochMilliseconds() }
    val batteryGroups = remember(state.battery) { vehicleBatteryGroups(state.battery) }
    val asOf = state.battery.maxOfOrNull { it.atEpochMillis } ?: state.lastRide?.ended?.epochMillis
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(state.vehicle?.name ?: "", fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (asOf != null) stringResource(Res.string.vehicle_overview_offline_at, whenText(asOf, now))
                            else stringResource(Res.string.vehicle_overview_offline),
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = { IconButton(onClick = component::onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } },
                actions = {
                    IconButton(onClick = component::onAlerts) { Icon(Icons.Default.Notifications, stringResource(Res.string.vehicle_overview_alerts)) }
                    IconButton(onClick = component::onEdit) { Icon(Icons.Default.Settings, stringResource(Res.string.vehicle_overview_settings)) }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Button(onClick = component::onConnect, shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp, 10.dp).height(52.dp)) {
                    Text(stringResource(Res.string.vehicle_overview_connect), fontSize = 16.sp)
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.error) item { Text(stringResource(Res.string.vehicle_overview_error), color = MaterialTheme.colorScheme.error) }
                items(batteryGroups, key = { "battery-" + it.first().sourceId }) { group ->
                    BatteryCard(group, showLabel = batteryGroups.size > 1)
                }
                state.lastRide?.let { ride ->
                    item { SectionLabel(stringResource(Res.string.vehicle_overview_last_ride, whenText(ride.started.epochMillis, now))) }
                    item {
                        ElevatedCard(shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth().clickable { component.onOpenRide(ride.id) }) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                if (state.lastRoute.size >= 2) {
                                    RouteMapPreview(state.lastRoute, LocalVoltyDarkTheme.current, Modifier.fillMaxWidth().height(160.dp))
                                }
                                if (state.lastTotals.isNotEmpty()) TotalsTiles(state.lastTotals)
                            }
                        }
                    }
                }
                if (!state.loading && !state.error && state.rides.isEmpty()) item {
                    Text(stringResource(Res.string.vehicle_overview_no_rides), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (state.rides.isNotEmpty()) {
                    item { SectionLabel(stringResource(Res.string.vehicle_overview_rides)) }
                    item {
                        ElevatedCard(shape = RoundedCornerShape(22.dp)) {
                            val latestRideId = state.lastRide?.id
                            val olderTotalsAlpha by animateFloatAsState(
                                targetValue = if (state.rideTotals.keys.any { it != latestRideId }) 1f else 0f,
                                animationSpec = tween(180),
                                label = "older ride totals",
                            )
                            val olderTotalsModifier = Modifier.graphicsLayer { alpha = olderTotalsAlpha }
                            state.rides.forEachIndexed { i, ride ->
                                if (i > 0) HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                                RideRow(ride, state.rideTotals[ride.id], now,
                                    totalsModifier = if (ride.id == latestRideId) Modifier else olderTotalsModifier,
                                ) { component.onOpenRide(ride.id) }
                            }
                        }
                    }
                }
            }
            if (state.loading) {
                LinearProgressIndicator(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(horizontal = 16.dp))
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text.uppercase(), fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, top = 6.dp))
}

@Composable
private fun BatteryCard(group: List<VehicleBatterySnapshot>, showLabel: Boolean) {
    val snapshot = group.first()
    val sharedCharge = sharedBatteryCharge(group)
    val sharedReadings = snapshot.readings.filter { reading -> group.all { reading in it.readings } }
    ElevatedCard(shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (showLabel) Text(snapshot.label, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                SocRing(sharedCharge, size = 104.dp, stroke = 10.dp, caption = stringResource(Res.string.garage_soc))
                val cells = sharedReadings.filter { it.metricId != "soc_percent" && it.metricId != "cell_delta_mv" }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    cells.chunked(2).forEach { pair ->
                        Row { pair.forEach { StatCell(readingLabel(it.metricId), readingText(it), Modifier.weight(1f)) }
                            if (pair.size == 1) Spacer(Modifier.weight(1f)) }
                    }
                }
            }
            group.forEachIndexed { index, branch ->
                if (group.size > 1) {
                    Text(stringResource(Res.string.branch_label_n, index + 1), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val specific = branch.readings.filterNot {
                        it in sharedReadings || it.metricId == "cell_delta_mv" ||
                            (it.metricId == "soc_percent" && sharedCharge != null)
                    }
                    if (specific.isNotEmpty()) Row {
                        specific.forEach { StatCell(readingLabel(it.metricId), readingText(it), Modifier.weight(1f)) }
                    }
                }
                BatteryCells(branch)
            }
        }
    }
}

@Composable
private fun BatteryCells(snapshot: VehicleBatterySnapshot) {
    val delta = snapshot.value("cell_delta_mv")
    if (snapshot.cells.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        CellBars(snapshot.cells, Modifier.fillMaxWidth().height(38.dp))
        delta?.let { Text(stringResource(Res.string.vehicle_overview_cells,
            "${formatFixed(it.toFloat(), 0)} ${stringResource(Res.string.history_unit_millivolt)}"),
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    } else delta?.let {
        StatCell(stringResource(Res.string.vehicle_reading_cell_delta), "${formatFixed(it.toFloat(), 0)} ${stringResource(Res.string.history_unit_millivolt)}")
    }
}

@Composable
private fun RideRow(ride: HistoryRide, totals: List<HistoryTotal>?, now: Long, totalsModifier: Modifier, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(whenText(ride.started.epochMillis, now), fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            val parts = listOfNotNull(
                totals?.firstOrNull { it.kind == HistoryTotalKind.DISTANCE_KM }?.text(),
                totals?.firstOrNull { it.kind == HistoryTotalKind.DURATION_MILLIS }?.text(),
                totals?.firstOrNull { it.kind == HistoryTotalKind.WH_PER_KM }?.text(),
            )
            Text(
                parts.joinToString(" · "),
                modifier = totalsModifier,
                fontSize = 12.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                minLines = 1,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun readingLabel(metricId: String): String = stringResource(when (metricId) {
    "soc_percent" -> Res.string.vehicle_reading_soc
    "voltage_v" -> Res.string.vehicle_reading_voltage
    "cell_min_v" -> Res.string.vehicle_reading_cell_min
    "cell_max_v" -> Res.string.vehicle_reading_cell_max
    "cell_delta_mv" -> Res.string.vehicle_reading_cell_delta
    else -> Res.string.vehicle_reading_temperature
})

@Composable
private fun readingText(reading: VehicleReading): String {
    val decimals = when (reading.unit) {
        HistoryUnit.VOLT -> if (reading.metricId.startsWith("cell_")) 3 else 1
        else -> 0
    }
    return "${formatFixed(reading.value.toFloat(), decimals)} ${historyUnitText(reading.unit)}"
}
