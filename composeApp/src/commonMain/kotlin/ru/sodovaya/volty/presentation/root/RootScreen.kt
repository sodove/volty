package ru.sodovaya.volty.presentation.root

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkivanov.decompose.extensions.compose.stack.Children
import com.arkivanov.decompose.extensions.compose.stack.animation.fade
import com.arkivanov.decompose.extensions.compose.stack.animation.stackAnimation
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import ru.sodovaya.volty.presentation.alerts.VehicleAlertsScreen
import ru.sodovaya.volty.presentation.autoconnect.AutoConnectScreen
import ru.sodovaya.volty.presentation.dashboard.DashboardScreen
import ru.sodovaya.volty.presentation.pack.PackDetailScreen
import ru.sodovaya.volty.presentation.graph.GraphScreen
import ru.sodovaya.volty.presentation.permissions.PermissionsGateScreen
import ru.sodovaya.volty.presentation.picker.PickerScreen
import ru.sodovaya.volty.presentation.ride.RideDashboardScreen
import ru.sodovaya.volty.presentation.scanning.ScanningScreen
import ru.sodovaya.volty.presentation.settings.SettingsScreen
import ru.sodovaya.volty.presentation.nearby.NearbyScreen
import ru.sodovaya.volty.presentation.map.PlatformRideMapLayer
import ru.sodovaya.volty.presentation.map.RideMapScreen
import ru.sodovaya.volty.presentation.map.rideMapHostState
import ru.sodovaya.volty.presentation.map.NavigationMapRenderPolicy
import ru.sodovaya.volty.presentation.map.NavigationMapScene
import ru.sodovaya.volty.presentation.map.NavigationTrailPoint
import ru.sodovaya.volty.presentation.map.RideMapTrailSample
import ru.sodovaya.volty.presentation.map.GroupMapScreen
import ru.sodovaya.volty.presentation.map.groupMapState
import ru.sodovaya.volty.presentation.common.LocalVoltyDarkTheme
import ru.sodovaya.volty.domain.model.DashboardStyle
import ru.sodovaya.volty.domain.stats.MotionReadings
import ru.sodovaya.volty.domain.location.RideLocationStatus
import ru.sodovaya.volty.presentation.navigation.LightNavigationCallbacks
import ru.sodovaya.volty.presentation.vehicle.VehicleEditScreen
import ru.sodovaya.volty.presentation.vehicle.wizard.SetupWizardScreen
import ru.sodovaya.volty.presentation.welcome.WelcomeScreen
import org.jetbrains.compose.resources.stringResource
import volty.composeapp.generated.resources.Res
import volty.composeapp.generated.resources.tab_battery
import volty.composeapp.generated.resources.tab_nearby
import volty.composeapp.generated.resources.tab_ride

@Composable
fun RootScreen(component: RootComponent, onOpenLocationSettings: () -> Unit = {}) {
    val stackState by component.stack.subscribeAsState()
    val active = stackState.active.instance
    val darkTheme = LocalVoltyDarkTheme.current
    val socialLiveState by component.socialLiveState.collectAsState()
    val rideAvailable by component.rideAvailable.subscribeAsState()
    val activeRide = active as? RootComponent.Child.Ride
    val activeRideState = activeRide?.component?.state?.collectAsState()?.value
    val navigationState by component.navigation.state.collectAsState()
    val locationState by component.navigation.locationState.collectAsState()
    var mapRecenterRequest by remember { mutableLongStateOf(0L) }
    val groupMapVisible = active is RootComponent.Child.GroupMap
    val rideMapVisible = active is RootComponent.Child.Ride &&
        rideAvailable &&
        activeRideState?.style == DashboardStyle.LIGHT
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
    LaunchedEffect(rideMapVisible) {
        component.navigation.onMapVisibilityChanged(rideMapVisible)
    }
    val mapScene = if (rideMapVisible) {
        NavigationMapRenderPolicy.scene(
            state = navigationState,
            ownFix = ownFix,
            trail = trail,
            participantMarkers = socialLiveState.markers,
            cameraSequence = navigationState.phase.hashCode().toLong(),
            recenterSequence = mapRecenterRequest,
        )
    } else {
        NavigationMapScene(
            ownFix = null,
            trail = emptyList(),
            participantMarkers = socialLiveState.markers,
            routes = emptyList(),
            destination = null,
            followState = navigationState.followState,
            cameraRequest = null,
        )
    }
    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f)) {
            if (mapHost.mounted) {
                PlatformRideMapLayer(
                    scene = mapScene,
                    darkTheme = darkTheme,
                    onCameraGesture = { now ->
                        if (active is RootComponent.Child.Ride) component.navigation.onCameraGesture(now)
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(if (mapHost.visible) 1f else 0f),
                )
            }
            Children(
                stack = component.stack,
                animation = stackAnimation(fade())
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
                        socialLiveState = socialLiveState,
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
                    is RootComponent.Child.Graph -> GraphScreen(component = instance.component)
                    is RootComponent.Child.Nearby -> NearbyScreen(instance.component)
                    is RootComponent.Child.GroupMap -> GroupMapScreen(
                        state = groupMapState(socialLiveState.markers),
                        darkTheme = darkTheme,
                        onBack = component::onBack,
                    )
                    is RootComponent.Child.Settings -> SettingsScreen(instance.component)
                }
            }
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
