package ru.sodovaya.volty.presentation.ride

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LocationSearching
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.stringResource
import ru.sodovaya.volty.domain.stats.MotionReadings
import ru.sodovaya.volty.presentation.common.SparklineGraph
import ru.sodovaya.volty.presentation.common.LocalVoltyDarkTheme
import ru.sodovaya.volty.presentation.common.SyncLightDashboardSystemBars
import volty.composeapp.generated.resources.Res
import volty.composeapp.generated.resources.dashboard_light_battery
import volty.composeapp.generated.resources.dashboard_light_battery_current
import volty.composeapp.generated.resources.dashboard_light_controller
import volty.composeapp.generated.resources.dashboard_light_duty
import volty.composeapp.generated.resources.dashboard_light_motor
import volty.composeapp.generated.resources.dashboard_light_motor_current
import volty.composeapp.generated.resources.dashboard_light_speed
import volty.composeapp.generated.resources.no_battery
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal val LightHudBackground = Color(0xFF07131E)

private data class LightHudPalette(
    val text: Color,
    val muted: Color,
    val surface: Color,
    val surfaceStrong: Color,
    val cyan: Color,
    val green: Color,
    val battery: Color,
    val orange: Color,
    val pink: Color,
)

private val DarkLightHudPalette = LightHudPalette(
    text = Color(0xFFEAF4F8),
    muted = Color(0xFF8EA6B0),
    surface = Color(0x66132531),
    surfaceStrong = Color(0x80162A38),
    cyan = Color(0xFF27D5F5),
    green = Color(0xFF25E0B4),
    battery = Color(0xFF39E681),
    orange = Color(0xFFFFB45E),
    pink = Color(0xFFFF719C),
)

private val BrightLightHudPalette = LightHudPalette(
    text = Color(0xFF10232D),
    muted = Color(0xFF435A64),
    surface = Color(0xB8FFFFFF),
    surfaceStrong = Color(0xD9FFFFFF),
    cyan = Color(0xFF007C99),
    green = Color(0xFF00856C),
    battery = Color(0xFF008A4E),
    orange = Color(0xFFB65A00),
    pink = Color(0xFFB31B52),
)

private val LocalLightHudPalette = staticCompositionLocalOf { DarkLightHudPalette }

@Composable
internal fun LightRideDashboard(
    component: RideDashboardComponent,
    state: RideDashboardComponent.State,
    maxSpeedKmh: Float,
    recentSpeeds: List<Float>,
    layoutMode: LightLayoutMode,
    statusText: String,
    statusColor: Color,
    modifier: Modifier = Modifier,
    mapLayer: (@Composable () -> Unit)? = null,
    onOpenBattery: () -> Unit = {},
    onOpenNearby: () -> Unit = {},
    onRecenterMap: () -> Unit = {},
    gpsSpeedKmh: Float? = null,
) {
    SyncLightDashboardSystemBars(darkTheme = LocalVoltyDarkTheme.current)
    val palette = if (LocalVoltyDarkTheme.current) DarkLightHudPalette else BrightLightHudPalette
    val readouts = LightDashboardMapper.map(
        motion = state.motion,
        battery = state.battery,
        units = state.units,
        gpsSpeedKmh = gpsSpeedKmh,
    )
    val motorTemperatures = remember(state.vehicle?.id) { mutableStateListOf<Float>() }
    val controllerTemperatures = remember(state.vehicle?.id) { mutableStateListOf<Float>() }
    val motorCurrents = remember(state.vehicle?.id) { mutableStateListOf<Float>() }
    val batteryCurrents = remember(state.vehicle?.id) { mutableStateListOf<Float>() }
    val dutyHistory = remember(state.vehicle?.id) { mutableStateListOf<Float>() }

    LaunchedEffect(state.vehicle?.id, state.motion.timestamp, state.battery.timestamp) {
        if (state.motion.isConnected || state.battery.isConnected) {
            appendKnown(motorTemperatures, state.motion.motorTempC.takeIf { state.motion.hasMotorTemp })
            appendKnown(controllerTemperatures, state.motion.escTempC.takeIf { state.motion.hasEscTemp })
            appendKnown(motorCurrents, state.motion.motorCurrentA.takeIf { it != 0f || state.motion.hasPower })
            appendKnown(batteryCurrents, LightDashboardMapper.batteryCurrentA(state.motion, state.battery))
            appendKnown(dutyHistory, MotionReadings.dutyPercent(state.motion))
        }
    }

    CompositionLocalProvider(LocalLightHudPalette provides palette) {
        Box(modifier = modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (mapLayer == null) LightMapFallback()
            else mapLayer()
        }

        if (lightVignettePlacement == LightVignettePlacement.BETWEEN_MAP_AND_HUD) {
            LightMapVignette(
                darkTheme = LocalVoltyDarkTheme.current,
                modifier = Modifier.fillMaxSize(),
            )
        }

        Column(
            modifier = Modifier.fillMaxSize().statusBarsPadding()
                .padding(start = 10.dp, top = 6.dp, end = 10.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            LightTopBar(
                name = state.vehicle?.name ?: stringResource(Res.string.no_battery),
                statusText = statusText,
                statusColor = if (state.connection is ru.sodovaya.volty.domain.model.ConnectionState.Connected) LocalLightHudPalette.current.battery else statusColor,
                temperature = readouts.controllerTemperature,
                onPillClick = component::onPillClicked,
                onNearby = onOpenNearby,
                onEdit = component::onEditVehicle,
                onSettings = component::onOpenSettings,
                onDisconnect = component::onDisconnect,
            )

            LightTopGraphs(
                speed = recentSpeeds,
                duty = dutyHistory,
                maxSpeedKmh = maxSpeedKmh,
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(lightGaugeBlockHeight(layoutMode).dp)
                    .padding(top = lightGaugeBlockTopSpacing(layoutMode).dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight()
                        .align(Alignment.TopCenter),
                ) {
                    Row(
                        modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        LightGauge(
                            label = stringResource(Res.string.dashboard_light_speed),
                            readout = readouts.speed,
                            fraction = LightDashboardMapper.speedFraction(
                                state.motion,
                                maxSpeedKmh,
                                gpsSpeedKmh,
                            ),
                            color = LocalLightHudPalette.current.cyan,
                            layoutMode = layoutMode,
                            arcSide = LightArcSide.LEFT,
                            modifier = Modifier.weight(1f),
                        )
                        LightGauge(
                            label = stringResource(Res.string.dashboard_light_duty),
                            readout = readouts.duty,
                            fraction = LightDashboardMapper.dutyFraction(state.motion),
                            color = LocalLightHudPalette.current.green,
                            layoutMode = layoutMode,
                            arcSide = LightArcSide.RIGHT,
                            modifier = Modifier.weight(1f),
                        )
                    }

                    Column(
                        modifier = Modifier.align(Alignment.CenterStart).padding(start = 2.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        LightCircleButton(Icons.Default.Navigation, "Ориентация")
                        LightCircleButton(Icons.Default.LocationSearching, "Моё положение", onClick = onRecenterMap)
                    }
                }
            }

            Spacer(Modifier.weight(lightDashboardMiddleSpacerWeight()))

            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                    LightCircleButton(Icons.Default.History, "История")
                    Spacer(Modifier.weight(1f))
                    LightCircleButton(Icons.Default.BatteryFull, "Батарея", onClick = onOpenBattery)
                    Spacer(Modifier.size(8.dp))
                    LightCircleButton(Icons.Default.Groups, "Nearby", onClick = onOpenNearby)
                }
            LightTelemetryStrip(
                readouts = readouts,
                motorTemperatures = motorTemperatures,
                controllerTemperatures = controllerTemperatures,
                motorCurrents = motorCurrents,
                batteryCurrents = batteryCurrents,
                layoutMode = layoutMode,
            )
            LightBatteryLine(
                readout = readouts.batteryVoltage,
                soc = readouts.batterySoc,
                fraction = LightDashboardMapper.batteryFraction(state.battery),
            )
        }
        }
    }
}

@Composable
private fun LightTopGraphs(
    speed: List<Float>,
    duty: List<Float>,
    maxSpeedKmh: Float,
) {
    val speedPeak = speed.maxOrNull()?.roundToInt()?.takeIf { it > 0 } ?: maxSpeedKmh.roundToInt()
    val dutyPeak = duty.maxOrNull()?.roundToInt()?.takeIf { it > 0 } ?: 100
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 1.dp),
        horizontalArrangement = Arrangement.spacedBy(28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LightTopGraph(
            label = "max ${speedPeak}km/h",
            values = speed,
            color = LocalLightHudPalette.current.cyan,
            modifier = Modifier.weight(1f),
        )
        LightTopGraph(
            label = "max ${dutyPeak}%",
            values = duty,
            color = LocalLightHudPalette.current.green,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun LightTopGraph(
    label: String,
    values: List<Float>,
    color: Color,
    modifier: Modifier,
) {
    val palette = LocalLightHudPalette.current
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(
            label,
            color = LocalLightHudPalette.current.muted.copy(alpha = 0.8f),
            style = TextStyle(fontSize = 7.sp, lineHeight = 8.sp),
        )
        Box(Modifier.fillMaxWidth().height(18.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                val baseline = size.height - 2.dp.toPx()
                drawLine(
                    color = palette.muted.copy(alpha = 0.22f),
                    start = Offset(0f, baseline),
                    end = Offset(size.width, baseline),
                    strokeWidth = 1.dp.toPx(),
                )
            }
            if (values.size >= 2) {
                SparklineGraph(
                    values = values,
                    color = color,
                    glowAlpha = 0.05f,
                    minRange = values.maxOrNull()?.coerceAtLeast(1f) ?: 1f,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun LightTopBar(
    name: String,
    statusText: String,
    statusColor: Color,
    temperature: LightReadout,
    onPillClick: () -> Unit,
    onNearby: () -> Unit,
    onEdit: () -> Unit,
    onSettings: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LightCircleButton(Icons.Default.Groups, "Nearby", onClick = onNearby)
        Spacer(Modifier.weight(1f))
        Row(
            modifier = Modifier.clip(RoundedCornerShape(22.dp))
                .background(LocalLightHudPalette.current.surfaceStrong)
                .border(1.dp, Color(0x445A8C99), RoundedCornerShape(22.dp))
                .clickable(onClick = onPillClick)
                .padding(horizontal = 9.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box(Modifier.size(5.dp).clip(CircleShape).background(statusColor))
            Text(name, color = LocalLightHudPalette.current.text, style = TextStyle(fontSize = 9.sp, lineHeight = 10.sp, fontWeight = FontWeight.SemiBold))
            Icon(Icons.Default.ExpandMore, contentDescription = null, tint = LocalLightHudPalette.current.muted, modifier = Modifier.size(12.dp))
            Box(
                modifier = Modifier.size(22.dp).clickable(onClick = onEdit),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = "Редактировать",
                    tint = LocalLightHudPalette.current.muted,
                    modifier = Modifier.size(11.dp),
                )
            }
            Icon(
                Icons.Default.PowerSettingsNew,
                contentDescription = statusText,
                tint = Color(0xFFFF6871),
                modifier = Modifier.size(12.dp).clickable(onClick = onDisconnect),
            )
        }
        Spacer(Modifier.weight(1f))
        Box(
            modifier = Modifier.size(28.dp)
                .clip(CircleShape)
                .background(LocalLightHudPalette.current.surface)
                .clickable(onClick = onSettings),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Settings, contentDescription = "Настройки", tint = LocalLightHudPalette.current.muted, modifier = Modifier.size(14.dp))
        }
    }
    Text(
        text = temperature.value.takeIf { temperature.unit.isNotEmpty() }?.let { "$it${temperature.unit}" } ?: "—",
        color = LocalLightHudPalette.current.muted,
        style = TextStyle(fontSize = 8.sp, lineHeight = 9.sp),
        modifier = Modifier.fillMaxWidth().padding(top = 1.dp),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
}

@Composable
private fun LightGauge(
    label: String,
    readout: LightReadout,
    fraction: Float,
    color: Color,
    layoutMode: LightLayoutMode,
    arcSide: LightArcSide,
    modifier: Modifier,
) {
    val palette = LocalLightHudPalette.current
    val geometry = lightGaugeGeometry(layoutMode, arcSide)
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().fillMaxHeight()) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 1.5.dp.toPx()
                val arcDiameter = minOf(
                    size.width * geometry.arcWidthFraction,
                    size.height * geometry.arcHeightFraction,
                )
                val arcTop = (size.height - arcDiameter) / 2f
                val arcLeft = if (arcSide == LightArcSide.LEFT) {
                    0f
                } else {
                    size.width - arcDiameter
                }
                val arcSize = androidx.compose.ui.geometry.Size(arcDiameter, arcDiameter)
                drawArc(
                    color = palette.muted.copy(alpha = 0.25f),
                    startAngle = geometry.arcStartDegrees,
                    sweepAngle = geometry.arcSweepDegrees,
                    useCenter = false,
                    topLeft = Offset(arcLeft, arcTop),
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
                if (fraction > 0f) {
                    drawArc(
                        color = color,
                        startAngle = geometry.arcStartDegrees,
                        sweepAngle = geometry.arcSweepDegrees * fraction.coerceIn(0f, 1f),
                        useCenter = false,
                        topLeft = Offset(arcLeft, arcTop),
                        size = arcSize,
                        style = Stroke(width = stroke * 2.2f, cap = StrokeCap.Round),
                    )
                }
            }
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    label.uppercase(),
                    color = color,
                    style = TextStyle(fontSize = geometry.labelSizeSp.sp, lineHeight = 9.sp, letterSpacing = 1.sp, fontWeight = FontWeight(geometry.labelWeight)),
                )
                Text(
                    readout.value,
                    color = LocalLightHudPalette.current.text,
                    fontFamily = FontFamily.Monospace,
                    style = TextStyle(
                        fontSize = geometry.valueSizeSp.sp,
                        lineHeight = (geometry.valueSizeSp + 2f).sp,
                        fontWeight = FontWeight(geometry.valueWeight),
                    ),
                )
                if (readout.unit.isNotEmpty()) Text(
                    readout.unit,
                    color = LocalLightHudPalette.current.muted,
                    style = TextStyle(fontSize = 8.sp, lineHeight = 9.sp, fontWeight = FontWeight.SemiBold),
                )
            }
        }
    }
}

@Composable
private fun LightTelemetryStrip(
    readouts: LightTelemetryReadouts,
    motorTemperatures: List<Float>,
    controllerTemperatures: List<Float>,
    motorCurrents: List<Float>,
    batteryCurrents: List<Float>,
    layoutMode: LightLayoutMode,
) {
    val cells = listOf(
        LightMetric(stringResource(Res.string.dashboard_light_motor), readouts.motorTemperature, motorTemperatures, LocalLightHudPalette.current.pink),
        LightMetric(stringResource(Res.string.dashboard_light_controller), readouts.controllerTemperature, controllerTemperatures, LocalLightHudPalette.current.orange),
        LightMetric(stringResource(Res.string.dashboard_light_motor_current), readouts.motorCurrent, motorCurrents, LocalLightHudPalette.current.cyan),
        LightMetric(stringResource(Res.string.dashboard_light_battery_current), readouts.batteryCurrent, batteryCurrents, LocalLightHudPalette.current.battery),
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(if (layoutMode == LightLayoutMode.COMPACT) 3.dp else 6.dp)) {
        cells.forEach { cell ->
            Column(
                modifier = Modifier.weight(1f).padding(horizontal = 2.dp, vertical = 2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(cell.label.uppercase(), color = cell.color, fontSize = 7.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(cell.readout.value, color = LocalLightHudPalette.current.text, fontFamily = FontFamily.Monospace, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    if (cell.readout.unit.isNotEmpty()) Text(" ${cell.readout.unit}", color = LocalLightHudPalette.current.muted, fontSize = 8.sp, fontWeight = FontWeight.SemiBold)
                }
                if (cell.history.size >= 2) SparklineGraph(cell.history, color = cell.color, modifier = Modifier.fillMaxWidth().height(15.dp))
                else Spacer(Modifier.height(15.dp))
            }
        }
    }
}

private data class LightMetric(val label: String, val readout: LightReadout, val history: List<Float>, val color: Color)

@Composable
private fun LightBatteryLine(readout: LightReadout, soc: LightReadout, fraction: Float) {
    val palette = LocalLightHudPalette.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 5.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Canvas(Modifier.fillMaxWidth().height(13.dp)) {
            val y = size.height / 2f
            drawLine(palette.muted.copy(alpha = 0.35f), Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
            if (fraction > 0f) drawLine(palette.battery, Offset(0f, y), Offset(size.width * fraction, y), strokeWidth = 2.dp.toPx())
            repeat(11) { i ->
                val x = size.width * i / 10f
                drawLine(palette.muted.copy(alpha = 0.55f), Offset(x, y - 4.dp.toPx()), Offset(x, y + 4.dp.toPx()), strokeWidth = 1.dp.toPx())
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(if (readout.unit.isNotEmpty()) "${readout.value} ${readout.unit}" else UNKNOWN_READOUT, color = LocalLightHudPalette.current.muted, fontSize = 9.sp)
            Text(if (soc.unit.isNotEmpty()) "${soc.value}${soc.unit}" else UNKNOWN_READOUT, color = LocalLightHudPalette.current.battery, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun LightCircleButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit = {}) {
    Box(
        modifier = Modifier.size(28.dp)
            .clip(CircleShape)
            .background(LocalLightHudPalette.current.surface)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = LocalLightHudPalette.current.text.copy(alpha = 0.85f), modifier = Modifier.size(14.dp))
    }
}

@Composable
private fun LightMapFallback() {
    Canvas(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF081725), Color(0xFF142B33))))) {
        repeat(10) { i ->
            val x = size.width * (i + 1) / 11f
            drawLine(Color(0xFF28424B).copy(alpha = 0.35f), Offset(x, 0f), Offset(x - size.width * 0.28f, size.height), strokeWidth = 1.dp.toPx())
        }
        repeat(8) { i ->
            val y = size.height * (i + 1) / 9f
            drawLine(Color(0xFF28424B).copy(alpha = 0.35f), Offset(0f, y), Offset(size.width, y + size.height * 0.12f), strokeWidth = 1.dp.toPx())
        }
        val road = Path().apply {
            moveTo(-20f, size.height * 0.76f)
            cubicTo(size.width * 0.25f, size.height * 0.52f, size.width * 0.58f, size.height * 0.67f, size.width + 20f, size.height * 0.35f)
        }
        drawPath(road, Color(0xFF6A8B91).copy(alpha = 0.28f), style = Stroke(width = 12.dp.toPx(), cap = StrokeCap.Round))
        drawPath(road, Color(0xFF243D45).copy(alpha = 0.9f), style = Stroke(width = 7.dp.toPx(), cap = StrokeCap.Round))
    }
}

@Composable
private fun LightMapVignette(darkTheme: Boolean, modifier: Modifier = Modifier) {
    val spec = lightMapOverlaySpec(darkTheme)
    val tint = if (spec.fallbackTone == LightVignetteTone.DARK) {
        Color(0xFF07131E)
    } else {
        Color.White
    }
    Canvas(modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = sqrt(center.x * center.x + center.y * center.y)
        drawRect(
            brush = Brush.radialGradient(
                0f to tint.copy(alpha = spec.vignetteCenterAlpha),
                spec.clearUntilFraction to tint.copy(alpha = spec.vignetteCenterAlpha),
                spec.edgeStartFraction to tint.copy(alpha = spec.vignetteEdgeAlpha * 0.55f),
                1f to tint.copy(alpha = spec.vignetteEdgeAlpha),
                center = center,
                radius = radius,
            ),
        )
    }
}

private const val MAX_HISTORY_POINTS = 40

private fun appendKnown(target: MutableList<Float>, value: Float?) {
    if (value == null) return
    target += value
    while (target.size > MAX_HISTORY_POINTS) target.removeAt(0)
}
