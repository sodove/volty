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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.LocationSearching
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.zIndex
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
import ru.sodovaya.volty.domain.social.PresenceStatus
import ru.sodovaya.volty.domain.social.SocialRideRuntime
import ru.sodovaya.volty.presentation.common.SparklineGraph
import ru.sodovaya.volty.presentation.common.LocalVoltyDarkTheme
import ru.sodovaya.volty.presentation.common.SyncLightDashboardSystemBars
import ru.sodovaya.volty.presentation.nearby.ParticipantMarker
import ru.sodovaya.volty.presentation.nearby.SocialLiveState
import ru.sodovaya.volty.presentation.navigation.LightNavigationCallbacks
import ru.sodovaya.volty.presentation.navigation.LightNavigationDockPolicy
import ru.sodovaya.volty.presentation.navigation.LightNearbyPlacement
import ru.sodovaya.volty.presentation.navigation.LightNearbyPlacementPolicy
import ru.sodovaya.volty.presentation.navigation.LightNavigationNearby
import ru.sodovaya.volty.presentation.navigation.LightNavigationOverlay
import ru.sodovaya.volty.presentation.navigation.LightNavigationPanelPlacement
import ru.sodovaya.volty.presentation.navigation.LightNavigationPanelPolicy
import ru.sodovaya.volty.presentation.navigation.LightNavigationState
import ru.sodovaya.volty.presentation.navigation.LightNavigationSurface
import ru.sodovaya.volty.presentation.navigation.LightNavigationSurfacePolicy
import ru.sodovaya.volty.presentation.navigation.LightNavigationUiMapper
import volty.composeapp.generated.resources.Res
import volty.composeapp.generated.resources.dashboard_light_battery
import volty.composeapp.generated.resources.dashboard_light_battery_current
import volty.composeapp.generated.resources.dashboard_light_controller
import volty.composeapp.generated.resources.dashboard_light_duty
import volty.composeapp.generated.resources.dashboard_light_group_map
import volty.composeapp.generated.resources.dashboard_light_group_map_unavailable
import volty.composeapp.generated.resources.dashboard_light_group_online
import volty.composeapp.generated.resources.dashboard_light_group_ride
import volty.composeapp.generated.resources.dashboard_light_motor
import volty.composeapp.generated.resources.dashboard_light_motor_current
import volty.composeapp.generated.resources.dashboard_light_speed
import volty.composeapp.generated.resources.no_battery
import volty.composeapp.generated.resources.navigation_open
import volty.composeapp.generated.resources.navigation_recenter
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal val LightHudBackground = Color(0xFF07131E)

internal data class LightHudPalette(
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

internal val LocalLightHudPalette = staticCompositionLocalOf { DarkLightHudPalette }

private fun lightHudTextScale(layoutMode: LightLayoutMode): Float = when (layoutMode) {
    LightLayoutMode.COMPACT -> 1f
    LightLayoutMode.MEDIUM -> 1.10f
    LightLayoutMode.WIDE -> 1.16f
}

private fun lightHudSp(base: Float, layoutMode: LightLayoutMode) =
    (base * lightHudTextScale(layoutMode)).sp

private fun lightTopGraphHeight(layoutMode: LightLayoutMode): Float = when (layoutMode) {
    LightLayoutMode.COMPACT -> 20f
    LightLayoutMode.MEDIUM -> 23f
    LightLayoutMode.WIDE -> 25f
}

internal data class LightGroupRideUiState(
    val groupId: ru.sodovaya.volty.domain.social.RideGroupId,
    val markers: List<ParticipantMarker>,
) {
    val participantCount: Int get() = markers.size
    val onlineParticipantCount: Int get() = markers.count { it.presence == PresenceStatus.ONLINE }
}

internal fun lightGroupRideUiState(state: SocialLiveState): LightGroupRideUiState? =
    state.groupId?.let { groupId ->
        LightGroupRideUiState(groupId = groupId, markers = state.markers)
    }

@Composable
internal fun LightRideDashboard(
    component: RideDashboardComponent,
    state: RideDashboardComponent.State,
    maxSpeedKmh: Float,
    recentSpeeds: List<Float>,
    layoutMode: LightLayoutMode,
    statusColor: Color,
    faults: List<RideDashboardComponent.FaultEntry> = emptyList(),
    modifier: Modifier = Modifier,
    mapLayer: (@Composable () -> Unit)? = null,
    onOpenBattery: () -> Unit = {},
    onOpenNearby: () -> Unit = {},
    onOpenGroupMap: () -> Unit = {},
    onRecenterMap: () -> Unit = {},
    onOpenGraph: () -> Unit = {},
    rideMaxSpeedKmh: Float = 0f,
    rideMaxDutyPercent: Float = 0f,
    gpsSpeedKmh: Float? = null,
    socialLiveState: SocialLiveState = SocialLiveState(),
    socialRuntime: SocialRideRuntime? = null,
    navigationState: LightNavigationState? = null,
    navigationCallbacks: LightNavigationCallbacks? = null,
) {
    SyncLightDashboardSystemBars(darkTheme = LocalVoltyDarkTheme.current)
    val palette = if (LocalVoltyDarkTheme.current) DarkLightHudPalette else BrightLightHudPalette
    val targetTelemetry = LightDashboardMapper.values(
        motion = state.motion,
        battery = state.battery,
        gpsSpeedKmh = gpsSpeedKmh,
    )
    val latestTelemetry = rememberUpdatedState(targetTelemetry)
    val telemetrySmoother = remember(state.vehicle?.id) { LightTelemetrySmoother() }
    var displayedTelemetry by remember(state.vehicle?.id) { mutableStateOf(targetTelemetry) }
    LaunchedEffect(state.vehicle?.id) {
        var previousFrameNanos = Long.MIN_VALUE
        while (isActive) {
            val frameNanos = withFrameNanos { it }
            val deltaMillis = if (previousFrameNanos == Long.MIN_VALUE) 0L
            else ((frameNanos - previousFrameNanos) / 1_000_000L).coerceAtMost(250L)
            previousFrameNanos = frameNanos
            displayedTelemetry = telemetrySmoother.advance(latestTelemetry.value, deltaMillis)
        }
    }
    val readouts = LightDashboardMapper.map(displayedTelemetry, state.units)
    val groupRide = lightGroupRideUiState(socialLiveState)
    val navigationSurface = navigationState?.let { currentState ->
        LightNavigationSurfacePolicy.forPhase(
            LightNavigationUiMapper.map(currentState, state.units).phase,
        )
    } ?: LightNavigationSurface.HIDDEN
    val nearbyPlacement = LightNearbyPlacementPolicy.forSurface(
        navigationSurface = navigationSurface,
        nearbyActive = groupRide != null,
    )
    val navigationPanelPlacement = LightNavigationPanelPolicy.forSurface(navigationSurface)
    val navigationNearby = groupRide?.takeIf {
        nearbyPlacement == LightNearbyPlacement.PLANNER_ROW ||
            nearbyPlacement == LightNearbyPlacement.GUIDANCE_DOCK
    }?.let { liveGroup ->
        LightNavigationNearby(
            participantCount = liveGroup.participantCount,
            onlineParticipantCount = liveGroup.onlineParticipantCount,
        )
    }
    val runtimeState = socialRuntime?.state?.collectAsState()?.value
    var groupSheetOpen by remember { mutableStateOf(false) }
    val sheetScope = rememberCoroutineScope()
    val openGroupSheet = {
        if (runtimeState?.selectedGroup != null) groupSheetOpen = true else onOpenNearby()
    }
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
                statusColor = if (state.connection is ru.sodovaya.volty.domain.model.ConnectionState.Connected) LocalLightHudPalette.current.battery else statusColor,
                temperature = readouts.controllerTemperature,
                onPillClick = component::onPillClicked,
                onNearby = openGroupSheet,
                onSettings = component::onOpenSettings,
                layoutMode = layoutMode,
            )

            LightTopGraphs(
                speed = recentSpeeds,
                duty = dutyHistory,
                speedPeak = rideMaxSpeedKmh,
                dutyPeak = rideMaxDutyPercent.takeIf { it > 0f },
                layoutMode = layoutMode,
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
                            fraction = (displayedTelemetry.speedKmh?.div(maxSpeedKmh) ?: 0f)
                                .coerceIn(0f, 1f),
                            color = LocalLightHudPalette.current.cyan,
                            layoutMode = layoutMode,
                            arcSide = LightArcSide.LEFT,
                            modifier = Modifier.weight(1f),
                        )
                        LightGauge(
                            label = stringResource(Res.string.dashboard_light_duty),
                            readout = readouts.duty,
                            fraction = (displayedTelemetry.dutyPercent?.div(100f) ?: 0f)
                                .coerceIn(0f, 1f),
                            color = LocalLightHudPalette.current.green,
                            layoutMode = layoutMode,
                            arcSide = LightArcSide.RIGHT,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            if (navigationSurface == LightNavigationSurface.GUIDANCE_DOCK &&
                navigationState != null && navigationCallbacks != null
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(lightDashboardMiddleSpacerWeight()),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    LightNavigationOverlay(
                        state = navigationState,
                        callbacks = navigationCallbacks,
                        units = state.units,
                        nearby = navigationNearby,
                        onOpenNearby = openGroupSheet,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp)
                            .padding(bottom = LightNavigationDockPolicy.guidanceHudGap().dp)
                            .zIndex(2f),
                    )
                }
            } else {
                Spacer(Modifier.weight(lightDashboardMiddleSpacerWeight()))
            }

            if (nearbyPlacement == LightNearbyPlacement.DASHBOARD_CARD) groupRide?.let { liveGroup ->
                LightGroupRideCard(
                    state = liveGroup,
                    mapAvailable = mapLayer != null,
                    onOpenNearby = openGroupSheet,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                    LightCircleButton(Icons.Default.ShowChart, "Графики", onClick = onOpenGraph)
                    Spacer(Modifier.weight(1f))
                    LightCircleButton(Icons.Default.BatteryFull, "Батарея", onClick = onOpenBattery)
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
                layoutMode = layoutMode,
            )
        }

        if (navigationPanelPlacement == LightNavigationPanelPlacement.MAP_BOTTOM_PANEL &&
            navigationSurface == LightNavigationSurface.PLANNER &&
            navigationState != null && navigationCallbacks != null
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .imePadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 10.dp)
                    .zIndex(2f),
            ) {
                LightNavigationOverlay(
                    state = navigationState,
                    callbacks = navigationCallbacks,
                    units = state.units,
                    nearby = navigationNearby,
                    onOpenNearby = openGroupSheet,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        if (navigationSurface != LightNavigationSurface.PLANNER) {
            val mapControlsPlacement = lightMapControlsPlacement()
            Box(
                modifier = Modifier.fillMaxSize().padding(end = 2.dp).zIndex(3f),
                contentAlignment = when {
                    mapControlsPlacement.edge == LightMapControlsEdge.RIGHT &&
                        mapControlsPlacement.vertical == LightMapControlsVertical.CENTER -> Alignment.CenterEnd
                    mapControlsPlacement.edge == LightMapControlsEdge.LEFT &&
                        mapControlsPlacement.vertical == LightMapControlsVertical.CENTER -> Alignment.CenterStart
                    mapControlsPlacement.edge == LightMapControlsEdge.RIGHT -> Alignment.BottomEnd
                    else -> Alignment.BottomStart
                },
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LightCircleButton(
                        Icons.Default.Navigation,
                        stringResource(Res.string.navigation_open),
                        onClick = { navigationCallbacks?.onOpenPlanner?.invoke() },
                    )
                    LightCircleButton(
                        Icons.Default.LocationSearching,
                        stringResource(Res.string.navigation_recenter),
                        onClick = onRecenterMap,
                    )
                }
            }
        }

        if (faults.isNotEmpty() && navigationSurface != LightNavigationSurface.PLANNER) {
            RideFaultsBanner(
                faults = faults,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(horizontal = 10.dp)
                    .zIndex(4f),
            )
        }

        if (groupSheetOpen && runtimeState != null) {
            LightGroupSheet(
                runtime = runtimeState,
                onDismiss = { groupSheetOpen = false },
                onStartSharing = { ttlMillis -> socialRuntime?.let { runtime ->
                    sheetScope.launch { runtime.startSharing(ttlMillis) }
                } },
                onRenewSharing = { ttlMillis -> socialRuntime?.let { runtime ->
                    sheetScope.launch { runtime.renewSharing(ttlMillis) }
                } },
                onStopSharing = { socialRuntime?.let { runtime ->
                    sheetScope.launch { runtime.stopSharing() }
                } },
                onJoinVoice = { socialRuntime?.let { runtime ->
                    val groupId = runtime.state.value.selectedGroup?.id ?: return@let
                    if (runtime.voicePermissions.isNotEmpty()) runtime.requestVoicePermission(groupId)
                    else sheetScope.launch { runtime.joinVoice() }
                } },
                onToggleMute = { socialRuntime?.let { runtime ->
                    val voice = runtime.state.value.voice as? ru.sodovaya.volty.domain.social.VoiceRoomState.Joined
                    if (voice != null) sheetScope.launch { runtime.setMuted(!voice.muted) }
                } },
                onLeaveVoice = { socialRuntime?.let { runtime ->
                    sheetScope.launch { runtime.leaveVoice() }
                } },
                onOpenNearby = onOpenNearby,
                onOpenGroupMap = {
                    groupSheetOpen = false
                    onOpenGroupMap()
                },
            )
        }
        }
    }
}
}


@Composable
private fun LightGroupRideCard(
    state: LightGroupRideUiState,
    mapAvailable: Boolean,
    onOpenNearby: () -> Unit,
) {
    val palette = LocalLightHudPalette.current
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(palette.surfaceStrong)
            .border(1.dp, palette.green.copy(alpha = 0.35f), shape)
            .clickable(onClick = onOpenNearby)
            .minimumInteractiveComponentSize()
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(palette.green.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Groups,
                contentDescription = null,
                tint = palette.green,
                modifier = Modifier.size(16.dp),
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = stringResource(Res.string.dashboard_light_group_ride),
                color = palette.text,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            Text(
                text = if (mapAvailable) {
                    stringResource(Res.string.dashboard_light_group_map, state.participantCount)
                } else {
                    stringResource(Res.string.dashboard_light_group_map_unavailable)
                },
                color = palette.muted,
                fontSize = 8.sp,
                maxLines = 1,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = stringResource(
                    Res.string.dashboard_light_group_online,
                    state.onlineParticipantCount,
                ),
                color = palette.green,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            Text("›", color = palette.muted, fontSize = 20.sp, lineHeight = 18.sp)
        }
    }
}

@Composable
private fun LightTopGraphs(
    speed: List<Float>,
    duty: List<Float>,
    speedPeak: Float,
    dutyPeak: Float?,
    layoutMode: LightLayoutMode,
) {
    // These labels describe observed samples, not the minimum scale used by
    // the gauges. In particular, a parked vehicle must not claim "max 70".
    val observedSpeedPeak = speedPeak.takeIf { it.isFinite() && it > 0f }
        ?: lightGraphPeak(speed).toFloat()
    val observedDutyPeak = dutyPeak?.takeIf { it.isFinite() && it > 0f }
        ?: lightGraphPeak(duty).toFloat()
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 1.dp),
        horizontalArrangement = Arrangement.spacedBy(28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LightTopGraph(
            label = "max ${observedSpeedPeak.roundToInt()}km/h",
            values = speed,
            color = LocalLightHudPalette.current.cyan,
            layoutMode = layoutMode,
            modifier = Modifier.weight(1f),
        )
        LightTopGraph(
            label = "max ${observedDutyPeak.roundToInt()}%",
            values = duty,
            color = LocalLightHudPalette.current.green,
            layoutMode = layoutMode,
            modifier = Modifier.weight(1f),
        )
    }
}

internal fun lightGraphPeak(values: List<Float>): Int =
    values.asSequence().filter { it.isFinite() }.maxOrNull()?.roundToInt() ?: 0

@Composable
private fun LightTopGraph(
    label: String,
    values: List<Float>,
    color: Color,
    layoutMode: LightLayoutMode,
    modifier: Modifier,
) {
    val palette = LocalLightHudPalette.current
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(
            label,
            color = LocalLightHudPalette.current.muted.copy(alpha = 0.8f),
            style = TextStyle(fontSize = lightHudSp(8.5f, layoutMode), lineHeight = lightHudSp(10f, layoutMode)),
        )
        Box(Modifier.fillMaxWidth().height(lightTopGraphHeight(layoutMode).dp)) {
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
    statusColor: Color,
    temperature: LightReadout,
    onPillClick: () -> Unit,
    onNearby: () -> Unit,
    onSettings: () -> Unit,
    layoutMode: LightLayoutMode,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LightCircleButton(Icons.Default.Groups, "Nearby", onClick = onNearby)
        Box(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onPillClick)
                .minimumInteractiveComponentSize(),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(36.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(LocalLightHudPalette.current.surfaceStrong)
                    .border(1.dp, Color(0x445A8C99), RoundedCornerShape(22.dp))
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(statusColor))
                Spacer(Modifier.size(7.dp))
                Text(name, color = LocalLightHudPalette.current.text, style = TextStyle(fontSize = lightHudSp(12f, layoutMode), lineHeight = lightHudSp(14f, layoutMode), fontWeight = FontWeight.SemiBold))
                Spacer(Modifier.size(3.dp))
                Icon(Icons.Default.ExpandMore, contentDescription = null, tint = LocalLightHudPalette.current.muted, modifier = Modifier.size(18.dp))
            }
        }
        LightCircleButton(Icons.Default.Settings, "Настройки", onClick = onSettings)
    }
    Text(
        text = temperature.value.takeIf { temperature.unit.isNotEmpty() }?.let { "$it${temperature.unit}" } ?: "—",
        color = LocalLightHudPalette.current.muted,
        style = TextStyle(fontSize = lightHudSp(9.5f, layoutMode), lineHeight = lightHudSp(11f, layoutMode)),
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
                    style = TextStyle(fontSize = 9.sp, lineHeight = 10.sp, fontWeight = FontWeight.SemiBold),
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
                Text(cell.label.uppercase(), color = cell.color, fontSize = lightHudSp(8.5f, layoutMode), fontWeight = FontWeight.Bold, maxLines = 1)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(cell.readout.value, color = LocalLightHudPalette.current.text, fontFamily = FontFamily.Monospace, fontSize = lightHudSp(16f, layoutMode), fontWeight = FontWeight.SemiBold)
                    if (cell.readout.unit.isNotEmpty()) Text(" ${cell.readout.unit}", color = LocalLightHudPalette.current.muted, fontSize = lightHudSp(9.5f, layoutMode), fontWeight = FontWeight.SemiBold)
                }
                if (cell.history.size >= 2) SparklineGraph(cell.history, color = cell.color, modifier = Modifier.fillMaxWidth().height(18.dp))
                else Spacer(Modifier.height(18.dp))
            }
        }
    }
}

private data class LightMetric(val label: String, val readout: LightReadout, val history: List<Float>, val color: Color)

@Composable
private fun LightBatteryLine(readout: LightReadout, soc: LightReadout, fraction: Float, layoutMode: LightLayoutMode) {
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
            Text(if (readout.unit.isNotEmpty()) "${readout.value} ${readout.unit}" else UNKNOWN_READOUT, color = LocalLightHudPalette.current.muted, fontSize = lightHudSp(10f, layoutMode))
            Text(if (soc.unit.isNotEmpty()) "${soc.value}${soc.unit}" else UNKNOWN_READOUT, color = LocalLightHudPalette.current.battery, fontSize = lightHudSp(11f, layoutMode), fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun LightCircleButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit = {}) {
    Box(
        modifier = Modifier
            .clickable(onClick = onClick)
            .minimumInteractiveComponentSize(),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(LocalLightHudPalette.current.surface),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = description, tint = LocalLightHudPalette.current.text.copy(alpha = 0.85f), modifier = Modifier.size(18.dp))
        }
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
    if (value == null || !value.isFinite()) return
    target += value
    while (target.size > MAX_HISTORY_POINTS) target.removeAt(0)
}
