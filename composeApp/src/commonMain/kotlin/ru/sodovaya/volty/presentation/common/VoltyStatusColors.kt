package ru.sodovaya.volty.presentation.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.materialkolor.hct.Hct

/**
 * Semantic status colours that do not follow the accent or the wallpaper.
 *
 * "Connected" and the duty WARN level used to read `colorScheme.tertiary`, which is accent hue +60
 * for a manual accent (blue turned the connection dot magenta, pink turned it orange) and anything
 * at all under dynamic colour. A status colour must mean the same thing on every theme, so these
 * are fixed hues at M3-style tones: tone 40 on light surfaces and 80 on dark ones keep AA against
 * both `surface` and `surfaceVariant` for every accent (pinned by VoltyThemeContrastTest).
 *
 * Errors stay `colorScheme.error`, which [seededVoltyColorScheme] now pins to a fixed red family.
 */
data class VoltyStatusColors(val connected: Color, val warn: Color)

private const val CONNECTED_HUE = 145.0
private const val CONNECTED_CHROMA = 30.0
private const val WARN_HUE = 70.0
private const val WARN_CHROMA = 50.0

fun voltyStatusColors(dark: Boolean): VoltyStatusColors {
    val tone = if (dark) 80.0 else 40.0
    return VoltyStatusColors(
        connected = Color(Hct.from(CONNECTED_HUE, CONNECTED_CHROMA, tone).toInt()),
        warn = Color(Hct.from(WARN_HUE, WARN_CHROMA, tone).toInt()),
    )
}

@Composable
fun voltyStatusColors(): VoltyStatusColors = voltyStatusColors(LocalVoltyDarkTheme.current)
