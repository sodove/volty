package ru.sodovaya.volty.presentation.vehicle

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import ru.sodovaya.volty.presentation.common.LocalVoltyDarkTheme
import ru.sodovaya.volty.presentation.history.HistoryTotal
import ru.sodovaya.volty.presentation.history.HistoryTotalKind
import ru.sodovaya.volty.presentation.history.historyUnitText
import ru.sodovaya.volty.presentation.history.text
import ru.sodovaya.volty.presentation.map.LocalVoltyMapPalette
import ru.sodovaya.volty.util.formatFixed
import volty.composeapp.generated.resources.*

private val rideSubtitleKinds = listOf(
    HistoryTotalKind.DISTANCE_KM,
    HistoryTotalKind.DURATION_MILLIS,
    HistoryTotalKind.WH_PER_KM,
)

/** Renders [VehicleOverviewState]; every number here is a recorded one, labelled with its time. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalTime::class)
@Composable
fun VehicleOverviewScreen(component: VehicleOverviewComponent) {
    val state by component.state.collectAsState()
    val now = remember { Clock.System.now().toEpochMilliseconds() }
    val batteryGroups = remember(state.battery) { vehicleBatteryGroups(state.battery) }
    val asOf = state.battery.maxOfOrNull { it.atEpochMillis } ?: state.lastRide?.ended?.epochMillis
    // Keep the reveal state above the lazy item so settled content does not replay on scroll or back.
    var rideListRevealed by rememberSaveable { mutableStateOf(false) }
    var olderTotalsRevealed by rememberSaveable { mutableStateOf(false) }
    val rideListReveal = remember { Animatable(if (rideListRevealed) 1f else 0f) }
    val olderTotalsReveal = remember { Animatable(if (olderTotalsRevealed) 1f else 0f) }
    val latestRideId = state.lastRide?.id
    val hasRides = state.rides.isNotEmpty()
    val hasOlderSubtitles = state.rideTotals.any { (id, totals) ->
        id != latestRideId && totals.any { it.kind in rideSubtitleKinds }
    }
    LaunchedEffect(hasRides) {
        if (hasRides && !rideListRevealed) {
            rideListReveal.animateTo(1f, tween(320))
            rideListRevealed = true
        }
    }
    LaunchedEffect(hasOlderSubtitles) {
        if (hasOlderSubtitles && !olderTotalsRevealed) {
            olderTotalsReveal.animateTo(1f, tween(320))
            olderTotalsRevealed = true
        }
    }
    val ridesVisible by remember { derivedStateOf { rideListReveal.value > 0f } }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(state.vehicle?.name ?: "", fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (asOf != null) stringResource(Res.string.vehicle_overview_offline_at, whenText(asOf, now))
                            else stringResource(Res.string.vehicle_overview_offline),
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
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
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.error) item { Text(stringResource(Res.string.vehicle_overview_error), color = MaterialTheme.colorScheme.error) }
            item(key = "overview-summary") {
                Column {
                    BatterySection(batteryGroups, state.loading)
                    state.lastRide?.let { ride ->
                        LastRideCard(ride, state.lastTotals, state.lastRoute, state.loading, now) { component.onOpenRide(ride.id) }
                    }
                    if (!state.loading && !state.error && state.rides.isEmpty()) {
                        Text(stringResource(Res.string.vehicle_overview_no_rides), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (hasRides) {
                item(key = "ride-list") {
                    Column(
                        modifier = Modifier.fillMaxWidth().graphicsLayer {
                            alpha = rideListReveal.value
                            translationY = (1f - rideListReveal.value) * 8.dp.toPx()
                        },
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        SectionLabel(stringResource(Res.string.vehicle_overview_rides))
                        ElevatedCard(shape = RoundedCornerShape(22.dp)) {
                            val olderTotalsModifier = Modifier.graphicsLayer { alpha = olderTotalsReveal.value }
                            state.rides.forEachIndexed { i, ride ->
                                key(ride.id) {
                                    if (i > 0) HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                                    RideRow(ride, state.rideTotals[ride.id], now,
                                        enabled = ridesVisible,
                                        totalsModifier = if (ride.id == latestRideId) Modifier else olderTotalsModifier,
                                    ) { component.onOpenRide(ride.id) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BatterySection(groups: List<List<VehicleBatterySnapshot>>, loading: Boolean) {
    Column(Modifier.fillMaxWidth().animateContentSize(tween(180))) {
        if (groups.isEmpty() && loading) {
            BatteryLoadingCard(Modifier.padding(bottom = 12.dp))
        }
        groups.forEach { group ->
            key(group.first().sourceId) {
                BatteryCard(group, showLabel = groups.size > 1, modifier = Modifier.padding(bottom = 12.dp))
            }
        }
    }
}

@Composable
private fun BatteryLoadingCard(modifier: Modifier = Modifier) {
    ElevatedCard(shape = RoundedCornerShape(22.dp), modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            SocRing(null, size = 104.dp, stroke = 10.dp, caption = stringResource(Res.string.garage_soc))
            Text(stringResource(Res.string.vehicle_overview_loading_battery), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LastRideCard(
    ride: HistoryRide,
    totals: List<HistoryTotal>,
    route: List<Pair<Double, Double>>,
    loading: Boolean,
    now: Long,
    onClick: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionLabel(stringResource(Res.string.vehicle_overview_last_ride, whenText(ride.started.epochMillis, now)))
        ElevatedCard(shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
            Column(Modifier.animateContentSize(tween(180)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Reserve the preview while reading the route so the existing totals stay in place.
                val previewModifier = Modifier.fillMaxWidth().height(160.dp)
                if (route.size >= 2) {
                    RouteMapPreview(route, LocalVoltyDarkTheme.current, previewModifier)
                } else if (loading) {
                    Surface(modifier = previewModifier, shape = RoundedCornerShape(14.dp), color = Color(LocalVoltyMapPalette.current.background)) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(stringResource(Res.string.vehicle_overview_loading_route), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (totals.isNotEmpty()) TotalsTiles(totals)
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
private fun BatteryCard(group: List<VehicleBatterySnapshot>, showLabel: Boolean, modifier: Modifier = Modifier) {
    val snapshot = group.first()
    val sharedCharge = sharedBatteryCharge(group)
    val sharedReadings = snapshot.readings.filter { reading -> group.all { reading in it.readings } }
    ElevatedCard(shape = RoundedCornerShape(22.dp), modifier = modifier.fillMaxWidth()) {
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
private fun RideRow(
    ride: HistoryRide, totals: List<HistoryTotal>?, now: Long,
    enabled: Boolean, totalsModifier: Modifier, onClick: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(whenText(ride.started.epochMillis, now), fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            val parts = rideSubtitleKinds.mapNotNull { kind -> totals?.firstOrNull { it.kind == kind }?.text() }
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
