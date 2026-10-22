package ru.sodovaya.volty.presentation.root

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalDensity
import androidx.activity.compose.BackHandler
import com.arkivanov.decompose.extensions.compose.stack.Children
import com.arkivanov.decompose.extensions.compose.stack.animation.fade
import com.arkivanov.decompose.extensions.compose.stack.animation.stackAnimation
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import ru.sodovaya.volty.presentation.alerts.VehicleAlertsScreen
import ru.sodovaya.volty.presentation.autoconnect.AutoConnectScreen
import ru.sodovaya.volty.presentation.dashboard.DashboardScreen
import ru.sodovaya.volty.presentation.pack.PackDetailScreen
import ru.sodovaya.volty.presentation.graph.GraphScreen
import ru.sodovaya.volty.presentation.history.*
import ru.sodovaya.volty.domain.history.favoriteSource
import ru.sodovaya.volty.presentation.permissions.PermissionsGateScreen
import ru.sodovaya.volty.presentation.picker.PickerScreen
import ru.sodovaya.volty.presentation.ride.RideDashboardScreen
import ru.sodovaya.volty.presentation.scanning.ScanningScreen
import ru.sodovaya.volty.presentation.settings.SettingsScreen
import ru.sodovaya.volty.presentation.settings.OfflineRegionDisplayNamePolicy
import ru.sodovaya.volty.presentation.nearby.VoiceSpacesNearbyScreen
import ru.sodovaya.volty.presentation.root.nearby.NearbyConversationPanelScreen
import ru.sodovaya.volty.presentation.root.nearby.nearbyConversationPanelVisible
import ru.sodovaya.volty.presentation.root.nearby.NearbyIncomingCallScreen
import ru.sodovaya.volty.presentation.root.nearby.NearbyRadioStatusLine
import ru.sodovaya.volty.presentation.root.nearby.nearbyChannelMarkers
import ru.sodovaya.volty.presentation.map.PlatformRideMapLayer
import ru.sodovaya.volty.presentation.map.RideMapScreen
import ru.sodovaya.volty.presentation.map.rideMapHostState
import ru.sodovaya.volty.presentation.map.rideMapRenderActive
import ru.sodovaya.volty.presentation.map.RIDE_MAP_FADE_MILLIS
import ru.sodovaya.volty.presentation.map.NavigationMapRenderPolicy
import ru.sodovaya.volty.presentation.map.NavigationMapScene
import ru.sodovaya.volty.presentation.map.MapCameraRequest
import ru.sodovaya.volty.presentation.map.NavigationTrailPoint
import ru.sodovaya.volty.presentation.map.RideMapTrailSample
import ru.sodovaya.volty.presentation.map.ChannelMapScreen
import ru.sodovaya.volty.presentation.common.LocalVoltyDarkTheme
import ru.sodovaya.volty.domain.model.DashboardStyle
import ru.sodovaya.volty.domain.stats.MotionReadings
import ru.sodovaya.volty.domain.location.RideLocationStatus
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionPackageRepository
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionPackageStatus
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionPreparationCoordinator
import ru.sodovaya.volty.domain.navigation.offline.OfflineMapStyleVariant
import ru.sodovaya.volty.presentation.navigation.LightNavigationCallbacks
import ru.sodovaya.volty.presentation.vehicle.VehicleEditScreen
import ru.sodovaya.volty.presentation.vehicle.wizard.SetupWizardScreen
import ru.sodovaya.volty.presentation.welcome.WelcomeScreen
import org.jetbrains.compose.resources.stringResource
import volty.composeapp.generated.resources.nearby_map_participant
import volty.composeapp.generated.resources.nearby_channel_map_unavailable
import volty.composeapp.generated.resources.nearby_map_loading
import volty.composeapp.generated.resources.nearby_map_positions
import volty.composeapp.generated.resources.Res
import volty.composeapp.generated.resources.tab_battery
import volty.composeapp.generated.resources.tab_nearby
import volty.composeapp.generated.resources.tab_ride
import volty.composeapp.generated.resources.settings_offline_download_mobile
import volty.composeapp.generated.resources.settings_offline_metered_later
import volty.composeapp.generated.resources.settings_offline_metered_text
import volty.composeapp.generated.resources.settings_offline_metered_title
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RootScreen(component: RootComponent, onOpenLocationSettings: () -> Unit = {}) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val imeBottomPx = WindowInsets.ime.getBottom(LocalDensity.current)
    BackHandler(enabled = shouldConsumeBackForIme(imeBottomPx)) {
        keyboardController?.hide()
        focusManager.clearFocus(force = false)
    }
    val stackState by component.stack.subscribeAsState()
    val active = stackState.active.instance
    val activeHistory = active as? RootComponent.Child.History
    val historyState = activeHistory?.component?.state?.collectAsState()?.value
    // Camera command numbers restart in a newly composed history screen. Its
    // viewport generation must change even when the same ride is reopened.
    val historyViewportSequence = remember { longArrayOf(0L) }
    val historyHost = remember(activeHistory?.component) {
        check(historyViewportSequence[0] < Long.MAX_VALUE)
        HistoryMapHost(activeHistory?.component ?: component, ++historyViewportSequence[0])
    }
    val historyFrame by historyHost.state.collectAsState()
    val recordedScene = if (historyState != null && historyScreenContent(historyState).phase == HistoryScreenPhase.READY)
        qualifiedHistoryMap(historyFrame, historyState.detail.ride?.favoriteSource(), historyState.detail.range) else null
    val darkTheme = LocalVoltyDarkTheme.current
    val nearby by component.nearby.state.collectAsState()
    var conversationDetailsVisible by remember { mutableStateOf(false) }
    val conversationVisible = nearbyConversationPanelVisible(nearby.voice, nearby.sharing)
    LaunchedEffect(conversationVisible) {
        if (!conversationVisible) conversationDetailsVisible = false
    }
    val channelMap = nearby.voice.channelMap
    val markerLabel = stringResource(volty.composeapp.generated.resources.Res.string.nearby_map_participant)
    val channelMarkers = nearbyChannelMarkers(channelMap,markerLabel)
    val rideAvailable by component.rideAvailable.subscribeAsState()
    val offlineRegions: OfflineRegionPackageRepository = koinInject()
    val offlinePreparation: OfflineRegionPreparationCoordinator = koinInject()
    val offlineRegionStates by offlineRegions.states.collectAsState()
    val meteredApproval = offlineRegionStates.firstOrNull {
        it.status == OfflineRegionPackageStatus.AWAITING_METERED_APPROVAL
    }
    var dismissedMeteredRegionId by remember { mutableStateOf<String?>(null) }
    val visibleMeteredApproval = meteredApproval?.takeUnless {
        it.region.regionId == dismissedMeteredRegionId
    }
    LaunchedEffect(meteredApproval?.region?.regionId) {
        dismissedMeteredRegionId = null
    }
    val offlineActionScope = rememberCoroutineScope()
    val activeRide = active as? RootComponent.Child.Ride
    val activeRideState = activeRide?.component?.state?.collectAsState()?.value
    val navigationState by component.navigation.state.collectAsState()
    val locationState by component.navigation.locationState.collectAsState()
    var mapRecenterRequest by remember { mutableLongStateOf(0L) }
    var groupMapCameraRequest by remember { mutableLongStateOf(0L) }
    val groupMapVisible = active is RootComponent.Child.GroupMap
    val rideMapVisible = active is RootComponent.Child.Ride &&
        rideAvailable &&
        activeRideState?.style == DashboardStyle.LIGHT
    LaunchedEffect(rideMapVisible, nearby.voice.pending) {
        if (rideMapVisible && nearby.voice.pending?.let { it.confirmationRequired && !it.confirmed }==true)
            conversationDetailsVisible = true
    }
    val ownFix = (locationState.status as? RideLocationStatus.Available)?.fix
    val freshGpsSpeedKmh = ownFix
        ?.takeIf { navigationState.locationStatus == ru.sodovaya.volty.presentation.navigation.LocationUiStatus.FRESH }
        ?.speedMetersPerSecond
        ?.times(3.6)
        ?.toFloat()
    val trail = remember(activeRideState?.vehicle?.id) { mutableStateListOf<NavigationTrailPoint>() }
    LaunchedEffect(rideMapVisible, activeRideState?.vehicle?.id, ownFix?.capturedAtEpochMillis) {
        if (rideMapVisible && ownFix != null && trail.lastOrNull()?.sample?.timestampMillis != ownFix.capturedAtEpochMillis) {
            trail += NavigationTrailPoint(
                coordinate = ownFix.coordinate,
                sample = RideMapTrailSample(
                    latitude = ownFix.coordinate.latitude,
                    longitude = ownFix.coordinate.longitude,
                    timestampMillis = ownFix.capturedAtEpochMillis,
                    accuracyMeters = ownFix.accuracyMeters.toFloat(),
                    speedMetersPerSecond = ownFix.speedMetersPerSecond?.toFloat(),
                ),
            )
            while (trail.size > 240) trail.removeAt(0)
        }
    }
    val mapHost = rideMapHostState(
        rideAvailable = rideAvailable,
        activeScreen = when {
            groupMapVisible -> RideMapScreen.GROUP_MAP
            active is RootComponent.Child.Ride -> RideMapScreen.RIDE
            active is RootComponent.Child.Dashboard -> RideMapScreen.BATTERY
            active is RootComponent.Child.Nearby -> RideMapScreen.NEARBY
            activeHistory != null -> RideMapScreen.HISTORY
            else -> RideMapScreen.OTHER
        },
        activeStyle = activeRideState?.style,
    )
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val granted = component.navigation.locationPermissions.all { grants[it] == true }
        component.navigation.onLocationPermissionResult(granted)
    }
    val requestLocationPermission = {
        val permissions = component.navigation.locationPermissions
        if (permissions.isEmpty()) {
            component.navigation.onLocationPermissionResult(true)
        } else {
            locationPermissionLauncher.launch(permissions.toTypedArray())
        }
    }
    val locationPermissionRequired = locationState.status is RideLocationStatus.PermissionRequired
    LaunchedEffect(rideMapVisible) {
        component.navigation.onMapVisibilityChanged(rideMapVisible)
    }
    LaunchedEffect(groupMapVisible, channelMap.operation, channelMap.session, channelMarkers.size) {
        // The channel audience can arrive after opening the map. Re-fit when the
        // visible point count changes, but
        // keep the request stable while locations are merely refreshed so a
        // user's manual pan is never snapped back.
        if (groupMapVisible && channelMarkers.isNotEmpty()) {
            groupMapCameraRequest++
        }
    }
    // Preparation belongs to the map host lifecycle: a non-null fix starts one
    // keyed request; later GPS samples and recompositions cannot start another.
    LaunchedEffect(rideMapVisible, ownFix != null, darkTheme, offlineRegionStates.size) {
        if (rideMapVisible) {
            ownFix?.let {
                offlinePreparation.prepareCurrentRegion(
                    coordinate = it.coordinate,
                    style = if (darkTheme) OfflineMapStyleVariant.DARK else OfflineMapStyleVariant.BRIGHT,
                )
            }
        }
    }
    LaunchedEffect(mapHost.requestLocationPermission, locationPermissionRequired) {
        if (mapHost.requestLocationPermission && locationPermissionRequired) {
            requestLocationPermission()
        }
    }
    val mapScene = if (activeHistory != null) {
        recordedScene ?: NavigationMapScene(null, emptyList(), emptyList(), emptyList(), null,
            ru.sodovaya.volty.presentation.map.RideMapFollowState(ru.sodovaya.volty.presentation.map.RideMapFollowMode.FREE), null)
    } else if (rideMapVisible) {
        NavigationMapRenderPolicy.scene(
            state = navigationState,
            ownFix = ownFix,
            trail = trail.toList(),
            participantMarkers = channelMarkers,
            cameraSequence = navigationState.requestGeneration,
            recenterSequence = mapRecenterRequest,
        )
    } else {
        NavigationMapScene(
            ownFix = null,
            trail = emptyList(),
            participantMarkers = channelMarkers,
            routes = emptyList(),
            destination = null,
            // The group map is an exploratory view. Navigation's ride-follow
            // state must not recenter it on every render frame, otherwise a
            // pan gesture appears to do nothing until the screen is closed.
            followState = if (groupMapVisible) {
                ru.sodovaya.volty.presentation.map.RideMapFollowState(
                    mode = ru.sodovaya.volty.presentation.map.RideMapFollowMode.FREE,
                )
            } else {
                navigationState.followState
            },
            cameraRequest = if (groupMapVisible && groupMapCameraRequest > 0L) {
                MapCameraRequest.FitParticipants(
                    sequence = groupMapCameraRequest,
                    points = channelMarkers.map {
                        ru.sodovaya.volty.domain.navigation.GeoCoordinate(it.latitude, it.longitude)
                    },
                )
            } else {
                null
            },
        )
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
    // Reserve space for the destination and tab bar even on short landscape windows.
    val panelMaximumHeight = maxHeight / 2
    val detailsMaximumHeight = maxHeight * 0.8f
    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f)) {
            // The map fades with the screen crossfade instead of jumping 0/1 at the
            // start of a transition, which let it flash through a fading screen.
            val mapAlpha by animateFloatAsState(
                targetValue = if (mapHost.visible) 1f else 0f,
                animationSpec = tween(RIDE_MAP_FADE_MILLIS),
                label = "rideMapAlpha",
            )
            if (mapHost.mounted) {
                PlatformRideMapLayer(
                    scene = mapScene,
                    darkTheme = darkTheme,
                    onCameraGesture = { now ->
                        if (active is RootComponent.Child.Ride) component.navigation.onCameraGesture(now)
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(mapAlpha),
                    onHistoryMediaClick = { id ->
                        activeHistory?.component?.let { owner ->
                            owner.state.value.media.items.singleOrNull { it.id == id }?.let(owner::openMedia)
                        }
                    },
                    // History renders the same required attribution in its
                    // measured bottom panel, above the system gesture area.
                    showAttribution = activeHistory == null,
                    active = rideMapRenderActive(mapHost.visible, mapAlpha),
                )
            }
            Children(
                stack = component.stack,
                animation = stackAnimation(fade(tween(RIDE_MAP_FADE_MILLIS)))
            ) { child ->
                when (val instance = child.instance) {
                    is RootComponent.Child.Loading -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) { CircularProgressIndicator() }
                    is RootComponent.Child.Welcome -> WelcomeScreen(instance.component)
                    is RootComponent.Child.Permissions -> PermissionsGateScreen(instance.component)
                    is RootComponent.Child.Scanning -> ScanningScreen(instance.component)
                    is RootComponent.Child.AutoConnect -> AutoConnectScreen(instance.component)
                    is RootComponent.Child.Picker -> PickerScreen(instance.component)
                    is RootComponent.Child.Ride -> RideDashboardScreen(
                        instance.component,
                        // The native map is hosted above at Root level so its
                        // Compose/GL surface survives tab changes. Light only
                        // needs a non-null marker to leave the HUD transparent.
                        mapLayer = if (mapHost.visible) ({}) else null,
                        onOpenBattery = { component.onTab(RootComponent.Tab.Battery) },
                        onOpenNearby = { component.onTab(RootComponent.Tab.Nearby) },
                        onOpenGroupMap = component::onOpenGroupMap,
                        onRecenterMap = {
                            mapRecenterRequest++
                            component.navigation.onRecenterRequested()
                            if (component.navigation.locationState.value.status !is RideLocationStatus.Available) {
                                requestLocationPermission()
                            }
                        },
                        gpsSpeedKmh = freshGpsSpeedKmh,
                        nearbyMap = channelMap,
                        nearbyVoice = nearby.voice,
                        nearbySharing = nearby.sharing,
                        onOpenConversation = { conversationDetailsVisible = true },
                        navigationState = navigationState,
                        navigationCallbacks = LightNavigationCallbacks(
                            onOpenPlanner = {
                                component.navigation.onPlannerRequested()
                                if (component.navigation.locationState.value.status !is RideLocationStatus.Available) {
                                    requestLocationPermission()
                                }
                            },
                            onQueryChanged = component.navigation::onQueryChanged,
                            onPlaceSelected = component.navigation::onPlaceSelected,
                            onRouteStyleChanged = component.navigation::onRouteStyleChanged,
                            onTopSpeedChanged = component.navigation::onTopSpeedChanged,
                            onAlternativeSelected = component.navigation::onAlternativeSelected,
                            onStartNavigation = component.navigation::onStartNavigation,
                            onRetry = component.navigation::onRetry,
                            onStopNavigation = component.navigation::onStopNavigation,
                            onRequestLocationPermission = requestLocationPermission,
                            onOpenLocationSettings = onOpenLocationSettings,
                        ),
                    )
                    is RootComponent.Child.Dashboard -> DashboardScreen(component = instance.component)
                    is RootComponent.Child.PackDetail -> PackDetailScreen(instance.component)
                    is RootComponent.Child.SetupWizard -> SetupWizardScreen(instance.component)
                    is RootComponent.Child.VehicleEdit -> VehicleEditScreen(instance.component)
                    is RootComponent.Child.VehicleAlerts -> VehicleAlertsScreen(instance.component)
                    is RootComponent.Child.Graph -> GraphScreen(component = instance.component, onOpenHistory = component::onOpenHistory)
                    is RootComponent.Child.History -> HistoryScreen(instance.component,
                        cursorMaxAgeMillis = { 5_000L }, gpsMaxAgeMillis = 5_000L, mapHost = historyHost,
                        vehicleNames = instance.vehicleNames.collectAsState(emptyMap()).value)
                    is RootComponent.Child.Nearby -> VoiceSpacesNearbyScreen(instance.component)
                    is RootComponent.Child.GroupMap -> ChannelMapScreen(
                        statusText = when {
                            channelMap.channelId == null -> stringResource(volty.composeapp.generated.resources.Res.string.nearby_channel_map_unavailable)
                            !channelMap.observed -> stringResource(volty.composeapp.generated.resources.Res.string.nearby_map_loading)
                            else -> stringResource(volty.composeapp.generated.resources.Res.string.nearby_map_positions,channelMarkers.size)
                        },
                        darkTheme = darkTheme,
                        onBack = component::onBack,
                    )
                    is RootComponent.Child.Settings -> SettingsScreen(instance.component)
                }
            }
        }
        Column(Modifier.fillMaxWidth().heightIn(max=panelMaximumHeight).verticalScroll(rememberScrollState())) {
            NearbyIncomingCallScreen(nearby.incoming,component.nearby.incomingActions)
            // The Light ride already owns a glass pill over its map. An earlier
            // revision added a second dock that resized the HUD on expansion.
            if (!rideMapVisible) NearbyConversationPanelScreen(
                nearby.voice, nearby.sharing, expanded=false, actions=component.nearby.actions,
                maxHeight=panelMaximumHeight, onOpenDetails={ conversationDetailsVisible = true },
            )
            // One line while an offline radio runs; the server screen has the full card.
            if (!rideMapVisible) NearbyRadioStatusLine()
        }
        // Persistent bottom tab bar — only for main destinations
        BottomTabBar(
            active = active,
            groupMapVisible = groupMapVisible,
            rideAvailable = rideAvailable,
            rideStyle = activeRideState?.style,
            onTab = { tab ->
                component.onTab(tab)
            },
            onGroupMap = component::onOpenGroupMap,
            modifier = Modifier.navigationBarsPadding()
        )
    }

    if (conversationDetailsVisible && conversationVisible) {
        ModalBottomSheet(
            onDismissRequest = { conversationDetailsVisible = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
        ) {
            NearbyConversationPanelScreen(
                nearby.voice, nearby.sharing, expanded=true, actions=component.nearby.actions,
                maxHeight=detailsMaximumHeight,
            )
        }
    }
    }
    visibleMeteredApproval?.let { region ->
        AlertDialog(
            onDismissRequest = { dismissedMeteredRegionId = region.region.regionId },
            title = { Text(stringResource(Res.string.settings_offline_metered_title, OfflineRegionDisplayNamePolicy.displayName(region.region))) },
            text = { Text(stringResource(Res.string.settings_offline_metered_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        dismissedMeteredRegionId = region.region.regionId
                        offlineActionScope.launch {
                            runCatching {
                                offlinePreparation.prepareExplicitRegionConfirmed(
                                    regionId = region.region.regionId,
                                    style = if (darkTheme) OfflineMapStyleVariant.DARK else OfflineMapStyleVariant.BRIGHT,
                                )
                            }
                        }
                    },
                ) {
                    Text(stringResource(Res.string.settings_offline_download_mobile))
                }
            },
            dismissButton = {
                TextButton(onClick = { dismissedMeteredRegionId = region.region.regionId }) {
                    Text(stringResource(Res.string.settings_offline_metered_later))
                }
            },
        )
    }
}

@Composable
private fun BottomTabBar(
    active: RootComponent.Child,
    groupMapVisible: Boolean,
    rideAvailable: Boolean,
    rideStyle: DashboardStyle?,
    onTab: (RootComponent.Tab) -> Unit,
    onGroupMap: () -> Unit,
    modifier: Modifier = Modifier
) {
    val destination = when {
        groupMapVisible -> RootChromeDestination.GROUP_MAP
        else -> when (active) {
        is RootComponent.Child.Ride -> RootChromeDestination.RIDE
        is RootComponent.Child.Dashboard -> RootChromeDestination.BATTERY
        is RootComponent.Child.Graph -> RootChromeDestination.GRAPH
        is RootComponent.Child.Nearby -> RootChromeDestination.NEARBY
        is RootComponent.Child.Settings -> RootChromeDestination.SETTINGS
        else -> RootChromeDestination.OTHER
        }
    }
    val visible = bottomTabBarVisible(destination, rideStyle)
    if (!visible) return

    // Graph is no longer a tab — it's reached from a button on either dashboard.
    // While it's on screen no tab is selected, hence the nullable.
    val current: RootComponent.Tab? = if (groupMapVisible) {
        null
    } else {
        when (active) {
            is RootComponent.Child.Ride -> RootComponent.Tab.Ride
            is RootComponent.Child.Dashboard -> RootComponent.Tab.Battery
            is RootComponent.Child.Nearby -> RootComponent.Tab.Nearby
            is RootComponent.Child.Settings -> RootComponent.Tab.Settings
            else -> null
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Ride is available for BMS-only vehicles too; controller-only metrics
        // simply remain unavailable on that limited Ride surface.
        if (rideAvailable) {
            Tab(
                stringResource(Res.string.tab_ride),
                current == RootComponent.Tab.Ride
            ) { onTab(RootComponent.Tab.Ride) }
        }
        Tab(
            stringResource(Res.string.tab_battery),
            current == RootComponent.Tab.Battery
        ) { onTab(RootComponent.Tab.Battery) }
        Tab(
            stringResource(Res.string.tab_nearby),
            current == RootComponent.Tab.Nearby
        ) { onTab(RootComponent.Tab.Nearby) }
        if (shouldShowGroupMapTab(destination, groupMapVisible)) {
            Tab("Карта", groupMapVisible, onGroupMap)
        }
        Tab("⚙", current == RootComponent.Tab.Settings) { onTab(RootComponent.Tab.Settings) }
    }
}

@Composable
private fun RowScope.Tab(label: String, active: Boolean, onClick: () -> Unit) {
    val bg = if (active) MaterialTheme.colorScheme.primary else Color.Transparent
    val fg = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .weight(1f)
            .clip(
                RoundedCornerShape(
                    if (active) 16.dp else 20.dp,
                    if (active) 24.dp else 20.dp,
                    if (active) 16.dp else 20.dp,
                    if (active) 24.dp else 20.dp
                )
            )
            .background(bg)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = fg)
    }
}
