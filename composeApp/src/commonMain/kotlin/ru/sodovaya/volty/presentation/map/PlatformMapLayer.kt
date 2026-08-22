package ru.sodovaya.volty.presentation.map

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ru.sodovaya.volty.presentation.nearby.ParticipantMarker

/** Android owns the map renderer; common code owns only earned marker data. */
@Composable
expect fun PlatformRideMapLayer(
    darkTheme: Boolean,
    markers: List<ParticipantMarker> = emptyList(),
    requestLocationPermission: Boolean = false,
    vehicleSpeedKmh: Float? = null,
    recenterRequest: Long = 0L,
    modifier: Modifier = Modifier,
)
