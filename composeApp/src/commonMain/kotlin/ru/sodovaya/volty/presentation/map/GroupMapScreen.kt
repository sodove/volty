package ru.sodovaya.volty.presentation.map

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import ru.sodovaya.volty.presentation.nearby.ParticipantMarker
import ru.sodovaya.volty.domain.social.SocialParticipantMarker

internal fun SocialParticipantMarker.toGroupMapMarker(): ParticipantMarker = ParticipantMarker(
    userId = userId,
    label = label,
    latitude = latitude,
    longitude = longitude,
    accuracyMeters = accuracyMeters,
    presence = presence,
    stale = stale,
)

/** Full-screen group map. It only receives runtime state and never owns it. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun GroupMapScreen(
    state: GroupMapState,
    darkTheme: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // MapLibre owns the native surface underneath this composable. Register a
    // local back handler as well as the root overlay handler so system back is
    // consumed before the Activity can finish when the map has focus.
    BackHandler(onBack = onBack)
    // The root owns the cached MapLibre view. Rendering another
    // PlatformRideMapLayer here would try to attach the same native MapView to
    // two AndroidView parents during the route transition and crash. GroupMap
    // therefore only supplies chrome; RootScreen keeps the live map mounted
    // underneath it and feeds it the same runtime markers.
    Box(modifier = modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
            shape = MaterialTheme.shapes.large,
            tonalElevation = 4.dp,
        ) {
            TopAppBar(
                title = {
                    Text("Карта группы")
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    Icon(Icons.Default.Groups, contentDescription = null, modifier = Modifier.padding(end = 16.dp))
                    Text("${state.participantCount}", modifier = Modifier.padding(end = 16.dp))
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                ),
            )
        }
    }
}
