package ru.sodovaya.volty.presentation.video

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import ru.sodovaya.volty.domain.video.OverlayPalette
import volty.composeapp.generated.resources.Res
import volty.composeapp.generated.resources.video_title

/** Opens the ride camera. Opening it never starts a recording; only its shutter does. */
@Composable
expect fun rememberRideVideoOpener(): () -> Unit

/**
 * A1: the dashboards' own 36 dp circle in a 48 dp slot, so it shares its neighbours' size and step, with a red dot
 * that says "record". [fill] and [tint] are the host dashboard's. **Retraction:** this used to be a 40 dp Material
 * tonal button set beside the HUD's circles, a different size and colour in the same row.
 */
@Composable
fun RideVideoButton(fill: Color, tint: Color, modifier: Modifier = Modifier) {
    val open = rememberRideVideoOpener()
    Box(
        modifier = modifier.clickable(role = Role.Button, onClick = open).minimumInteractiveComponentSize(),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(fill), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Videocam, contentDescription = stringResource(Res.string.video_title), tint = tint, modifier = Modifier.size(18.dp))
        }
        Box(Modifier.size(36.dp)) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .size(11.dp)
                    .clip(CircleShape)
                    .background(Color(OverlayPalette.REC))
                    .border(1.5.dp, Color(OverlayPalette.PANEL), CircleShape),
            )
        }
    }
}
