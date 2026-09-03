package ru.sodovaya.volty.presentation.navigation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.stringResource
import ru.sodovaya.volty.domain.navigation.PlaceCandidate
import ru.sodovaya.volty.domain.navigation.routing.RouteStyle
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
import volty.composeapp.generated.resources.navigation_route_profile
import volty.composeapp.generated.resources.navigation_route_style_curvy
import volty.composeapp.generated.resources.navigation_route_style_fast_highways
import volty.composeapp.generated.resources.navigation_route_style_fast_without_highways
import volty.composeapp.generated.resources.navigation_route_style_max_curvy_touring
import volty.composeapp.generated.resources.navigation_route_top_speed
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
    val onRouteStyleChanged: (RouteStyle) -> Unit = {},
    val onTopSpeedChanged: (Int) -> Unit = {},
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
    val colors = MaterialTheme.colorScheme
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    Box(modifier = modifier) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 560.dp)
                .heightIn(max = 440.dp)
                .navigationBarsPadding()
                .padding(bottom = LightNavigationDockPolicy.plannerBottomPadding(imeVisible).dp)
                .animateContentSize(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(
                containerColor = colors.surfaceContainer.copy(alpha = 0.96f),
            ),
            border = BorderStroke(1.dp, colors.primary.copy(alpha = 0.35f)),
            elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        ) {
            Column(
                modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(
                    if (model.phase == NavigationUiPhase.ROUTE_READY) 6.dp else 10.dp,
                ),
            ) {
            NavigationHeader(
                title = stringResource(Res.string.navigation_destination),
                onClose = callbacks.onStopNavigation,
            )
            AnimatedVisibility(
                visible = model.locationBanner != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                NavigationLocationBanner(model, callbacks)
            }
            AnimatedContent(
                targetState = model.phase,
                transitionSpec = {
                    (fadeIn() + expandVertically()).togetherWith(fadeOut() + shrinkVertically())
                },
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
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.Navigation, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
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

    OutlinedTextField(
        value = model.query,
        onValueChange = callbacks.onQueryChanged,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text(stringResource(Res.string.navigation_search_hint)) },
    )

    RoutingOptions(
        model = model,
        callbacks = callbacks,
    )

    AnimatedVisibility(
        visible = model.requestInFlight,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            AnimatedContent(
                targetState = model.destination != null,
                label = "navigation_request_kind",
            ) { buildingRoute ->
                Text(
                    if (buildingRoute) {
                        stringResource(Res.string.navigation_route_loading)
                    } else {
                        stringResource(Res.string.navigation_searching)
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    AnimatedVisibility(
        visible = model.searchResults.isNotEmpty(),
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
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
    AnimatedVisibility(
        visible = shouldShowNavigationNoResults(model),
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        Text(stringResource(Res.string.navigation_no_results), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    AnimatedVisibility(
        visible = model.destination != null,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        model.destination?.let { destination ->
            Column(
                modifier = Modifier.fillMaxWidth().animateContentSize(),
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
                    ) {
                        Text(stringResource(Res.string.navigation_build_route))
                    }
                }
            }
        }
    }
    FailureBanner(model, callbacks)
}

@Composable
private fun RoutingOptions(
    model: NavigationUiModel,
    callbacks: LightNavigationCallbacks,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            stringResource(Res.string.navigation_route_profile),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            RouteStyle.entries.forEach { style ->
                FilterChip(
                    selected = model.routeStyle == style,
                    enabled = !model.requestInFlight,
                    onClick = { callbacks.onRouteStyleChanged(style) },
                    label = { Text(style.label()) },
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(Res.string.navigation_route_top_speed, model.topSpeedKph), fontSize = 13.sp)
        }
        Slider(
            value = model.topSpeedKph.toFloat(),
            onValueChange = { callbacks.onTopSpeedChanged(it.toInt()) },
            valueRange = 20f..130f,
            steps = 10,
            enabled = !model.requestInFlight,
        )
    }
}

@Composable
private fun RouteStyle.label(): String = when (this) {
    RouteStyle.FAST_WITH_HIGHWAYS -> stringResource(Res.string.navigation_route_style_fast_highways)
    RouteStyle.FAST_WITHOUT_HIGHWAYS -> stringResource(Res.string.navigation_route_style_fast_without_highways)
    RouteStyle.CURVY -> stringResource(Res.string.navigation_route_style_curvy)
    RouteStyle.MAX_CURVY_TOURING -> stringResource(Res.string.navigation_route_style_max_curvy_touring)
}

internal fun shouldShowNavigationNoResults(model: NavigationUiModel): Boolean =
    model.destination == null &&
        model.query.trim().length >= LightNavigationSearchPolicy.MIN_QUERY_LENGTH &&
        !model.requestInFlight &&
        model.searchResults.isEmpty() &&
        model.failureBanner == null

@Composable
private fun PlaceResult(place: PlaceCandidate, onSelected: (PlaceCandidate) -> Unit) {
    val destinationDescription = stringResource(Res.string.navigation_select_destination, place.title)
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
        Icon(Icons.Default.LocationOn, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
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
        modifier = Modifier.fillMaxWidth().animateContentSize(),
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
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        label = "navigation_route_selection",
    )
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
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
            BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
        } else null,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 1.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = alternative.selected, onClick = null)
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
private fun GuidanceDock(
    model: NavigationUiModel,
    callbacks: LightNavigationCallbacks,
    units: UnitSystem,
    nearby: LightNavigationNearby?,
    onOpenNearby: () -> Unit,
    modifier: Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val stopDescription = stringResource(Res.string.navigation_stop)
    val retryDescription = stringResource(Res.string.navigation_retry)
    Card(
        modifier = modifier
            .fillMaxWidth()
            .widthIn(max = 560.dp)
            .animateContentSize(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = colors.surfaceContainer.copy(alpha = 0.94f),
        ),
        border = BorderStroke(1.dp, colors.primary.copy(alpha = 0.35f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            AnimatedContent(
                targetState = model.phase,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "navigation_guidance_phase",
            ) { phase ->
                val phaseDescription = guidanceDescription(model, phase)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = guidanceIcon(model.maneuver, phase),
                        color = colors.primary,
                        fontSize = 28.sp,
                        lineHeight = 30.sp,
                        modifier = Modifier.semantics { contentDescription = phaseDescription },
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(1.dp),
                    ) {
                        when (phase) {
                            NavigationUiPhase.NAVIGATING -> {
                                val maneuver = model.maneuver
                                if (maneuver == null) {
                                    Text(
                                        stringResource(Res.string.navigation_location_searching),
                                        color = colors.onSurfaceVariant,
                                        maxLines = 1,
                                    )
                                } else {
                                    Text(
                                        guidancePrimaryText(maneuver),
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    maneuver.streetName
                                        ?.takeIf { it != maneuver.instruction }
                                        ?.let { streetName ->
                                            Text(
                                                streetName,
                                                color = colors.onSurfaceVariant,
                                                fontSize = 10.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    Text(
                                        stringResource(
                                            Res.string.navigation_turn_in,
                                            distanceWithUnit(maneuver.distanceText, units),
                                        ),
                                        color = colors.onSurfaceVariant,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            NavigationUiPhase.REROUTING ->
                                Text(stringResource(Res.string.navigation_rerouting), fontWeight = FontWeight.Bold)
                            NavigationUiPhase.ARRIVED ->
                                Text(stringResource(Res.string.navigation_arrived), fontWeight = FontWeight.Bold)
                            else -> Unit
                        }
                    }
                    when (phase) {
                        NavigationUiPhase.NAVIGATING,
                        NavigationUiPhase.ARRIVED ->
                            DockAction(
                                text = stringResource(Res.string.navigation_stop),
                                description = stopDescription,
                                onClick = callbacks.onStopNavigation,
                            )
                        NavigationUiPhase.REROUTING -> {
                            DockAction(
                                text = stringResource(Res.string.navigation_retry),
                                description = retryDescription,
                                enabled = model.canRetry,
                                icon = true,
                                onClick = callbacks.onRetry,
                            )
                            DockAction(
                                text = stringResource(Res.string.navigation_stop),
                                description = stopDescription,
                                onClick = callbacks.onStopNavigation,
                            )
                        }
                        else -> Unit
                    }
                }
            }

            AnimatedVisibility(
                visible = model.phase == NavigationUiPhase.NAVIGATING && model.remainingDistanceText != null,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                model.remainingDistanceText?.let { distance ->
                    AnimatedContent(
                        targetState = distance to model.remainingDurationMinutes,
                        label = "navigation_remaining",
                    ) { (remainingDistance, remainingDuration) ->
                        Text(
                            stringResource(
                                Res.string.navigation_route_option,
                                distanceWithUnit(remainingDistance, units),
                                durationText(remainingDuration ?: 1L),
                            ),
                            color = colors.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (model.phase == NavigationUiPhase.REROUTING) {
                model.failureBanner?.let { key ->
                    Text(
                        text = if (model.retryAfterSeconds != null) {
                            stringResource(Res.string.navigation_rate_limited, model.retryAfterSeconds)
                        } else copyText(key),
                        color = colors.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (model.phase == NavigationUiPhase.ARRIVED) ArrivalSoc(model)
            if (model.phase == NavigationUiPhase.NAVIGATING && nearby != null) {
                NavigationNearbySummary(nearby, onOpenNearby)
            }
        }
    }
}

@Composable
private fun DockAction(
    text: String,
    description: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    icon: Boolean = false,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.semantics { contentDescription = description },
    ) {
        if (icon) Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
        Text(text, maxLines = 1)
    }
}

@Composable
private fun NavigationNearbyDock(state: LightNavigationNearby, onOpenNearby: () -> Unit) {
    val description = stringResource(Res.string.dashboard_light_group_ride)
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
        Icon(Icons.Default.Groups, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(17.dp))
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
    Row(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .clickable(role = Role.Button, onClick = onOpenNearby)
            .semantics(mergeDescendants = true) { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            Icons.Default.Groups,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp),
        )
        Text(
            stringResource(Res.string.dashboard_light_group_online, state.onlineParticipantCount),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 10.sp,
            maxLines = 1,
        )
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
        Text(stringResource(Res.string.navigation_arrival_soc, it), color = MaterialTheme.colorScheme.primary)
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
        modifier = Modifier.fillMaxWidth().animateContentSize(),
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
    AnimatedVisibility(
        visible = model.failureBanner != null,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        val key = model.failureBanner ?: return@AnimatedVisibility
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
