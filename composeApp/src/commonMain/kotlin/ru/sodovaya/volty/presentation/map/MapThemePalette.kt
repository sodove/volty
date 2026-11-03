package ru.sodovaya.volty.presentation.map

import androidx.compose.runtime.staticCompositionLocalOf
import com.materialkolor.hct.Hct
import kotlinx.serialization.Serializable
import ru.sodovaya.volty.presentation.common.VoltyTonalColors
import ru.sodovaya.volty.presentation.common.seededTonalColors

/**
 * Tones the map theme wants for each class of OpenFreeMap layer, as colours.
 *
 * The map styles are recoloured by a tone shift per class (see [themedMapColor]): a layer keeps
 * the tone distance it had from its class reference upstream, so every field here is "where the
 * class's own upstream colour should land", not "what every layer of the class becomes".
 *
 * Retraction: the first revision of this palette (0.7.63) used one absolute tone per field, which
 * flattened every road class into one tone, inverted building-versus-land in the dark theme and put
 * footpaths on the muted *text* colour (the brightest line on the dark map). The [dark] values
 * below are the upstream tones plus a uniform night lift; the light values are upstream's own.
 * [road], [roadMajor], [roadMotorway], [roadOutline] and [path] are only *applied* by the dark
 * theme: the light theme keeps OpenFreeMap's roads as drawn (orange motorways, yellow primaries),
 * which is the one cue a rider reads at a glance in sunlight. Their light values document the
 * upstream tones and feed nothing.
 */
@Serializable
data class MapThemePalette(
    val background: Int, val land: Int, val building: Int, val buildingOutline: Int,
    val building3d: Int, val road: Int, val roadMajor: Int, val roadOutline: Int,
    val text: Int, val textMuted: Int, val halo: Int, val accent: Int,
    val accentSecondary: Int, val routeInactive: Int,
    val roadMotorway: Int = road, val path: Int = road,
    /** Ride HUD card tones. Kept apart from [land]/[building]: the dark map ground is near-black now. */
    val hudSurface: Int = land, val hudSurfaceStrong: Int = building,
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
        // Upstream bright: background #f8f4f0 (L96), building #f2eae2 / #dfdbd7 (L93 / L88).
        // Upstream dark: background L3.3, land L3.5, building L2.7, building outline L9.8; the
        // values here follow the review's table (4 / 2 / 3 / 11): building < outline, building
        // lighter than land, both under the road lift.
        background = subdued(tones.neutralVariant(if (dark) 4 else 96)),
        land = subdued(tones.neutralVariant(if (dark) 2 else 91)),
        building = subdued(tones.neutralVariant(if (dark) 3 else 93), 5.0),
        buildingOutline = subdued(tones.secondary(if (dark) 11 else 88), 6.0),
        building3d = subdued(tones.secondary(if (dark) 9 else 90), 5.0),
        // Dark roads keep upstream's own order (minor L8, motorway L8 with a black inner, major L17,
        // path L10 -> 12, casing L25) lifted by a uniform +8 for night legibility, so a casing stays
        // lighter than the fill it wraps. Footpaths get only +2 (tone 12), not +8: their dashed line
        // widens to 10 px at high zoom, and at tone 18 (and in 0.7.63, text-muted tone 65) they
        // outshone the roads they branch from. Owner feedback from the emulator, 2026-11-02.
        // The first review proposed 34/46/14 (minor/major/casing); that put the minor fill above
        // the casing and was set aside in favour of upstream's relations.
        road = subdued(tones.neutralVariant(if (dark) 20 else 100)),
        roadMajor = subdued(tones.neutralVariant(if (dark) 30 else 94), 5.0),
        roadMotorway = subdued(tones.neutralVariant(if (dark) 16 else 85), 5.0),
        roadOutline = subdued(tones.neutralVariant(if (dark) 36 else 75)),
        path = subdued(tones.neutralVariant(if (dark) 12 else 77)),
        text = subdued(tones.neutral(if (dark) 85 else 20)),
        textMuted = subdued(tones.neutralVariant(if (dark) 65 else 38)),
        halo = subdued(tones.neutralVariant(if (dark) 4 else 96)),
        accent = tones.primary(if (dark) 80 else 40),
        accentSecondary = tones.secondary(if (dark) 72 else 48),
        routeInactive = subdued(tones.neutralVariant(if (dark) 48 else 62)),
        hudSurface = subdued(tones.neutralVariant(if (dark) 9 else 94)),
        hudSurfaceStrong = subdued(tones.neutralVariant(if (dark) 16 else 87), 5.0),
    )
}

val LocalVoltyMapPalette = staticCompositionLocalOf {
    mapThemePalette(seededTonalColors(0xFF5B53D4.toInt()), false)
}
