package ru.sodovaya.volty.presentation.map

import androidx.compose.runtime.staticCompositionLocalOf
import com.materialkolor.hct.Hct
import kotlinx.serialization.Serializable
import ru.sodovaya.volty.presentation.common.VoltyTonalColors
import ru.sodovaya.volty.presentation.common.seededTonalColors

@Serializable
data class MapThemePalette(
    val background: Int, val land: Int, val building: Int, val buildingOutline: Int,
    val building3d: Int, val road: Int, val roadMajor: Int, val roadOutline: Int,
    val text: Int, val textMuted: Int, val halo: Int, val accent: Int,
    val accentSecondary: Int, val routeInactive: Int,
)

fun mapThemePalette(tones: VoltyTonalColors, dark: Boolean): MapThemePalette {
    // Retraction: using the full secondary family for large building surfaces made
    // warm themes look sepia. Keep the hue/tone, but reserve full chroma for accents.
    fun subdued(color: Int, maxChroma: Double = 3.0): Int {
        val hct = Hct.fromInt(color)
        return if (hct.chroma <= maxChroma) color
        else Hct.from(hct.hue, maxChroma, hct.tone).toInt()
    }
    return MapThemePalette(
        background = subdued(tones.neutralVariant(if (dark) 5 else 98)),
        land = subdued(tones.neutralVariant(if (dark) 9 else 94)),
        building = subdued(tones.neutralVariant(if (dark) 16 else 87), 5.0),
        buildingOutline = subdued(tones.secondary(if (dark) 23 else 79), 6.0),
        building3d = subdued(tones.secondary(if (dark) 24 else 82), 5.0),
        road = subdued(tones.neutralVariant(if (dark) 28 else 100)),
        roadMajor = subdued(tones.secondary(if (dark) 35 else 91), 5.0),
        roadOutline = subdued(tones.neutralVariant(if (dark) 18 else 76)),
        text = subdued(tones.neutral(if (dark) 85 else 20)),
        textMuted = subdued(tones.neutralVariant(if (dark) 65 else 38)),
        halo = subdued(tones.neutralVariant(if (dark) 5 else 98)),
        accent = tones.primary(if (dark) 80 else 40),
        accentSecondary = tones.secondary(if (dark) 72 else 48),
        routeInactive = subdued(tones.neutralVariant(if (dark) 48 else 62)),
    )
}

val LocalVoltyMapPalette = staticCompositionLocalOf {
    mapThemePalette(seededTonalColors(0xFF5B53D4.toInt()), false)
}
