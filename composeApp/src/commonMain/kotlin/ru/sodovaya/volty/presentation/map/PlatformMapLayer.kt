package ru.sodovaya.volty.presentation.map

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ru.sodovaya.volty.presentation.nearby.ParticipantMarker

/** Android owns the map renderer; common code owns only earned marker data. */
@Composable
expect fun PlatformRideMapLayer(
    scene: NavigationMapScene,
    darkTheme: Boolean,
    onCameraGesture: (Long) -> Unit,
    modifier: Modifier = Modifier,
    /** The camera went idle after a gesture (drag ended, fling settled), with the same clock as [onCameraGesture]. */
    onCameraGestureEnd: (Long) -> Unit = {},
    onHistoryMediaClick: (String) -> Unit = {},
    showAttribution: Boolean = true,
    /** False pauses rendering; the retained map keeps its style, tiles and camera. */
    active: Boolean = true,
)

/** Compatibility surface for the root host until Task 11 supplies the retained scene. */
@Composable
expect fun PlatformRideMapLayer(
    darkTheme: Boolean,
    markers: List<ParticipantMarker> = emptyList(),
    requestLocationPermission: Boolean = false,
    vehicleSpeedKmh: Float? = null,
    recenterRequest: Long = 0L,
    onGpsSpeedKmhChanged: (Float?) -> Unit = {},
    modifier: Modifier = Modifier,
)
