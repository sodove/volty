package ru.sodovaya.volty.presentation.navigation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.stringResource
import ru.sodovaya.volty.domain.navigation.PlaceCandidate
import ru.sodovaya.volty.domain.navigation.RouteProfile
import ru.sodovaya.volty.util.UnitFormatter
import ru.sodovaya.volty.util.UnitSystem
import volty.composeapp.generated.resources.Res
import volty.composeapp.generated.resources.navigation_allow_location
import volty.composeapp.generated.resources.navigation_arrival_soc
import volty.composeapp.generated.resources.navigation_arrival_soc_unknown
import volty.composeapp.generated.resources.navigation_arrived
import volty.composeapp.generated.resources.navigation_confirm_profile
import volty.composeapp.generated.resources.navigation_destination
import volty.composeapp.generated.resources.navigation_duration_hours_minutes
import volty.composeapp.generated.resources.navigation_duration_minutes
import volty.composeapp.generated.resources.navigation_location_fresh_required
import volty.composeapp.generated.resources.navigation_location_permission_denied
import volty.composeapp.generated.resources.navigation_location_permission_required
import volty.composeapp.generated.resources.navigation_location_poor_accuracy
import volty.composeapp.generated.resources.navigation_location_provider_disabled
import volty.composeapp.generated.resources.navigation_location_searching
import volty.composeapp.generated.resources.navigation_location_stale
import volty.composeapp.generated.resources.navigation_no_results
import volty.composeapp.generated.resources.navigation_profile_bicycle
import volty.composeapp.generated.resources.navigation_profile_bicycle_description
import volty.composeapp.generated.resources.navigation_profile_light_ev
import volty.composeapp.generated.resources.navigation_profile_light_ev_description
import volty.composeapp.generated.resources.navigation_profile_motor_scooter
import volty.composeapp.generated.resources.navigation_profile_motor_scooter_description
import volty.composeapp.generated.resources.navigation_profile_title
import volty.composeapp.generated.resources.navigation_retry
import volty.composeapp.generated.resources.navigation_route_alternatives
import volty.composeapp.generated.resources.navigation_route_loading
import volty.composeapp.generated.resources.navigation_route_option
import volty.composeapp.generated.resources.navigation_search_hint
import volty.composeapp.generated.resources.navigation_search_offline
import volty.composeapp.generated.resources.navigation_searching
import volty.composeapp.generated.resources.navigation_start
import volty.composeapp.generated.resources.navigation_stop
import volty.composeapp.generated.resources.navigation_title
import volty.composeapp.generated.resources.navigation_turn_in
import volty.composeapp.generated.resources.navigation_no_route
import volty.composeapp.generated.resources.navigation_route_offline
import volty.composeapp.generated.resources.navigation_rate_limited
import volty.composeapp.generated.resources.navigation_provider_unavailable
import volty.composeapp.generated.resources.navigation_invalid_request
import volty.composeapp.generated.resources.navigation_malformed_response
import volty.composeapp.generated.resources.navigation_rerouting
import volty.composeapp.generated.resources.navigation_soc_bms_disconnected
import volty.composeapp.generated.resources.navigation_soc_packs_partial
import volty.composeapp.generated.resources.navigation_soc_unearned
import volty.composeapp.generated.resources.navigation_soc_capacity_unearned
import volty.composeapp.generated.resources.navigation_soc_telemetry_stale
import volty.composeapp.generated.resources.navigation_soc_consumption_unearned

data class LightNavigationCallbacks(
    val onOpenPlanner: () -> Unit = {},
    val onQueryChanged: (String) -> Unit = {},
    val onPlaceSelected: (PlaceCandidate) -> Unit = {},
    val onProfileSelected: (RouteProfile) -> Unit = {},
    val onProfileConfirmed: () -> Unit = {},
    val onAlternativeSelected: (String) -> Unit = {},
    val onStartNavigation: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onStopNavigation: () -> Unit = {},
    val onRequestLocationPermission: () -> Unit = {},
    val onOpenLocationSettings: () -> Unit = {},
)

@Composable
internal fun LightNavigationOverlay(
    state: LightNavigationState,
    callbacks: LightNavigationCallbacks,
    units: UnitSystem,
    modifier: Modifier = Modifier,
) {
    val model = LightNavigationUiMapper.map(state, units)
    if (model.phase == NavigationUiPhase.IDLE) return

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        tonalElevation = 8.dp,
        shadowElevation = 8.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            NavigationHeader(
                title = when (model.phase) {
                    NavigationUiPhase.NAVIGATING,
                    NavigationUiPhase.REROUTING,
                    NavigationUiPhase.ARRIVED -> stringResource(Res.string.navigation_title)
                    else -> stringResource(Res.string.navigation_destination)
                },
                onClose = callbacks.onStopNavigation,
            )
            NavigationLocationBanner(model, callbacks)
            when (model.phase) {
                NavigationUiPhase.PLANNING -> PlanningContent(model, callbacks)
                NavigationUiPhase.ROUTE_READY -> RouteReadyContent(model, callbacks, units)
                NavigationUiPhase.NAVIGATING -> GuidanceContent(model, callbacks, units)
                NavigationUiPhase.REROUTING -> ReroutingContent(model, callbacks)
                NavigationUiPhase.ARRIVED -> ArrivedContent(model, callbacks)
                NavigationUiPhase.IDLE -> Unit
            }
        }
    }
}

@Composable
private fun NavigationHeader(title: String, onClose: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.Navigation, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        IconButton(onClick = onClose) {
            Icon(Icons.Default.Close, contentDescription = null)
        }
    }
}

@Composable
private fun PlanningContent(model: NavigationUiModel, callbacks: LightNavigationCallbacks) {
    OutlinedTextField(
        value = model.query,
        onValueChange = callbacks.onQueryChanged,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text(stringResource(Res.string.navigation_search_hint)) },
    )

    if (model.requestInFlight && model.searchResults.isEmpty()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Text(
                if (model.destination != null && model.profileConfirmed) {
                    stringResource(Res.string.navigation_route_loading)
                } else {
                    stringResource(Res.string.navigation_searching)
                },
            )
        }
    }
    model.searchResults.forEach { place -> PlaceResult(place, callbacks.onPlaceSelected) }
    if (model.query.trim().length >= 3 && !model.requestInFlight && model.searchResults.isEmpty() && model.failureBanner == null) {
        Text(stringResource(Res.string.navigation_no_results), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    model.destination?.let { destination ->
        Text(
            text = destination.title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        destination.subtitle?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Text(stringResource(Res.string.navigation_profile_title), fontWeight = FontWeight.SemiBold)
        model.profiles.forEach { profile ->
            ProfileCard(profile, callbacks.onProfileSelected)
        }
        if (model.selectedProfile != null && !model.profileConfirmed) {
            TextButton(
                onClick = callbacks.onProfileConfirmed,
                enabled = model.canConfirmProfile,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(Res.string.navigation_confirm_profile)) }
        }
    }
    FailureBanner(model, callbacks)
}

@Composable
private fun PlaceResult(place: PlaceCandidate, onSelected: (PlaceCandidate) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelected(place) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Default.LocationOn, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column {
            Text(place.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            place.subtitle?.let {
                Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun ProfileCard(profile: NavigationUiProfile, onSelected: (RouteProfile) -> Unit) {
    val (title, description) = when (profile.value) {
        RouteProfile.BICYCLE -> stringResource(Res.string.navigation_profile_bicycle) to
            stringResource(Res.string.navigation_profile_bicycle_description)
        RouteProfile.LIGHT_EV -> stringResource(Res.string.navigation_profile_light_ev) to
            stringResource(Res.string.navigation_profile_light_ev_description)
        RouteProfile.MOTOR_SCOOTER -> stringResource(Res.string.navigation_profile_motor_scooter) to
            stringResource(Res.string.navigation_profile_motor_scooter_description)
    }
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onSelected(profile.value) },
        colors = CardDefaults.cardColors(
            containerColor = if (profile.selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
            },
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
            FilterChip(
                selected = profile.selected,
                onClick = { onSelected(profile.value) },
                label = { Text(if (profile.confirmed) "✓" else "") },
            )
        }
    }
}

@Composable
private fun RouteReadyContent(model: NavigationUiModel, callbacks: LightNavigationCallbacks, units: UnitSystem) {
    Text(model.destination?.title.orEmpty(), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    Text(stringResource(Res.string.navigation_route_alternatives), color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(Res.string.navigation_start)) }
}

@Composable
private fun RouteOption(
    alternative: NavigationUiAlternative,
    units: UnitSystem,
    onSelected: (String) -> Unit,
) {
    val duration = durationText(alternative.durationMinutes)
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onSelected(alternative.routeId) },
        colors = CardDefaults.cardColors(
            containerColor = if (alternative.selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
            },
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (alternative.selected) "●" else "○", color = MaterialTheme.colorScheme.primary)
            Text(
                stringResource(
                    Res.string.navigation_route_option,
                    distanceWithUnit(alternative.distanceText, units),
                    duration,
                ),
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
        }
    }
}

@Composable
private fun GuidanceContent(model: NavigationUiModel, callbacks: LightNavigationCallbacks, units: UnitSystem) {
    model.maneuver?.let { maneuver ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                maneuverIcon(maneuver.icon),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(Res.string.navigation_turn_in, distanceWithUnit(maneuver.distanceText, units)),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(maneuver.streetName ?: maneuver.instruction, fontWeight = FontWeight.Bold)
                if (maneuver.streetName != null) Text(maneuver.instruction, fontSize = 12.sp)
            }
        }
    }
    model.remainingDistanceText?.let { distance ->
        Text(
            stringResource(
                Res.string.navigation_route_option,
                distanceWithUnit(distance, units),
                durationText(model.remainingDurationMinutes ?: 1L),
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    ArrivalSoc(model)
    Button(onClick = callbacks.onStopNavigation, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(Res.string.navigation_stop))
    }
}

@Composable
private fun ReroutingContent(model: NavigationUiModel, callbacks: LightNavigationCallbacks) {
    Text(stringResource(Res.string.navigation_rerouting), fontWeight = FontWeight.Bold)
    FailureBanner(model, callbacks)
    Button(onClick = callbacks.onRetry, enabled = model.canRetry, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.Refresh, contentDescription = null)
        Text(stringResource(Res.string.navigation_retry), modifier = Modifier.padding(start = 6.dp))
    }
    OutlinedButton(onClick = callbacks.onStopNavigation, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(Res.string.navigation_stop))
    }
}

@Composable
private fun ArrivedContent(model: NavigationUiModel, callbacks: LightNavigationCallbacks) {
    Text(stringResource(Res.string.navigation_arrived), fontWeight = FontWeight.Bold)
    ArrivalSoc(model)
    Button(onClick = callbacks.onStopNavigation, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(Res.string.navigation_stop))
    }
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
    val text = copyText(key)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
        when (key) {
            NavigationUiCopyKey.LOCATION_PERMISSION_REQUIRED,
            NavigationUiCopyKey.LOCATION_PERMISSION_DENIED ->
                TextButton(onClick = callbacks.onRequestLocationPermission) {
                    Text(stringResource(Res.string.navigation_allow_location))
                }
            NavigationUiCopyKey.LOCATION_PROVIDER_DISABLED ->
                TextButton(onClick = callbacks.onOpenLocationSettings) {
                    Text(stringResource(Res.string.navigation_allow_location))
                }
            else -> Unit
        }
    }
}

@Composable
private fun FailureBanner(model: NavigationUiModel, callbacks: LightNavigationCallbacks) {
    val key = model.failureBanner ?: return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = if (model.retryAfterSeconds != null) {
                stringResource(Res.string.navigation_rate_limited, model.retryAfterSeconds)
            } else copyText(key),
            color = MaterialTheme.colorScheme.error,
        )
        if (model.canRetry) {
            TextButton(onClick = callbacks.onRetry) { Text(stringResource(Res.string.navigation_retry)) }
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
    NavigationUiCopyKey.PROFILE_REQUIRED -> stringResource(Res.string.navigation_profile_title)
    NavigationUiCopyKey.PROFILE_CONFIRMATION_REQUIRED -> stringResource(Res.string.navigation_confirm_profile)
    NavigationUiCopyKey.SOC_NO_ROUTE -> stringResource(Res.string.navigation_arrival_soc_unknown, stringResource(Res.string.navigation_no_route))
    NavigationUiCopyKey.SOC_BMS_DISCONNECTED -> stringResource(Res.string.navigation_soc_bms_disconnected)
    NavigationUiCopyKey.SOC_PACKS_PARTIAL -> stringResource(Res.string.navigation_soc_packs_partial)
    NavigationUiCopyKey.SOC_UNEARNED -> stringResource(Res.string.navigation_soc_unearned)
    NavigationUiCopyKey.SOC_CAPACITY_UNEARNED -> stringResource(Res.string.navigation_soc_capacity_unearned)
    NavigationUiCopyKey.SOC_TELEMETRY_STALE -> stringResource(Res.string.navigation_soc_telemetry_stale)
    NavigationUiCopyKey.SOC_CONSUMPTION_UNEARNED -> stringResource(Res.string.navigation_soc_consumption_unearned)
}
