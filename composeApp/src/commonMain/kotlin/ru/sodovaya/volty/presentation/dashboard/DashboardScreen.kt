package ru.sodovaya.volty.presentation.dashboard

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.ConnectionState
import ru.sodovaya.volty.domain.model.cellCountOrNull
import ru.sodovaya.volty.presentation.common.CellGrid
import ru.sodovaya.volty.presentation.common.CellUiModel
import ru.sodovaya.volty.presentation.common.BmsMetricMapper
import ru.sodovaya.volty.presentation.common.chemistryFraction
import ru.sodovaya.volty.presentation.common.GraphLinkButton
import ru.sodovaya.volty.presentation.common.MetricCard
import ru.sodovaya.volty.presentation.common.PowerRangeBar
import ru.sodovaya.volty.presentation.common.ResponsiveLayoutMode
import ru.sodovaya.volty.presentation.common.SparklineGraph
import ru.sodovaya.volty.presentation.common.VehiclePill
import ru.sodovaya.volty.domain.stats.BmsReadings
import ru.sodovaya.volty.presentation.common.SourcePreference
import ru.sodovaya.volty.presentation.common.vehicleSourceLabel
import ru.sodovaya.volty.presentation.common.responsiveLayoutMode
import ru.sodovaya.volty.presentation.common.iconKeyToEmoji
import ru.sodovaya.volty.util.formatFixed
import kotlin.math.abs
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.stringResource
import volty.composeapp.generated.resources.Res
import volty.composeapp.generated.resources.bms_faults_title
import volty.composeapp.generated.resources.cells_delta_label
import volty.composeapp.generated.resources.cells_section_title
import volty.composeapp.generated.resources.cells_v_avg
import volty.composeapp.generated.resources.hero_ah_remaining
import volty.composeapp.generated.resources.hero_cycles_ah
import volty.composeapp.generated.resources.hero_cycles_count
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

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        val layoutMode = responsiveLayoutMode(maxWidth.value, maxHeight.value)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
        // BMS-first on THIS screen specifically: everything below renders cell
        // voltages, BMS faults and chemistry, so naming the battery is what
        // tells the user something. Ride leads with the controller for the
        // mirror-image reason. Same helper, same fallback chain — only the
        // order differs, and either way a single-source vehicle reads the same.
        // The em-dash is the "no vehicle connected at all" placeholder the
        // format string needs, exactly as before; it never stands in for a
        // real source.
        val bmsLabel = vehicleSourceLabel(vehicle, prefer = SourcePreference.BMS) ?: "—"
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

        if (layoutMode == ResponsiveLayoutMode.WIDE) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top
            ) {
                HeroCard(state, modifier = Modifier.weight(1f))
                PrimaryMetrics(state = state, data = data, modifier = Modifier.weight(1f))
            }
        } else {
            HeroCard(state)
            PrimaryMetrics(state = state, data = data)
        }

        // The primary metric cards stay together above so the wide branch can
        // place them beside the hero without changing their mapping. Secondary
        // telemetry remains below in the same scroll surface for both modes.


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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(Res.string.power_last_5_min),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                    )
                    // Graph is no longer a bottom tab — this is its entry point
                    // from the battery dashboard.
                    GraphLinkButton(onClick = component::onOpenGraph)
                }
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
            // Headline shows the HOTTEST sensor (not sensor #1) — that's the one
            // that matters for safety and the one AlertEngine watches (it alerts on
            // max). The hottest chip below is tinted so you can see *which* sensor.
            val hottestIdx = if (data.temperatures.size > 1)
                data.temperatures.indices.maxByOrNull { data.temperatures[it] } else null
            MetricCard(
                label = stringResource(Res.string.metric_temperature),
                value = if (data.temperatures.isEmpty()) "—" else "${fmt0(data.temperatures.max())}° C",
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
                            val isHottest = i == hottestIdx
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        if (isHottest) MaterialTheme.colorScheme.primaryContainer
                                        else MaterialTheme.colorScheme.surfaceContainerHigh
                                    )
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    "${i + 1}:${fmt0(t)}°",
                                    fontSize = 11.sp,
                                    color = if (isHottest) MaterialTheme.colorScheme.onPrimaryContainer
                                    else MaterialTheme.colorScheme.onSurface
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

        // Branch summary block — one card per pack. Guarded by showBranches so a
        // single-pack battery renders exactly the pre-multi-pack dashboard.
        if (state.showBranches) {
            BranchesSection(
                packs = state.packs,
                chemistry = chemistry,
                onPackClicked = component::onPackClicked
            )
        }

        // Inline cells grid — compact renderer of all cell voltages. Multi-pack
        // batteries skip it: the aggregate list is a concatenation whose indices
        // match no physical cell, and per the design cells live on the branch
        // detail screen (an ET Max would otherwise dump 80 cells here).
        if (!state.showBranches && data.cellVoltages.isNotEmpty()) {
            DashboardCellsSection(
                voltages = data.cellVoltages,
                chemistry = chemistry,
                deltaMv = state.cellsDeltaMv,
                cellsMinV = state.cellsMinV,
                cellsMaxV = state.cellsMaxV
            )
        }
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
private fun PrimaryMetrics(
    state: DashboardComponent.State,
    data: BmsData,
    modifier: Modifier = Modifier
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .animateContentSize()
    ) {
        MetricCard(
            label = stringResource(Res.string.metric_voltage),
            // Zero is never a real pack voltage — it means "unknown": a
            // Begode without a smart BMS whose profile has no cell count
            // yet (the live-frame reading cannot be scaled honestly), or
            // no sample at all. Show a dash rather than "0.00 V".
            value = if (data.isConnected && data.voltage > 0f) "${fmt2(data.voltage)} V" else "—",
            modifier = Modifier.weight(1f).fillMaxHeight(),
            sub = if (data.cellVoltages.isNotEmpty()) {
                // Cells in SERIES behind the aggregate voltage — not the raw
                // cell count. For a parallel multi-branch pack the cell list
                // is every branch's cells unioned (80 on a two-branch 40S
                // wheel) while the voltage is one branch's, so the raw count
                // would read "80s · 2.08 V/cell". Derive the series count
                // from the mean cell voltage instead, so count × per-cell
                // reconciles with the tile's own voltage. For a single pack
                // this equals cellVoltages.size exactly — unchanged.
                val perCell = data.cellVoltages.average().toFloat()
                val seriesCells =
                    if (perCell > 0f) (data.voltage / perCell).roundToInt()
                    else data.cellVoltages.size
                "${seriesCells}s · ${fmt2(perCell)} V/cell"
            } else null
        )
        val powerText = BmsMetricMapper.powerValue(data)
        val powerMin = state.powerMin
        val powerPeak = state.powerPeak
        val powerCharging = BmsReadings.current(data)?.let { it > 0.05f } ?: false
        // Fixed dark-green palette matches the hero card while charging so the
        // two cards visually agree regardless of dynamic-color wallpaper.
        val powerChargingContainer = Color(0xFF184D24)
        val powerChargingOn = Color(0xFFE8F5EA)
        val powerSubColor = if (powerCharging) powerChargingOn.copy(alpha = 0.7f)
        else MaterialTheme.colorScheme.onSurfaceVariant
        MetricCard(
            label = stringResource(Res.string.metric_power),
            value = powerText?.let { "$it W" } ?: "—",
            modifier = Modifier.weight(1f).fillMaxHeight(),
            containerColor = if (powerCharging) powerChargingContainer else null,
            onColor = if (powerCharging) powerChargingOn else null,
            extra = {
                if (powerText != null && powerMin != null && powerPeak != null) Column {
                    // powerMin/powerPeak are consumption-positive (DashboardComponent
                    // negates the power series: discharge plots upward). The marker
                    // tracks current consumption, so we negate data.power here too.
                    // The big number above keeps the domain sign (+ = charging).
                    PowerRangeBar(
                        min = powerMin, peak = powerPeak, now = -data.power,
                        modifier = Modifier.fillMaxWidth().height(12.dp),
                        marker = if (powerCharging) powerChargingOn else Color.White
                    )
                    Spacer(Modifier.height(2.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${fmt0(powerMin)} W", fontSize = 10.sp, color = powerSubColor, maxLines = 1, softWrap = false)
                        Text("peak ${fmt0(powerPeak)} W", fontSize = 10.sp, color = powerSubColor, maxLines = 1, softWrap = false)
                    }
                }
            }
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
private fun HeroCard(
    state: DashboardComponent.State,
    modifier: Modifier = Modifier
) {
    val data = state.data
    val socKnown = data.isConnected && data.socKnown
    val v = state.vehicle
    // Direction from short window (30 s) — switches fast on real flips, ignores
    // brief regen blips during a long discharge. We keep the per-vehicle long
    // window (state.avgPowerW) for the ETA magnitude below.
    val dirAvg = state.recentAvgPowerW
    val isCharging = when {
        dirAvg != null && dirAvg > 1f -> true
        dirAvg != null && dirAvg < -1f -> false
        else -> BmsReadings.power(data)?.let { it > 0.05f } ?: false
    }
    // Fixed darker-green palette for the charging hero so dynamic-color wallpapers
    // can't wash the card out. Other UI keeps the dynamic palette.
    val chargingContainer = Color(0xFF184D24) // dark forest green
    val chargingOn = Color(0xFFE8F5EA)        // near-white on-container
    val chargingAccent = Color(0xFF7FCB8A)    // mid green for bar + SOC %
    val containerColor = if (isCharging) chargingContainer else MaterialTheme.colorScheme.primaryContainer
    val onColor = if (isCharging) chargingOn else MaterialTheme.colorScheme.onPrimaryContainer
    val barColor = if (isCharging) chargingAccent else MaterialTheme.colorScheme.primary
    // `count > 0`, matching every other reader of a stored cell count
    // (VoltageSocEstimator, BegodeProtocol.inputVoltageOrNull,
    // withScaledBegodeLiveVoltage): the field is typeable again since Task 3,
    // and a typed literal "0" is `cellCount = 0`, not null — `nominalV = 0f`
    // would force `timeRemainingDescription` to a permanent "—" even though
    // the real live `data.voltage` fallback right below it is sitting there
    // unused, which is worse than the blank this guard already handles.
    val nominalV = v?.cellCountOrNull?.takeIf { it > 0 }?.let { count -> count * v.chemistry.nominalCellV }
        ?: data.voltage.coerceAtLeast(1f)
    val eta = timeRemainingDescription(
        isCharging = isCharging,
        remainingAh = data.charge,
        capacityAh = data.capacity,
        avgPowerW = state.avgPowerW,
        nominalV = nominalV
    )
    val etaLabel = state.avgPowerW?.let { averagePower ->
        if (isCharging) stringResource(Res.string.hero_to_full, abs(averagePower).toInt())
        else stringResource(Res.string.hero_to_empty, abs(averagePower).toInt())
    } ?: "—"
    val socFraction = if (socKnown) (data.soc / 100f).coerceIn(0f, 1f) else 0f
    val animatedSoc by animateFloatAsState(
        targetValue = if (socKnown) data.soc else 0f,
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
        modifier = modifier
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
                Text(if (socKnown) fmt0(animatedSoc) else "—", fontSize = 80.sp, fontWeight = FontWeight.Medium, color = onColor)
                if (socKnown) {
                    Text("%", fontSize = 24.sp, color = onColor.copy(alpha = 0.65f))
                }
            }
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(bottom = 6.dp)) {
                // Cycles, above the Ah block: an integer count for count-based BMS
                // (JK/JBD/Daly), or ANT's cumulative "total Ah cycle" in Ah.
                val cyclesText = when {
                    data.cycleCapacityAh > 0f ->
                        stringResource(Res.string.hero_cycles_ah, fmt0(data.cycleCapacityAh))
                    data.numCycles > 0 ->
                        stringResource(Res.string.hero_cycles_count, data.numCycles)
                    else -> null
                }
                if (cyclesText != null) {
                    Text(cyclesText, fontSize = 10.sp, color = onColor.copy(alpha = 0.7f))
                }
                // Remaining / total on one line — remaining stays bold, total
                // lighter. A device that never reported a capacity (a Begode
                // wheel has no Ah counters at all) leaves it at 0 — showing a
                // fabricated "0.0 / 0.0" would be a lie, so render the "—"
                // placeholder this screen uses for unavailable values instead.
                if (data.capacity > 0f) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(fmt1(data.charge), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = onColor)
                        Text(" / ${fmt1(data.capacity)}", fontSize = 12.sp, color = onColor.copy(alpha = 0.65f))
                    }
                } else {
                    Text("—", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = onColor)
                }
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
                Text(
                    BmsMetricMapper.currentValue(data)?.let { "$it A" } ?: "—",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = onColor
                )
                Text(stringResource(Res.string.hero_now), fontSize = 10.sp, color = onColor.copy(alpha = 0.55f))
            }
        }
    }
}

/** Pure counterpart of the dashboard hero's SoC contract; Compose itself is not unit-testable. */
internal fun dashboardSocValue(data: BmsData): String =
    if (data.isConnected && data.socKnown) fmt0(data.soc) else "—"

// --- Number formatting delegates to util.NumberFormat (KMP-safe, negative-correct) ---

private fun fmt0(v: Float): String = formatFixed(v, 0)
private fun fmt1(v: Float): String = formatFixed(v, 1)
private fun fmt2(v: Float): String = formatFixed(v, 2)
