package ru.sodovaya.volty.presentation.dashboard

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.sodovaya.volty.domain.model.Chemistry
import ru.sodovaya.volty.domain.model.ConnectionState
import ru.sodovaya.volty.presentation.common.CellGrid
import ru.sodovaya.volty.presentation.common.CellUiModel
import ru.sodovaya.volty.presentation.common.chemistryFraction
import ru.sodovaya.volty.presentation.common.MetricCard
import ru.sodovaya.volty.presentation.common.PowerRangeBar
import ru.sodovaya.volty.presentation.common.SparklineGraph
import ru.sodovaya.volty.presentation.common.VehiclePill
import ru.sodovaya.volty.presentation.common.bmsTypeLabel
import ru.sodovaya.volty.presentation.common.iconKeyToEmoji
import kotlin.math.abs
import kotlin.math.round
import org.jetbrains.compose.resources.stringResource
import volty.composeapp.generated.resources.Res
import volty.composeapp.generated.resources.bms_faults_title
import volty.composeapp.generated.resources.cells_delta_label
import volty.composeapp.generated.resources.cells_section_title
import volty.composeapp.generated.resources.cells_v_avg
import volty.composeapp.generated.resources.hero_ah_remaining
import volty.composeapp.generated.resources.hero_charging_to_full
import volty.composeapp.generated.resources.hero_now
import volty.composeapp.generated.resources.hero_state_of_charge
import volty.composeapp.generated.resources.hero_to_empty
import volty.composeapp.generated.resources.hero_to_full
import volty.composeapp.generated.resources.metric_power
import volty.composeapp.generated.resources.metric_temperature
import volty.composeapp.generated.resources.metric_voltage
import volty.composeapp.generated.resources.mosfet_charge_off
import volty.composeapp.generated.resources.mosfet_charge_on
import volty.composeapp.generated.resources.mosfet_discharge_off
import volty.composeapp.generated.resources.mosfet_discharge_on
import volty.composeapp.generated.resources.no_battery
import volty.composeapp.generated.resources.power_last_5_min
import volty.composeapp.generated.resources.status_connected
import volty.composeapp.generated.resources.status_connecting
import volty.composeapp.generated.resources.status_disconnected
import volty.composeapp.generated.resources.status_idle
import volty.composeapp.generated.resources.status_reconnecting
import volty.composeapp.generated.resources.status_scanning

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DashboardScreen(component: DashboardComponent) {
    val state by component.state.collectAsState()
    val data = state.data
    val vehicle = state.vehicle
    val chemistry = vehicle?.chemistry ?: Chemistry.LI_ION_NMC

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val bmsLabel = vehicle?.bmsType?.let { bmsTypeLabel(it) } ?: "—"
        val (statusLabel, statusColor) = when (val c = state.connection) {
            is ConnectionState.Connected -> ("● " + stringResource(Res.string.status_connected, bmsLabel)) to MaterialTheme.colorScheme.tertiary
            is ConnectionState.Connecting -> ("● " + stringResource(Res.string.status_connecting)) to MaterialTheme.colorScheme.secondary
            is ConnectionState.Reconnecting -> ("● " + stringResource(Res.string.status_reconnecting, c.reason)) to MaterialTheme.colorScheme.secondary
            is ConnectionState.Failed -> ("● " + stringResource(Res.string.status_reconnecting, c.reason)) to MaterialTheme.colorScheme.error
            ConnectionState.Disconnected -> ("● " + stringResource(Res.string.status_disconnected)) to MaterialTheme.colorScheme.outline
            ConnectionState.Scanning -> ("● " + stringResource(Res.string.status_scanning)) to MaterialTheme.colorScheme.secondary
            ConnectionState.Idle -> ("● " + stringResource(Res.string.status_idle)) to MaterialTheme.colorScheme.outline
        }
        VehiclePill(
            name = vehicle?.name ?: stringResource(Res.string.no_battery),
            statusText = statusLabel,
            statusColor = statusColor,
            iconEmoji = iconKeyToEmoji(vehicle?.iconKey),
            onClick = component::onPillClicked
        )

        // BMS faults — only shown when non-empty so the dashboard isn't cluttered
        if (data.bmsFaults.isNotEmpty()) {
            FaultsBanner(faults = data.bmsFaults)
        }

        // MOSFET state chips — quick at-a-glance health
        MosfetRow(
            chargeOn = data.chargeEnabled,
            dischargeOn = data.dischargeEnabled
        )

        HeroCard(state)

        // 2-col metric grid
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .animateContentSize()
        ) {
            MetricCard(
                label = stringResource(Res.string.metric_voltage),
                value = "${fmt2(data.voltage)} V",
                modifier = Modifier.weight(1f).fillMaxHeight(),
                sub = if (vehicle?.cellCount != null && data.cellVoltages.isNotEmpty()) {
                    "${data.cellVoltages.size}s · ${fmt2(data.voltage / data.cellVoltages.size)} V/cell"
                } else null
            )
            val powerCharging = data.current > 0.05f
            // Fixed dark-green palette matches the hero card while charging so the
            // two cards visually agree regardless of dynamic-color wallpaper.
            val powerChargingContainer = Color(0xFF184D24)
            val powerChargingOn = Color(0xFFE8F5EA)
            val powerSubColor = if (powerCharging) powerChargingOn.copy(alpha = 0.7f)
            else MaterialTheme.colorScheme.onSurfaceVariant
            MetricCard(
                label = stringResource(Res.string.metric_power),
                value = "${fmt0(data.power)} W",
                modifier = Modifier.weight(1f).fillMaxHeight(),
                containerColor = if (powerCharging) powerChargingContainer else null,
                onColor = if (powerCharging) powerChargingOn else null,
                extra = {
                    Column {
                        PowerRangeBar(
                            min = state.powerMin, peak = state.powerPeak, now = data.power,
                            modifier = Modifier.fillMaxWidth().height(12.dp),
                            marker = if (powerCharging) powerChargingOn else Color.White
                        )
                        Spacer(Modifier.height(2.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("${state.powerMin.toInt()} W", fontSize = 10.sp, color = powerSubColor)
                            Text("peak ${state.powerPeak.toInt()} W", fontSize = 10.sp, color = powerSubColor)
                        }
                    }
                }
            )
        }

        // Wide sparkline
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(12.dp)
                .animateContentSize()
        ) {
            Column {
                Text(
                    stringResource(Res.string.power_last_5_min),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                )
                Spacer(Modifier.height(6.dp))
                SparklineGraph(
                    values = state.sparkline,
                    modifier = Modifier.fillMaxWidth().height(40.dp),
                    minRange = 100f
                )
            }
        }

        // Temperature + cells summary. We intentionally drop the IntrinsicSize.Min
        // height constraint that previously forced the temp FlowRow into a single
        // line (which clipped the 5th+ sensor). Each card now sizes to its content.
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize()
        ) {
            MetricCard(
                label = stringResource(Res.string.metric_temperature),
                value = if (data.temperatures.isEmpty()) "—" else "${fmt0(data.temperatures.first())}° C",
                modifier = Modifier.weight(1f),
                extra = {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // Compact format "1:25°" survives 4+ sensors on a narrow card
                        // and FlowRow wraps gracefully when they exceed the row.
                        data.temperatures.forEachIndexed { i, t ->
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    "${i + 1}:${fmt0(t)}°",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            )
            MetricCard(
                label = stringResource(Res.string.cells_delta_label, state.cellsDeltaMv),
                value = if (data.cellVoltages.isEmpty()) "—" else stringResource(Res.string.cells_v_avg, fmt2(data.cellVoltages.average().toFloat())),
                modifier = Modifier.weight(1f),
                extra = {
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.height(18.dp)) {
                        val cells = data.cellVoltages
                        cells.forEach { v ->
                            val rawFrac = chemistryFraction(v, chemistry)
                            // animateFloatAsState is call-site-keyed, so each per-cell
                            // call in this forEach gets its own animation state.
                            val animFrac by animateFloatAsState(
                                targetValue = rawFrac,
                                animationSpec = tween(durationMillis = 400),
                                label = "cellMini"
                            )
                            val barH = (4 + (animFrac * 14)).dp
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(barH)
                                    .clip(RoundedCornerShape(1.dp))
                                    .background(MaterialTheme.colorScheme.primary)
                                    .align(Alignment.Bottom)
                            )
                        }
                    }
                }
            )
        }

        // Inline cells grid — compact renderer of all cell voltages.
        if (data.cellVoltages.isNotEmpty()) {
            DashboardCellsSection(
                voltages = data.cellVoltages,
                chemistry = chemistry,
                deltaMv = state.cellsDeltaMv,
                cellsMinV = state.cellsMinV,
                cellsMaxV = state.cellsMaxV
            )
        }
    }

    if (state.sheetOpen) {
        VehicleSheet(
            saved = state.savedVehicles,
            activeId = state.vehicle?.id,
            onSwitch = component::onSwitchVehicle,
            onAdd = component::onAddBattery,
            onDisconnect = component::onDisconnect,
            onDismiss = component::onSheetDismiss
        )
    }
}

@Composable
private fun DashboardCellsSection(
    voltages: List<Float>,
    chemistry: Chemistry,
    deltaMv: Int,
    cellsMinV: Float,
    cellsMaxV: Float
) {
    val cells = voltages.mapIndexed { i, v ->
        CellUiModel(
            index = i + 1,
            voltageV = v,
            rangeFraction = chemistryFraction(v, chemistry)
        )
    }
    val maxIdx = if (voltages.isEmpty()) -1 else voltages.indexOf(cellsMaxV)
    val minIdx = if (voltages.isEmpty()) -1 else voltages.indexOf(cellsMinV)
    val highlightSpread = deltaMv >= 30

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(12.dp)
    ) {
        Text(
            stringResource(Res.string.cells_section_title).uppercase(),
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
        )
        Spacer(Modifier.height(8.dp))
        CellGrid(
            cells = cells,
            maxIdx = maxIdx,
            minIdx = minIdx,
            highlightSpread = highlightSpread,
            compact = true,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun MosfetRow(chargeOn: Boolean, dischargeOn: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        MosfetChip(
            label = stringResource(
                if (chargeOn) Res.string.mosfet_charge_on else Res.string.mosfet_charge_off
            ),
            on = chargeOn,
            modifier = Modifier.weight(1f)
        )
        MosfetChip(
            label = stringResource(
                if (dischargeOn) Res.string.mosfet_discharge_on else Res.string.mosfet_discharge_off
            ),
            on = dischargeOn,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun MosfetChip(label: String, on: Boolean, modifier: Modifier = Modifier) {
    val accent = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    val container = if (on) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
    } else {
        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(container)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Filled dot for ON, hollow ring for OFF — readable even when red/green
        // is hard to tell apart (e.g. greyscale screenshot).
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(accent),
            contentAlignment = Alignment.Center
        ) {
            if (!on) {
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .clip(RoundedCornerShape(2.5.dp))
                        .background(container)
                )
            }
        }
        Text(
            label,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun FaultsBanner(faults: List<String>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("!", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
            Text(
                stringResource(Res.string.bms_faults_title),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.85f)
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            faults.joinToString(" · "),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onErrorContainer
        )
    }
}

@Composable
private fun HeroCard(state: DashboardComponent.State) {
    val data = state.data
    val v = state.vehicle
    // Direction from short window (30 s) — switches fast on real flips, ignores
    // brief regen blips during a long discharge. We keep the per-vehicle long
    // window (state.avgPowerW) for the ETA magnitude below.
    val dirAvg = state.recentAvgPowerW
    val isCharging = when {
        dirAvg > 1f -> true
        dirAvg < -1f -> false
        else -> data.power > 0.05f  // fallback when truly balanced
    }
    // Fixed darker-green palette for the charging hero so dynamic-color wallpapers
    // can't wash the card out. Other UI keeps the dynamic palette.
    val chargingContainer = Color(0xFF184D24) // dark forest green
    val chargingOn = Color(0xFFE8F5EA)        // near-white on-container
    val chargingAccent = Color(0xFF7FCB8A)    // mid green for bar + SOC %
    val containerColor = if (isCharging) chargingContainer else MaterialTheme.colorScheme.primaryContainer
    val onColor = if (isCharging) chargingOn else MaterialTheme.colorScheme.onPrimaryContainer
    val barColor = if (isCharging) chargingAccent else MaterialTheme.colorScheme.primary
    val nominalV = v?.cellCount?.let { count -> count * v.chemistry.nominalCellV }
        ?: data.voltage.coerceAtLeast(1f)
    val eta = timeRemainingDescription(
        isCharging = isCharging,
        remainingAh = data.charge,
        capacityAh = data.capacity,
        avgPowerW = state.avgPowerW,
        nominalV = nominalV
    )
    val etaLabel = if (isCharging)
        stringResource(Res.string.hero_to_full, abs(state.avgPowerW).toInt())
    else
        stringResource(Res.string.hero_to_empty, abs(state.avgPowerW).toInt())
    val socFraction = (data.soc / 100f).coerceIn(0f, 1f)
    val animatedSoc by animateFloatAsState(
        targetValue = data.soc,
        animationSpec = tween(durationMillis = 600),
        label = "soc"
    )
    val animatedSocFraction by animateFloatAsState(
        targetValue = socFraction,
        animationSpec = tween(durationMillis = 400),
        label = "socFraction"
    )
    val animatedContainer by animateColorAsState(
        targetValue = containerColor,
        animationSpec = tween(durationMillis = 400),
        label = "heroBg"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp, 36.dp, 28.dp, 36.dp))
            .background(animatedContainer)
            .padding(horizontal = 20.dp, vertical = 16.dp)
            .animateContentSize()
    ) {
        Text(
            text = if (isCharging) stringResource(Res.string.hero_charging_to_full) else stringResource(Res.string.hero_state_of_charge),
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = onColor.copy(alpha = 0.7f)
        )
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(fmt0(animatedSoc), fontSize = 80.sp, fontWeight = FontWeight.Medium, color = onColor)
                Text("%", fontSize = 24.sp, color = onColor.copy(alpha = 0.65f))
            }
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(bottom = 6.dp)) {
                Text(fmt1(data.charge), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = onColor)
                Text(" / ${fmt1(data.capacity)}", fontSize = 12.sp, color = onColor.copy(alpha = 0.65f))
                Text(stringResource(Res.string.hero_ah_remaining), fontSize = 10.sp, color = onColor.copy(alpha = 0.7f))
            }
        }

        Spacer(Modifier.height(12.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(onColor.copy(alpha = 0.1f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(animatedSocFraction)
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(barColor)
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("≈ $eta", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = onColor)
                Text(etaLabel, fontSize = 10.sp, color = onColor.copy(alpha = 0.55f))
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("${fmtSigned1(data.current)} A", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = onColor)
                Text(stringResource(Res.string.hero_now), fontSize = 10.sp, color = onColor.copy(alpha = 0.55f))
            }
        }
    }
}

// --- KMP-safe number formatters (no JVM String.format) ---

private fun fmt0(v: Float): String = round(v).toInt().toString()
private fun fmt1(v: Float): String = roundTo(v, 1)
private fun fmt2(v: Float): String = roundTo(v, 2)

private fun fmtSigned1(v: Float): String = (if (v >= 0f) "+" else "") + fmt1(v)

private fun roundTo(value: Float, decimals: Int): String {
    var factor = 1.0
    repeat(decimals) { factor *= 10.0 }
    val rounded = round(value.toDouble() * factor) / factor
    val negative = rounded < 0
    val absVal = abs(rounded)
    val intPart = absVal.toLong()
    val fracScaled = round((absVal - intPart) * factor).toLong()
    val sign = if (negative) "-" else ""
    if (decimals == 0) return "$sign$intPart"
    val fracStr = fracScaled.toString().padStart(decimals, '0')
    return "$sign$intPart.$fracStr"
}
