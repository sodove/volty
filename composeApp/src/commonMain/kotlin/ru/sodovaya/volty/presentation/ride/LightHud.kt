package ru.sodovaya.volty.presentation.ride

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.sodovaya.volty.domain.video.OverlayPalette
import ru.sodovaya.volty.presentation.common.LocalVoltyDarkTheme
import ru.sodovaya.volty.presentation.map.LocalVoltyMapPalette
import kotlin.math.sqrt

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
    text = Color(OverlayPalette.TEXT),
    muted = Color(OverlayPalette.MUTED),
    surface = Color(OverlayPalette.SURFACE),
    surfaceStrong = Color(OverlayPalette.SURFACE_STRONG),
    cyan = Color(OverlayPalette.CYAN),
    green = Color(OverlayPalette.GREEN),
    battery = Color(OverlayPalette.BATTERY),
    orange = Color(OverlayPalette.ORANGE),
    pink = Color(OverlayPalette.PINK),
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

@Composable
internal fun lightHudPalette(): LightHudPalette {
    val map = LocalVoltyMapPalette.current
    val dark = LocalVoltyDarkTheme.current
    return (if (dark) DarkLightHudPalette else BrightLightHudPalette).copy(
        text = Color(map.text), muted = Color(map.textMuted),
        surface = Color(map.hudSurface).copy(alpha = if (dark) 0.40f else LIGHT_HUD_SURFACE_ALPHA),
        surfaceStrong = Color(map.hudSurfaceStrong).copy(alpha = if (dark) 0.50f else LIGHT_HUD_SURFACE_STRONG_ALPHA),
    )
}

internal fun lightHudSp(base: Float, layoutMode: LightLayoutMode) =
    (base * when (layoutMode) {
        LightLayoutMode.COMPACT -> 1f
        LightLayoutMode.MEDIUM -> 1.10f
        LightLayoutMode.WIDE -> 1.16f
    }).sp

@Composable
internal fun LightVehiclePill(
    name: String,
    layoutMode: LightLayoutMode,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    statusColor: Color? = null,
    subtitle: String? = null,
    onClickLabel: String? = null,
) {
    val palette = LocalLightHudPalette.current
    val shape = CircleShape
    Box(
        modifier.clickable(role = Role.Button, onClickLabel = onClickLabel, onClick = onClick)
            .minimumInteractiveComponentSize(),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.fillMaxWidth().heightIn(min = 36.dp).clip(shape).background(palette.surfaceStrong)
                .border(1.dp, palette.text.copy(alpha = 0.17f), shape)
                .padding(horizontal = 10.dp, vertical = 4.dp),
        ) {
            Column(
                Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (statusColor != null) {
                        Box(Modifier.size(7.dp).clip(CircleShape).background(statusColor))
                        Spacer(Modifier.width(7.dp))
                    }
                    Text(
                        name, color = palette.text,
                        style = TextStyle(fontSize = lightHudSp(12f, layoutMode), lineHeight = lightHudSp(14f, layoutMode),
                            fontWeight = FontWeight.SemiBold),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                if (subtitle != null) Text(
                    subtitle, color = palette.muted,
                    style = TextStyle(fontSize = lightHudSp(9f, layoutMode), lineHeight = lightHudSp(11f, layoutMode)),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(Icons.Default.ExpandMore, contentDescription = null, tint = palette.muted,
                modifier = Modifier.align(Alignment.CenterEnd).size(16.dp))
        }
    }
}

@Composable
internal fun LightCircleButton(
    icon: ImageVector,
    description: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
) {
    val palette = LocalLightHudPalette.current
    Box(
        modifier.clickable(role = Role.Button, onClick = onClick).minimumInteractiveComponentSize(),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(palette.surface), contentAlignment = Alignment.Center) {
            Icon(icon, description, tint = palette.text.copy(alpha = 0.85f), modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
internal fun LightMapVignette(darkTheme: Boolean, modifier: Modifier = Modifier) {
    val spec = lightMapOverlaySpec(darkTheme)
    val tint = Color(LocalVoltyMapPalette.current.background)
    Canvas(modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = sqrt(center.x * center.x + center.y * center.y)
        drawRect(brush = Brush.radialGradient(
            0f to tint.copy(alpha = spec.vignetteCenterAlpha),
            spec.clearUntilFraction to tint.copy(alpha = spec.vignetteCenterAlpha),
            spec.edgeStartFraction to tint.copy(alpha = spec.vignetteEdgeAlpha * 0.55f),
            1f to tint.copy(alpha = spec.vignetteEdgeAlpha),
            center = center,
            radius = radius,
        ))
    }
}
