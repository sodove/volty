package ru.sodovaya.volty.presentation.ride

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.ConnectionState
import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.bmsType
import ru.sodovaya.volty.domain.model.primaryController
import ru.sodovaya.volty.domain.stats.DutyLevel
import ru.sodovaya.volty.domain.stats.RideMetrics
import ru.sodovaya.volty.presentation.common.MetricCard
import ru.sodovaya.volty.presentation.common.SparklineGraph
import ru.sodovaya.volty.presentation.common.VehiclePill
import ru.sodovaya.volty.presentation.common.bmsTypeLabel
import ru.sodovaya.volty.presentation.common.iconKeyToEmoji
import ru.sodovaya.volty.presentation.dashboard.VehicleSheet
import ru.sodovaya.volty.presentation.ride.gauge.RadialGauge
import ru.sodovaya.volty.util.UnitFormatter
import ru.sodovaya.volty.util.UnitSystem
import ru.sodovaya.volty.util.formatFixed
import ru.sodovaya.volty.util.formatSigned
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.stringResource
import volty.composeapp.generated.resources.Res
import volty.composeapp.generated.resources.no_battery
import volty.composeapp.generated.resources.ride_battery
import volty.composeapp.generated.resources.ride_consumption
import volty.composeapp.generated.resources.ride_consumption_avg
import volty.composeapp.generated.resources.ride_esc_temp
import volty.composeapp.generated.resources.ride_motor_temp
import volty.composeapp.generated.resources.ride_odometer
import volty.composeapp.generated.resources.ride_power
import volty.composeapp.generated.resources.ride_speed_unknown
import volty.composeapp.generated.resources.ride_trip
import volty.composeapp.generated.resources.ride_uptime
import volty.composeapp.generated.resources.status_connected
import volty.composeapp.generated.resources.status_connecting
import volty.composeapp.generated.resources.status_disconnected
import volty.composeapp.generated.resources.status_idle
import volty.composeapp.generated.resources.status_reconnecting
import volty.composeapp.generated.resources.status_scanning

/**
 * The Clean Ride dashboard — the app's home screen for a vehicle with a motor
 * controller. Per the locked design (`docs/design/ride-dashboard-mockup.html`,
 * Clean view): vehicle pill, a concentric speedo hero, a 2x2 metric cluster,
 * a consumption card with a sparkline, and a monospace odo/trip/uptime strip.
 *
 * [ru.sodovaya.volty.domain.model.DashboardStyle.CLASSIC] renders this same
 * layout for now — the Classic (overlapping VESC dial cluster) renderer is a
 * later task.
 */
@Composable
fun RideDashboardScreen(component: RideDashboardComponent) {
    val state by component.state.collectAsState()
    val vehicle = state.vehicle
    val motion = state.motion
    val units = state.units

    // Session-local trackers, not part of RideDashboardComponent.State: keyed
    // on the vehicle id so switching vehicles starts both fresh, without the
    // component needing to know about per-screen presentation history.
    var sessionMaxSpeedKmh by remember(vehicle?.id) { mutableStateOf(0f) }
    if (motion.speedKnown && motion.speedKmh > sessionMaxSpeedKmh) {
        sessionMaxSpeedKmh = motion.speedKmh
    }
    // Never zero: the hero always has at least a 70 km/h scale to draw against.
    val vehicleMaxSpeed = max(70f, ceil(sessionMaxSpeedKmh / 10f) * 10f)

    val recentSpeeds = remember(vehicle?.id) { mutableStateListOf<Float>() }
    LaunchedEffect(vehicle?.id, motion.timestamp) {
        if (motion.speedKnown) {
            recentSpeeds.add(motion.speedKmh)
            while (recentSpeeds.size > SPARKLINE_MAX_POINTS) recentSpeeds.removeAt(0)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Controller type (VESC/FarDriver/…) when present, falling back to the
        // BMS label for a vehicle whose packs happen to be primary — mirrors
        // DashboardScreen's status line, just sourced from the motor side.
        val controllerLabel = vehicle?.primaryController?.controllerType?.label
            ?: vehicle?.takeIf { it.packs.isNotEmpty() }?.let { bmsTypeLabel(it.bmsType) }
            ?: "—"
        val (statusLabel, statusColor) = when (val c = state.connection) {
            is ConnectionState.Connected ->
                ("● " + stringResource(Res.string.status_connected, controllerLabel)) to MaterialTheme.colorScheme.tertiary
            is ConnectionState.Connecting ->
                ("● " + stringResource(Res.string.status_connecting)) to MaterialTheme.colorScheme.secondary
            is ConnectionState.Reconnecting ->
                ("● " + stringResource(Res.string.status_reconnecting, c.reason)) to MaterialTheme.colorScheme.secondary
            is ConnectionState.Failed ->
                ("● " + stringResource(Res.string.status_reconnecting, c.reason)) to MaterialTheme.colorScheme.error
            ConnectionState.Disconnected ->
                ("● " + stringResource(Res.string.status_disconnected)) to MaterialTheme.colorScheme.outline
            ConnectionState.Scanning ->
                ("● " + stringResource(Res.string.status_scanning)) to MaterialTheme.colorScheme.secondary
            ConnectionState.Idle ->
                ("● " + stringResource(Res.string.status_idle)) to MaterialTheme.colorScheme.outline
        }
        VehiclePill(
            name = vehicle?.name ?: stringResource(Res.string.no_battery),
            statusText = statusLabel,
            statusColor = statusColor,
            iconEmoji = iconKeyToEmoji(vehicle?.iconKey),
            onClick = component::onPillClicked
        )

        RideHero(state = state, vehicleMaxSpeed = vehicleMaxSpeed)

        MetricCluster(motion = motion, battery = state.battery)

        ConsumptionCard(
            motion = motion,
            sessionWhPerKm = state.sessionWhPerKm,
            recentSpeeds = recentSpeeds
        )

        OdometerStrip(motion = motion, units = units, uptimeSeconds = state.uptimeSeconds)
    }

    if (state.sheetOpen) {
        VehicleSheet(
            saved = state.savedVehicles,
            activeId = state.vehicle?.id,
            onSwitch = component::onSwitchVehicle,
            // RideDashboardComponent has no dedicated "add vehicle" hook (that
            // flow lives behind Settings for this screen) — routing there
            // keeps the button a real action rather than a dead no-op.
            onAdd = component::onOpenSettings,
            onDisconnect = component::onDisconnect,
            onDismiss = component::onSheetDismiss
        )
    }
}

private const val SPARKLINE_MAX_POINTS = 40

// Mirrors the ESC/MOTOR bands in SecondaryGaugeMapper (kept private to that
// mapper's own metric-selection logic) — duplicated as plain thresholds here
// because the 2x2 cluster always shows both temps regardless of which one the
// rider picked as their secondary gauge.
private const val ESC_WARN_C = 70f
private const val ESC_CRITICAL_C = 85f
private const val MOTOR_WARN_C = 85f
private const val MOTOR_CRITICAL_C = 100f

private fun tempSeverity(c: Float, warn: Float, critical: Float, known: Boolean = true): DutyLevel = when {
    !known -> DutyLevel.NORMAL
    c >= critical -> DutyLevel.CRITICAL
    c >= warn -> DutyLevel.WARN
    else -> DutyLevel.NORMAL
}

@Composable
private fun severityColor(level: DutyLevel): Color = when (level) {
    DutyLevel.NORMAL -> MaterialTheme.colorScheme.primary
    DutyLevel.WARN -> MaterialTheme.colorScheme.tertiary
    DutyLevel.CRITICAL -> MaterialTheme.colorScheme.error
}

@Composable
private fun RideHero(state: RideDashboardComponent.State, vehicleMaxSpeed: Float) {
    val motion = state.motion
    val secondary = state.secondaryReadout
    val speedFraction = motion.speedKmh / vehicleMaxSpeed
    val secondaryColor = severityColor(secondary.severity)

    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        RadialGauge(
            speedFraction = speedFraction,
            secondaryFraction = secondary.fraction,
            speedColor = MaterialTheme.colorScheme.primary,
            secondaryColor = secondaryColor,
            trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(250.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = if (motion.speedKnown) UnitFormatter.speedUnit(state.units).uppercase()
                    else stringResource(Res.string.ride_speed_unknown),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 2.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
                Text(
                    text = if (motion.speedKnown) UnitFormatter.speed(motion.speedKmh, state.units) else "—",
                    style = MaterialTheme.typography.displayLarge.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = secondary.label,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = secondary.value,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = secondaryColor
                    )
                    Text(
                        text = secondary.unit,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}

@Composable
private fun MetricCluster(motion: ControllerData, battery: BmsData) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)
        ) {
            MetricCard(
                label = stringResource(Res.string.ride_power),
                value = "${formatFixed(motion.powerW / 1000f, 1)} kW",
                sub = "${formatSigned(motion.batteryCurrentA, 1)} A",
                modifier = Modifier.weight(1f).fillMaxHeight()
            )
            MetricCard(
                label = stringResource(Res.string.ride_battery),
                value = if (battery.socKnown) "${battery.soc.roundToInt()}%" else "—",
                sub = "${formatFixed(battery.voltage, 1)} V",
                modifier = Modifier.weight(1f).fillMaxHeight()
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)
        ) {
            TempMetricCard(
                label = stringResource(Res.string.ride_esc_temp),
                valueC = motion.escTempC,
                known = true,
                severity = tempSeverity(motion.escTempC, ESC_WARN_C, ESC_CRITICAL_C),
                modifier = Modifier.weight(1f).fillMaxHeight()
            )
            TempMetricCard(
                label = stringResource(Res.string.ride_motor_temp),
                valueC = motion.motorTempC,
                known = motion.hasMotorTemp,
                severity = tempSeverity(motion.motorTempC, MOTOR_WARN_C, MOTOR_CRITICAL_C, motion.hasMotorTemp),
                modifier = Modifier.weight(1f).fillMaxHeight()
            )
        }
    }
}

@Composable
private fun TempMetricCard(
    label: String,
    valueC: Float,
    known: Boolean,
    severity: DutyLevel,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {
        MetricCard(
            label = label,
            value = if (known) "${valueC.roundToInt()}°C" else "—",
            modifier = Modifier.fillMaxSize()
        )
        // Small severity dot, top-right — the mockup's `.tstripe` corner marker.
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(10.dp)
                .size(8.dp)
                .clip(CircleShape)
                .background(severityColor(severity))
        )
    }
}

@Composable
private fun ConsumptionCard(
    motion: ControllerData,
    sessionWhPerKm: Float?,
    recentSpeeds: List<Float>
) {
    val instantWhPerKm = RideMetrics.instantWhPerKm(motion.powerW, motion.speedKmh)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(12.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = stringResource(Res.string.ride_consumption).uppercase(),
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
            )
            if (sessionWhPerKm != null) {
                Text(
                    text = stringResource(Res.string.ride_consumption_avg, formatFixed(sessionWhPerKm, 1)),
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = instantWhPerKm?.let { formatFixed(it, 1) } ?: "—",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                ),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = " Wh/km",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 3.dp)
            )
        }
        Spacer(Modifier.height(6.dp))
        SparklineGraph(
            values = recentSpeeds,
            modifier = Modifier.fillMaxWidth().height(28.dp)
        )
    }
}

@Composable
private fun OdometerStrip(motion: ControllerData, units: UnitSystem, uptimeSeconds: Long) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        StripCell(
            label = stringResource(Res.string.ride_odometer),
            value = "${UnitFormatter.distance(motion.odometerKm, units)} ${UnitFormatter.distanceUnit(units)}",
            modifier = Modifier.weight(1f)
        )
        StripDivider()
        StripCell(
            label = stringResource(Res.string.ride_trip),
            value = "${UnitFormatter.distance(motion.tripKm, units)} ${UnitFormatter.distanceUnit(units)}",
            modifier = Modifier.weight(1f)
        )
        StripDivider()
        StripCell(
            label = stringResource(Res.string.ride_uptime),
            value = formatUptime(uptimeSeconds),
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun StripDivider() {
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    )
}

@Composable
private fun StripCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label.uppercase(),
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/** "H:MM:SS", zero-padded — matches the mockup's monospace uptime cell. */
private fun formatUptime(totalSeconds: Long): String {
    val s = totalSeconds.coerceAtLeast(0L)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return "${h.toString().padStart(2, '0')}:${m.toString().padStart(2, '0')}:${sec.toString().padStart(2, '0')}"
}
