package ru.sodovaya.volty.presentation.picker

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import org.jetbrains.compose.resources.stringResource
import ru.sodovaya.volty.domain.model.DemoProfile
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.model.primaryAddress
import ru.sodovaya.volty.presentation.history.HistoryTotalKind
import ru.sodovaya.volty.presentation.history.text
import ru.sodovaya.volty.presentation.vehicle.*
import ru.sodovaya.volty.util.formatFixed
import volty.composeapp.generated.resources.*

/**
 * The cold picker as a garage: saved vehicles first, with what was recorded last, and the scan quietly
 * marking which of them are nearby. New devices and demo sit below, small.
 */
@OptIn(ExperimentalTime::class)
@Composable
internal fun GarageScreen(component: PickerComponent, state: PickerComponent.State) {
    val inRange = state.myInRange.map { it.id }.toSet()
    val layout = garageLayout(state.saved, inRange)
    val now = remember { Clock.System.now().toEpochMilliseconds() }
    fun connect(v: Vehicle) = if (v.id in inRange) component.onConnectKnown(v) else component.onConnectSaved(v)

    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(Res.string.garage_title), fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    if (state.saved.isNotEmpty()) Text(stringResource(Res.string.garage_count, state.saved.size),
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ScanChip(found = state.myInRange.size + state.otherNearby.size)
            }
        }
        state.error?.let { error -> item {
            Text(stringResource(Res.string.picker_error, error), color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        } }
        val hero = layout.hero
        if (hero == null) item { EmptyGarage(component::onAddNewBattery) }
        else item(key = "hero-" + hero.id) {
            HeroCard(hero, state.glances[hero.id], hero.id in inRange, state.glancesLoaded, now,
                connecting = state.connecting == hero.primaryAddress,
                onConnect = { connect(hero) }, onDetails = { component.onOpenSaved(hero) })
        }
        if (layout.others.isNotEmpty()) {
            item { SectionLabel(stringResource(Res.string.garage_more)) }
            item {
                ElevatedCard(shape = RoundedCornerShape(20.dp)) {
                    layout.others.forEachIndexed { i, v ->
                        if (i > 0) HorizontalDivider(Modifier.padding(start = 64.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        GarageRow(v, state.glances[v.id], v.id in inRange, now) { component.onOpenSaved(v) }
                    }
                }
            }
        }
        if (state.otherNearby.isNotEmpty()) {
            item { SectionLabel(stringResource(Res.string.garage_new_nearby)) }
            items(state.otherNearby, key = { "d-" + it.address }) { d ->
                DeviceRow(d, isConnecting = state.connecting == d.address) { component.onDeviceTapped(d) }
            }
        }
        if (state.otherDevices.isNotEmpty()) item {
            TextButton(onClick = component::onToggleShowAll, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(Res.string.picker_show_all, state.otherDevices.size))
            }
        }
        if (state.showAll) items(state.otherDevices, key = { "u-" + it.address }) { d ->
            DeviceRow(d, isConnecting = state.connecting == d.address) { component.onDeviceTapped(d) }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                if (hero != null) TextButton(onClick = component::onAddNewBattery) { Text(stringResource(Res.string.garage_add)) }
                TextButton(onClick = { component.onTryDemo(DemoProfile.SCOOTER) }) { Text(stringResource(Res.string.garage_demo_scooter)) }
                TextButton(onClick = { component.onTryDemo(DemoProfile.WHEEL) }) { Text(stringResource(Res.string.garage_demo_wheel)) }
            }
        }
    }
}

@Composable
private fun ScanChip(found: Int) {
    val pulse = rememberInfiniteTransition()
    val a by pulse.animateFloat(0.35f, 1f, infiniteRepeatable(tween(800), RepeatMode.Reverse))
    Row(Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 11.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Box(Modifier.size(8.dp).alpha(a).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
        Text(if (found > 0) stringResource(Res.string.garage_found, found) else stringResource(Res.string.garage_scanning),
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text.uppercase(), fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
}

@Composable
private fun HeroCard(vehicle: Vehicle, glance: VehicleGlance?, near: Boolean, loaded: Boolean, now: Long,
    connecting: Boolean, onConnect: () -> Unit, onDetails: () -> Unit) {
    ElevatedCard(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth().clickable(onClick = onDetails)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                VehicleBadge(vehicle.iconKey)
                Column(Modifier.weight(1f)) {
                    Text(vehicle.name, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val distance = glance?.total(HistoryTotalKind.DISTANCE_KM)?.text()
                    Text(when {
                        glance != null -> whenText(glance.ride.started.epochMillis, now) + (distance?.let { " · $it" } ?: "")
                        loaded -> stringResource(Res.string.garage_no_rides)
                        else -> " "
                    }, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                NearPill(near)
            }
            val battery = glance?.battery
            if (battery != null || glance?.totals?.isNotEmpty() == true) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    SocRing(battery?.value("soc_percent"), caption = stringResource(Res.string.garage_soc))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row {
                            battery?.value("voltage_v")?.let { StatCell(stringResource(Res.string.vehicle_reading_voltage),
                                "${formatFixed(it.toFloat(), 1)} ${stringResource(Res.string.history_unit_volt)}", Modifier.weight(1f)) }
                            battery?.value("cell_delta_mv")?.let { StatCell(stringResource(Res.string.vehicle_reading_cell_delta),
                                "${formatFixed(it.toFloat(), 0)} ${stringResource(Res.string.history_unit_millivolt)}", Modifier.weight(1f)) }
                        }
                        Row {
                            glance?.total(HistoryTotalKind.WH_PER_KM)?.let { StatCell(stringResource(Res.string.history_total_consumption), it.text(), Modifier.weight(1f)) }
                            battery?.value("battery_temperature_c")?.let { StatCell(stringResource(Res.string.vehicle_reading_temperature),
                                "${formatFixed(it.toFloat(), 0)} ${stringResource(Res.string.unit_celsius)}", Modifier.weight(1f)) }
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onConnect, enabled = !connecting, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(14.dp)) {
                    if (connecting) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text(stringResource(Res.string.vehicle_overview_connect))
                }
                FilledTonalButton(onClick = onDetails, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(14.dp)) {
                    Text(stringResource(Res.string.garage_details))
                }
            }
        }
    }
}

@Composable
private fun GarageRow(vehicle: Vehicle, glance: VehicleGlance?, near: Boolean, now: Long, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        VehicleBadge(vehicle.iconKey, size = 38.dp)
        Column(Modifier.weight(1f)) {
            Text(vehicle.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (glance != null) Text(whenText(glance.ride.started.epochMillis, now) +
                (glance.total(HistoryTotalKind.DISTANCE_KM)?.let { " · " + it.text() } ?: ""),
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val soc = glance?.battery?.value("soc_percent")
        if (near) NearPill(true)
        else if (soc != null) Text("${formatFixed(soc.toFloat(), 0)}%", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = socColor(soc))
    }
}

@Composable
private fun EmptyGarage(onAdd: () -> Unit) {
    ElevatedCard(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("🛞", fontSize = 40.sp)
            Text(stringResource(Res.string.garage_empty_title), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text(stringResource(Res.string.garage_empty_text), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Button(onClick = onAdd, shape = RoundedCornerShape(14.dp)) { Text(stringResource(Res.string.garage_add_vehicle)) }
        }
    }
}
