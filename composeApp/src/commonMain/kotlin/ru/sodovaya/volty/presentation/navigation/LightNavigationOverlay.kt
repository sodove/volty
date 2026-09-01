package ru.sodovaya.volty.presentation.navigation

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import org.jetbrains.compose.resources.stringResource
import ru.sodovaya.volty.domain.navigation.PlaceCandidate
import ru.sodovaya.volty.presentation.common.LocalVoltyDarkTheme
import ru.sodovaya.volty.presentation.map.platformNavigationGlass
import ru.sodovaya.volty.presentation.ride.LocalLightHudPalette
import ru.sodovaya.volty.util.UnitFormatter
import ru.sodovaya.volty.util.UnitSystem
import volty.composeapp.generated.resources.Res
import volty.composeapp.generated.resources.dashboard_light_group_online
import volty.composeapp.generated.resources.dashboard_light_group_ride
import volty.composeapp.generated.resources.navigation_allow_location
import volty.composeapp.generated.resources.navigation_arrival_soc
import volty.composeapp.generated.resources.navigation_arrival_soc_unknown
import volty.composeapp.generated.resources.navigation_arrived
import volty.composeapp.generated.resources.navigation_build_route
import volty.composeapp.generated.resources.navigation_close
import volty.composeapp.generated.resources.navigation_destination
import volty.composeapp.generated.resources.navigation_duration_hours_minutes
import volty.composeapp.generated.resources.navigation_duration_minutes
import volty.composeapp.generated.resources.navigation_invalid_request
import volty.composeapp.generated.resources.navigation_location_fresh_required
import volty.composeapp.generated.resources.navigation_location_permission_denied
import volty.composeapp.generated.resources.navigation_location_permission_required
import volty.composeapp.generated.resources.navigation_location_poor_accuracy
import volty.composeapp.generated.resources.navigation_location_provider_disabled
import volty.composeapp.generated.resources.navigation_location_searching
import volty.composeapp.generated.resources.navigation_location_stale
import volty.composeapp.generated.resources.navigation_malformed_response
import volty.composeapp.generated.resources.navigation_no_results
import volty.composeapp.generated.resources.navigation_no_route
import volty.composeapp.generated.resources.navigation_open_location_settings
import volty.composeapp.generated.resources.navigation_provider_unavailable
import volty.composeapp.generated.resources.navigation_rate_limited
import volty.composeapp.generated.resources.navigation_retry
import volty.composeapp.generated.resources.navigation_route_alternatives
import volty.composeapp.generated.resources.navigation_route_loading
import volty.composeapp.generated.resources.navigation_route_offline
import volty.composeapp.generated.resources.navigation_route_option
import volty.composeapp.generated.resources.navigation_rerouting
import volty.composeapp.generated.resources.navigation_search_hint
import volty.composeapp.generated.resources.navigation_search_offline
import volty.composeapp.generated.resources.navigation_searching
import volty.composeapp.generated.resources.navigation_select_destination
import volty.composeapp.generated.resources.navigation_select_route
import volty.composeapp.generated.resources.navigation_soc_bms_disconnected
import volty.composeapp.generated.resources.navigation_soc_capacity_unearned
import volty.composeapp.generated.resources.navigation_soc_consumption_unearned
import volty.composeapp.generated.resources.navigation_soc_packs_partial
import volty.composeapp.generated.resources.navigation_soc_telemetry_stale
import volty.composeapp.generated.resources.navigation_soc_unearned
import volty.composeapp.generated.resources.navigation_start
import volty.composeapp.generated.resources.navigation_stop
import volty.composeapp.generated.resources.navigation_title
import volty.composeapp.generated.resources.navigation_turn_in

data class LightNavigationCallbacks(
    val onOpenPlanner: () -> Unit = {},
    val onQueryChanged: (String) -> Unit = {},
    val onPlaceSelected: (PlaceCandidate) -> Unit = {},
    val onAlternativeSelected: (String) -> Unit = {},
    val onStartNavigation: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onStopNavigation: () -> Unit = {},
    val onRequestLocationPermission: () -> Unit = {},
    val onOpenLocationSettings: () -> Unit = {},
)

data class LightNavigationNearby(
    val participantCount: Int,
    val onlineParticipantCount: Int,
)

private data class LightNavigationGlass(
    val containerColor: Color,
    val borderColor: Color,
)

@Composable
private fun lightNavigationGlass(): LightNavigationGlass {
    val palette = LocalLightHudPalette.current
    return LightNavigationGlass(
        containerColor = palette.surfaceStrong,
        borderColor = Color(0x445A8C99),
    )
}

@Composable
internal fun LightNavigationOverlay(
    state: LightNavigationState,
    callbacks: LightNavigationCallbacks,
    units: UnitSystem,
    modifier: Modifier = Modifier,
    nearby: LightNavigationNearby? = null,
    onOpenNearby: () -> Unit = {},
) {
    val model = LightNavigationUiMapper.map(state, units)
    when (LightNavigationSurfacePolicy.forPhase(model.phase)) {
        LightNavigationSurface.HIDDEN -> Unit
        LightNavigationSurface.PLANNER -> PlannerSurface(
            model = model,
            callbacks = callbacks,
            units = units,
            nearby = nearby,
            onOpenNearby = onOpenNearby,
            modifier = modifier,
        )
        LightNavigationSurface.GUIDANCE_DOCK -> GuidanceDock(
            model = model,
            callbacks = callbacks,
            units = units,
            nearby = nearby,
            onOpenNearby = onOpenNearby,
            modifier = modifier,
        )
    }
}

@Composable
private fun PlannerSurface(
    model: NavigationUiModel,
    callbacks: LightNavigationCallbacks,
    units: UnitSystem,
    nearby: LightNavigationNearby?,
    onOpenNearby: () -> Unit,
    modifier: Modifier,
) {
    val glass = lightNavigationGlass()
    val plannerShape = RoundedCornerShape(18.dp)
    Box(modifier = modifier) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 440.dp)
                .clip(plannerShape)
                .then(platformNavigationGlass()),
            shape = plannerShape,
            colors = CardDefaults.cardColors(
                containerColor = glass.containerColor,
            ),
            border = BorderStroke(1.dp, glass.borderColor),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(
                    if (model.phase == NavigationUiPhase.ROUTE_READY) 4.dp else 10.dp,
                ),
            ) {
            NavigationHeader(
                title = stringResource(Res.string.navigation_destination),
                onClose = callbacks.onStopNavigation,
            )
            if (model.locationBanner != null) {
                NavigationLocationBanner(model, callbacks)
            }
            Crossfade(
                targetState = model.phase,
                animationSpec = tween(durationMillis = 180),
                label = "navigation_planner_content",
            ) { phase ->
                when (phase) {
                    NavigationUiPhase.PLANNING -> Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        PlanningContent(model, callbacks)
                    }
                    NavigationUiPhase.ROUTE_READY -> RouteReadyContent(model, callbacks, units)
                    else -> Unit
                }
            }
            nearby?.let { nearbyState ->
                NavigationNearbyDock(nearbyState, onOpenNearby)
            }
            }
        }
    }
}

@Composable
private fun NavigationHeader(title: String, onClose: () -> Unit) {
    val closeDescription = stringResource(Res.string.navigation_close)
    val palette = LocalLightHudPalette.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.Navigation, contentDescription = null, tint = palette.cyan)
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        IconButton(
            onClick = onClose,
            modifier = Modifier.semantics {
                contentDescription = closeDescription
            },
        ) {
            Icon(Icons.Default.Close, contentDescription = null)
        }
    }
}

@Composable
private fun PlanningContent(model: NavigationUiModel, callbacks: LightNavigationCallbacks) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val palette = LocalLightHudPalette.current

    OutlinedTextField(
        value = model.query,
        onValueChange = callbacks.onQueryChanged,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text(stringResource(Res.string.navigation_search_hint)) },
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = palette.text,
            unfocusedTextColor = palette.text,
            focusedBorderColor = palette.cyan,
            focusedLabelColor = palette.cyan,
            cursorColor = palette.cyan,
            unfocusedBorderColor = palette.muted.copy(alpha = 0.7f),
            unfocusedLabelColor = palette.muted,
        ),
    )

    if (model.requestInFlight) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = palette.cyan)
            Text(
                if (model.destination != null) {
                    stringResource(Res.string.navigation_route_loading)
                } else {
                    stringResource(Res.string.navigation_searching)
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (model.searchResults.isNotEmpty()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .height(
                    (LightNavigationSearchPolicy.visibleResultRows(model.searchResults.size) * 56).dp,
                ),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(model.searchResults, key = { it.id }) { place ->
                PlaceResult(place) {
                    keyboardController?.hide()
                    focusManager.clearFocus(force = true)
                    callbacks.onPlaceSelected(it)
                }
            }
        }
    }
    if (shouldShowNavigationNoResults(model)) {
        Text(stringResource(Res.string.navigation_no_results), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    model.destination?.let { destination ->
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = destination.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                destination.subtitle?.let {
                    Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!model.requestInFlight && model.failureBanner == null) {
                    Button(
                        onClick = callbacks.onRetry,
                        enabled = model.canRetry,
                        modifier = Modifier.fillMaxWidth(),
                        colors = navigationActionButtonColors(),
                    ) {
                        Text(stringResource(Res.string.navigation_build_route))
                    }
                }
            }
        }
    FailureBanner(model, callbacks)
}

internal fun shouldShowNavigationNoResults(model: NavigationUiModel): Boolean =
    model.destination == null &&
        model.query.trim().length >= 3 &&
        !model.requestInFlight &&
        model.searchResults.isEmpty() &&
        model.failureBanner == null

@Composable
private fun PlaceResult(place: PlaceCandidate, onSelected: (PlaceCandidate) -> Unit) {
    val destinationDescription = stringResource(Res.string.navigation_select_destination, place.title)
    val palette = LocalLightHudPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clickable(role = Role.Button) { onSelected(place) }
            .semantics(mergeDescendants = true) {
                contentDescription = destinationDescription
            }
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Default.LocationOn, contentDescription = null, tint = palette.cyan)
        Column(Modifier.weight(1f)) {
            Text(place.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            place.subtitle?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun RouteReadyContent(model: NavigationUiModel, callbacks: LightNavigationCallbacks, units: UnitSystem) {
    val startDescription = stringResource(Res.string.navigation_start)
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(model.destination?.title.orEmpty(), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(
            stringResource(Res.string.navigation_route_alternatives),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
        )
        model.alternatives.forEach { alternative ->
            RouteOption(alternative, units, callbacks.onAlternativeSelected)
        }
        if (model.locationBanner == NavigationUiCopyKey.LOCATION_PERMISSION_REQUIRED ||
            model.locationBanner == NavigationUiCopyKey.LOCATION_PERMISSION_DENIED ||
            model.locationBanner == NavigationUiCopyKey.LOCATION_FRESH_REQUIRED
        ) {
            OutlinedButton(onClick = callbacks.onRequestLocationPermission, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(Res.string.navigation_allow_location))
            }
        }
        Button(
            onClick = callbacks.onStartNavigation,
            enabled = model.canStart,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = startDescription },
            colors = navigationActionButtonColors(),
        ) {
            Text(stringResource(Res.string.navigation_start))
        }
    }
}

@Composable
private fun RouteOption(
    alternative: NavigationUiAlternative,
    units: UnitSystem,
    onSelected: (String) -> Unit,
) {
    val palette = LocalLightHudPalette.current
    val duration = durationText(alternative.durationMinutes)
    val routeDescription = stringResource(
        Res.string.navigation_select_route,
        stringResource(
            Res.string.navigation_route_option,
            distanceWithUnit(alternative.distanceText, units),
            duration,
        ),
    )
    val backgroundColor by animateColorAsState(
        targetValue = if (alternative.selected) {
            palette.cyan.copy(alpha = 0.22f)
        } else {
            palette.surface.copy(alpha = 0.72f)
        },
        label = "navigation_route_selection",
    )
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = alternative.selected,
                onClick = { onSelected(alternative.routeId) },
                role = Role.RadioButton,
            )
            .semantics(mergeDescendants = true) {
                contentDescription = routeDescription
            },
        colors = CardDefaults.cardColors(containerColor = backgroundColor),
        border = if (alternative.selected) {
            BorderStroke(1.dp, palette.cyan)
        } else null,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = LightNavigationDockPolicy.routeOptionMinHeight().dp)
                .padding(horizontal = 8.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(
                selected = alternative.selected,
                onClick = null,
                colors = RadioButtonDefaults.colors(
                    selectedColor = palette.cyan,
                    unselectedColor = palette.muted,
                ),
            )
            Text(
                stringResource(
                    Res.string.navigation_route_option,
                    distanceWithUnit(alternative.distanceText, units),
                    duration,
                ),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun navigationActionButtonColors() = ButtonDefaults.buttonColors(
    containerColor = LocalLightHudPalette.current.cyan,
    contentColor = if (LocalVoltyDarkTheme.current) Color(0xFF06222B) else Color.White,
)

@Composable
private fun GuidanceDock(
    model: NavigationUiModel,
    callbacks: LightNavigationCallbacks,
    units: UnitSystem,
    nearby: LightNavigationNearby?,
    onOpenNearby: () -> Unit,
    modifier: Modifier,
) {
    val glass = lightNavigationGlass()
    val guidanceShape = RoundedCornerShape(16.dp)
    val stopDescription = stringResource(Res.string.navigation_stop)
    val retryDescription = stringResource(Res.string.navigation_retry)
    BoxWithConstraints(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Card(
            modifier = Modifier
                .fillMaxWidth(LightNavigationDockPolicy.guidanceCardWidthFraction())
                .clip(guidanceShape)
                .then(platformNavigationGlass()),
            shape = guidanceShape,
            colors = CardDefaults.cardColors(
                containerColor = glass.containerColor,
            ),
            border = BorderStroke(1.dp, glass.borderColor),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) {
            GuidanceDockContent(
                model = model,
                phase = model.phase,
                units = units,
                nearby = nearby,
                onOpenNearby = onOpenNearby,
                onRetry = callbacks.onRetry,
                canRetry = model.canRetry,
                retryDescription = retryDescription,
                onStop = callbacks.onStopNavigation,
                stopDescription = stopDescription,
            )
        }
    }
}

@Composable
private fun GuidanceDockContent(
    model: NavigationUiModel,
    phase: NavigationUiPhase,
    units: UnitSystem,
    nearby: LightNavigationNearby?,
    onOpenNearby: () -> Unit,
    onRetry: () -> Unit,
    canRetry: Boolean,
    retryDescription: String,
    onStop: () -> Unit,
    stopDescription: String,
) {
    val colors = MaterialTheme.colorScheme
    val palette = LocalLightHudPalette.current
    val maneuver = model.maneuver
    val phaseDescription = guidanceDescription(model, phase)
    Column(modifier = Modifier.fillMaxWidth()) {
        Crossfade(
            targetState = phase,
            animationSpec = tween(durationMillis = 180),
            label = "navigation_guidance_content",
        ) { animatedPhase ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(LightNavigationDockPolicy.guidanceCardMinHeight().dp)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                GuidanceManeuverGlyph(
                    maneuver = maneuver,
                    phase = animatedPhase,
                    color = palette.cyan,
                    contentDescription = phaseDescription,
                )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                        when (animatedPhase) {
                            NavigationUiPhase.NAVIGATING -> if (maneuver == null) {
                                Text(
                                    stringResource(Res.string.navigation_location_searching),
                                    color = colors.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            } else {
                                Text(
                                    stringResource(
                                        Res.string.navigation_turn_in,
                                        distanceWithUnit(maneuver.distanceText, units),
                                    ),
                                    color = palette.cyan,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    guidancePrimaryText(maneuver),
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 15.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                maneuver.streetName
                                    ?.takeIf { it != maneuver.instruction }
                                    ?.let { streetName ->
                                        Text(
                                            streetName,
                                            color = colors.onSurfaceVariant,
                                            fontSize = 12.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                            }
                            NavigationUiPhase.REROUTING ->
                                Text(stringResource(Res.string.navigation_rerouting), fontWeight = FontWeight.SemiBold)
                            NavigationUiPhase.ARRIVED ->
                                Text(stringResource(Res.string.navigation_arrived), fontWeight = FontWeight.SemiBold)
                            else -> Unit
                        }
            }
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (animatedPhase == NavigationUiPhase.NAVIGATING) {
                    model.remainingDistanceText?.let { distance ->
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                distanceWithUnit(distance, units),
                                color = palette.cyan,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                durationText(model.remainingDurationMinutes ?: 1L),
                                color = colors.onSurfaceVariant,
                                fontSize = 11.sp,
                                maxLines = 1,
                            )
                        }
                    }
                    nearby?.let { NavigationNearbySummary(it, onOpenNearby) }
                }
                if (animatedPhase == NavigationUiPhase.REROUTING) {
                    IconButton(
                        onClick = onRetry,
                        enabled = canRetry,
                        modifier = Modifier.semantics { contentDescription = retryDescription },
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                }
            }
                GuidanceStopButton(onStop = onStop, description = stopDescription)
            }
        }
        if (phase == NavigationUiPhase.ARRIVED) ArrivalSoc(model)
        if (phase == NavigationUiPhase.REROUTING) {
            model.failureBanner?.let { key ->
                Text(
                    text = if (model.retryAfterSeconds != null) {
                        stringResource(Res.string.navigation_rate_limited, model.retryAfterSeconds)
                    } else copyText(key),
                    color = colors.error,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun NavigationNearbyDock(state: LightNavigationNearby, onOpenNearby: () -> Unit) {
    val description = stringResource(Res.string.dashboard_light_group_ride)
    val palette = LocalLightHudPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .minimumInteractiveComponentSize()
            .clickable(role = Role.Button, onClick = onOpenNearby)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Icon(Icons.Default.Groups, contentDescription = null, tint = palette.cyan, modifier = Modifier.size(17.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(Res.string.dashboard_light_group_ride), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(
                stringResource(Res.string.dashboard_light_group_online, state.onlineParticipantCount),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                maxLines = 1,
            )
        }
        Text("${state.participantCount}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 20.sp, lineHeight = 18.sp)
    }
}

@Composable
private fun NavigationNearbySummary(state: LightNavigationNearby, onOpenNearby: () -> Unit) {
    val description = stringResource(Res.string.dashboard_light_group_ride)
    val palette = LocalLightHudPalette.current
    Column(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .clickable(role = Role.Button, onClick = onOpenNearby)
            .semantics(mergeDescendants = true) { contentDescription = description },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        NavigationNearbyAvatars(state.participantCount)
        Text(
            stringResource(Res.string.dashboard_light_group_online, state.onlineParticipantCount),
            color = palette.cyan,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
private fun NavigationNearbyAvatars(participantCount: Int) {
    val colors = listOf(Color(0xFF2D9CDB), Color(0xFF38B86D), Color(0xFF9D6BD9))
    val visibleAvatars = participantCount.coerceIn(1, colors.size)
    Box(modifier = Modifier.size(width = 48.dp, height = 25.dp)) {
        repeat(visibleAvatars) { index ->
            Box(
                modifier = Modifier
                    .offset(x = (index * 13).dp)
                    .size(25.dp)
                    .clip(CircleShape)
                    .background(colors[index])
                    .border(1.dp, MaterialTheme.colorScheme.surfaceContainer, CircleShape),
            )
        }
    }
}

@Composable
private fun GuidanceStopButton(onStop: () -> Unit, description: String) {
    val palette = LocalLightHudPalette.current
    IconButton(
        onClick = onStop,
        modifier = Modifier.semantics { contentDescription = description },
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .border(1.dp, palette.cyan.copy(alpha = 0.55f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(19.dp))
        }
    }
}

@Composable
private fun GuidanceManeuverGlyph(
    maneuver: NavigationUiManeuver?,
    phase: NavigationUiPhase,
    color: Color,
    contentDescription: String,
) {
    Box(
        modifier = Modifier
            .size(width = 56.dp, height = 60.dp)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        val angle = guidanceArrowAngle(maneuver, phase)
        if (phase == NavigationUiPhase.NAVIGATING && maneuver?.icon == NavigationUiManeuverIcon.LEFT) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(48.dp),
            )
        } else if (angle == null) {
            Text(
                text = guidanceIcon(maneuver, phase),
                color = color,
                fontSize = 42.sp,
                lineHeight = 48.sp,
            )
        } else {
            Canvas(Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val shaftLength = minOf(size.width, size.height) * 0.32f
                val headLength = shaftLength * 0.42f
                val strokeWidth = 2.5.dp.toPx()
                val tip = Offset(
                    x = center.x + cos(angle.toDouble()).toFloat() * shaftLength,
                    y = center.y + sin(angle.toDouble()).toFloat() * shaftLength,
                )
                val tail = Offset(
                    x = center.x - cos(angle.toDouble()).toFloat() * shaftLength,
                    y = center.y - sin(angle.toDouble()).toFloat() * shaftLength,
                )
                drawLine(color, tail, tip, strokeWidth, cap = StrokeCap.Round)
                drawLine(
                    color,
                    tip,
                    Offset(
                        x = tip.x - cos((angle - PI.toFloat() * 0.72f).toDouble()).toFloat() * headLength,
                        y = tip.y - sin((angle - PI.toFloat() * 0.72f).toDouble()).toFloat() * headLength,
                    ),
                    strokeWidth,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color,
                    tip,
                    Offset(
                        x = tip.x - cos((angle + PI.toFloat() * 0.72f).toDouble()).toFloat() * headLength,
                        y = tip.y - sin((angle + PI.toFloat() * 0.72f).toDouble()).toFloat() * headLength,
                    ),
                    strokeWidth,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

private fun guidanceIcon(
    maneuver: NavigationUiManeuver?,
    phase: NavigationUiPhase,
): String = when (phase) {
    NavigationUiPhase.REROUTING -> "↻"
    NavigationUiPhase.ARRIVED -> "⚑"
    else -> maneuver?.let { maneuverIcon(it.icon) } ?: "·"
}

private fun guidanceArrowAngle(
    maneuver: NavigationUiManeuver?,
    phase: NavigationUiPhase,
): Float? = if (phase != NavigationUiPhase.NAVIGATING || maneuver == null) {
    null
} else {
    when (maneuver.icon) {
        NavigationUiManeuverIcon.DEPART -> -PI.toFloat() * 0.25f
        NavigationUiManeuverIcon.STRAIGHT -> -PI.toFloat() / 2f
        NavigationUiManeuverIcon.SLIGHT_LEFT -> -PI.toFloat() * 0.72f
        NavigationUiManeuverIcon.LEFT,
        NavigationUiManeuverIcon.SHARP_LEFT -> PI.toFloat()
        NavigationUiManeuverIcon.SLIGHT_RIGHT -> -PI.toFloat() * 0.28f
        NavigationUiManeuverIcon.RIGHT,
        NavigationUiManeuverIcon.SHARP_RIGHT -> 0f
        NavigationUiManeuverIcon.U_TURN,
        NavigationUiManeuverIcon.ROUNDABOUT,
        NavigationUiManeuverIcon.ARRIVE,
        NavigationUiManeuverIcon.UNKNOWN -> null
    }
}

internal fun guidancePrimaryText(maneuver: NavigationUiManeuver): String {
    val instruction = maneuver.instruction.trim()
    if (instruction.isBlank()) return maneuver.streetName.orEmpty()

    val street = maneuver.streetName?.trim()?.takeIf { it.isNotEmpty() }
        ?: return instruction
    return listOf(
        " на $street",
        " по $street",
        " к $street",
        " onto $street",
        " on $street",
        " at $street",
    ).firstOrNull(instruction::endsWith)
        ?.let(instruction::removeSuffix)
        ?.trim()
        ?: instruction
}

@Composable
private fun guidanceDescription(model: NavigationUiModel, phase: NavigationUiPhase): String = when (phase) {
    NavigationUiPhase.REROUTING -> stringResource(Res.string.navigation_rerouting)
    NavigationUiPhase.ARRIVED -> stringResource(Res.string.navigation_arrived)
    else -> model.maneuver?.instruction ?: stringResource(Res.string.navigation_title)
}

@Composable
private fun ArrivalSoc(model: NavigationUiModel) {
    model.arrivalSocPercent?.let {
        Text(stringResource(Res.string.navigation_arrival_soc, it), color = LocalLightHudPalette.current.cyan)
    } ?: model.arrivalSocReason?.let { reason ->
        Text(
            stringResource(Res.string.navigation_arrival_soc_unknown, copyText(reason)),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun NavigationLocationBanner(model: NavigationUiModel, callbacks: LightNavigationCallbacks) {
    val key = model.locationBanner ?: return
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(copyText(key), color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
        when (key) {
            NavigationUiCopyKey.LOCATION_PERMISSION_REQUIRED,
            NavigationUiCopyKey.LOCATION_PERMISSION_DENIED ->
                TextButton(onClick = callbacks.onRequestLocationPermission) {
                    Text(stringResource(Res.string.navigation_allow_location))
                }
            NavigationUiCopyKey.LOCATION_PROVIDER_DISABLED ->
                TextButton(onClick = callbacks.onOpenLocationSettings) {
                    Text(stringResource(Res.string.navigation_open_location_settings))
                }
            else -> Unit
        }
    }
}

@Composable
private fun FailureBanner(model: NavigationUiModel, callbacks: LightNavigationCallbacks) {
    model.failureBanner?.let { key ->
        val retryDescription = stringResource(Res.string.navigation_retry)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = if (model.retryAfterSeconds != null) {
                    stringResource(Res.string.navigation_rate_limited, model.retryAfterSeconds)
                } else copyText(key),
                color = MaterialTheme.colorScheme.error,
            )
            if (model.canRetry) {
                TextButton(
                    onClick = callbacks.onRetry,
                    modifier = Modifier.semantics {
                        contentDescription = retryDescription
                    },
                ) { Text(stringResource(Res.string.navigation_retry)) }
            }
        }
    }
}

@Composable
private fun durationText(minutes: Long): String = if (minutes < 60L) {
    stringResource(Res.string.navigation_duration_minutes, minutes)
} else {
    stringResource(
        Res.string.navigation_duration_hours_minutes,
        minutes / 60L,
        minutes % 60L,
    )
}

private fun distanceWithUnit(distance: String, units: UnitSystem): String =
    "$distance ${UnitFormatter.distanceUnit(units)}"

private fun maneuverIcon(icon: NavigationUiManeuverIcon): String = when (icon) {
    NavigationUiManeuverIcon.DEPART -> "↗"
    NavigationUiManeuverIcon.STRAIGHT -> "↑"
    NavigationUiManeuverIcon.SLIGHT_LEFT -> "↖"
    NavigationUiManeuverIcon.LEFT -> "←"
    NavigationUiManeuverIcon.SHARP_LEFT -> "↰"
    NavigationUiManeuverIcon.SLIGHT_RIGHT -> "↗"
    NavigationUiManeuverIcon.RIGHT -> "→"
    NavigationUiManeuverIcon.SHARP_RIGHT -> "↱"
    NavigationUiManeuverIcon.U_TURN -> "↶"
    NavigationUiManeuverIcon.ROUNDABOUT -> "↻"
    NavigationUiManeuverIcon.ARRIVE -> "⚑"
    NavigationUiManeuverIcon.UNKNOWN -> "·"
}

@Composable
private fun copyText(key: NavigationUiCopyKey): String = when (key) {
    NavigationUiCopyKey.LOCATION_PERMISSION_REQUIRED -> stringResource(Res.string.navigation_location_permission_required)
    NavigationUiCopyKey.LOCATION_PERMISSION_DENIED -> stringResource(Res.string.navigation_location_permission_denied)
    NavigationUiCopyKey.LOCATION_PROVIDER_DISABLED -> stringResource(Res.string.navigation_location_provider_disabled)
    NavigationUiCopyKey.LOCATION_SEARCHING -> stringResource(Res.string.navigation_location_searching)
    NavigationUiCopyKey.LOCATION_STALE -> stringResource(Res.string.navigation_location_stale)
    NavigationUiCopyKey.LOCATION_POOR_ACCURACY -> stringResource(Res.string.navigation_location_poor_accuracy)
    NavigationUiCopyKey.LOCATION_FRESH_REQUIRED -> stringResource(Res.string.navigation_location_fresh_required)
    NavigationUiCopyKey.SEARCH_OFFLINE -> stringResource(Res.string.navigation_search_offline)
    NavigationUiCopyKey.SEARCH_RATE_LIMITED -> stringResource(Res.string.navigation_rate_limited, 0L)
    NavigationUiCopyKey.ROUTE_OFFLINE -> stringResource(Res.string.navigation_route_offline)
    NavigationUiCopyKey.ROUTE_NO_ROUTE -> stringResource(Res.string.navigation_no_route)
    NavigationUiCopyKey.ROUTE_RATE_LIMITED -> stringResource(Res.string.navigation_rate_limited, 0L)
    NavigationUiCopyKey.ROUTE_PROVIDER_UNAVAILABLE -> stringResource(Res.string.navigation_provider_unavailable)
    NavigationUiCopyKey.ROUTE_INVALID_REQUEST -> stringResource(Res.string.navigation_invalid_request)
    NavigationUiCopyKey.ROUTE_MALFORMED_RESPONSE -> stringResource(Res.string.navigation_malformed_response)
    NavigationUiCopyKey.SOC_NO_ROUTE -> stringResource(
        Res.string.navigation_arrival_soc_unknown,
        stringResource(Res.string.navigation_no_route),
    )
    NavigationUiCopyKey.SOC_BMS_DISCONNECTED -> stringResource(Res.string.navigation_soc_bms_disconnected)
    NavigationUiCopyKey.SOC_PACKS_PARTIAL -> stringResource(Res.string.navigation_soc_packs_partial)
    NavigationUiCopyKey.SOC_UNEARNED -> stringResource(Res.string.navigation_soc_unearned)
    NavigationUiCopyKey.SOC_CAPACITY_UNEARNED -> stringResource(Res.string.navigation_soc_capacity_unearned)
    NavigationUiCopyKey.SOC_TELEMETRY_STALE -> stringResource(Res.string.navigation_soc_telemetry_stale)
    NavigationUiCopyKey.SOC_CONSUMPTION_UNEARNED -> stringResource(Res.string.navigation_soc_consumption_unearned)
}
