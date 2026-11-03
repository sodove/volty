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
 * footpaths on the muted *text* colour (the brightest line on the dark map). The light values are
 * upstream's own. The dark values were then set to upstream's near-black tones plus a uniform night
 * lift (b5d7e964, 00c12bff); that too was reverted on owner feedback (2026-11-04): the dark ground
 * is near-black and near-neutral, the buildings are lighter and carry the tint, and the roads sit
 * subdued between them.
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
    /** Ride HUD card tones, kept apart from [land]/[building] so map tuning never moves the HUD cards. */
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
        //
        // Dark: a near-black, near-neutral ground under lighter, lightly tinted buildings, with
        // subdued roads between them. Owner feedback 2026-11-04 on the 0.7.64 screenshots, then on
        // the AVD the same day; the look aimed at is 0.7.63's. Measured from the owner's 0.7.63
        // screenshot: ground L3.3 and neutral (upstream's own rgb(12,12,12)), 3D building tops
        // L32-37 and faces L10-18 with a cool tint (the then-default extrusion colour #31424B at
        // 0.82 opacity): the tint lived on the buildings, never on the ground.
        //
        // Retraction: b5d7e964 set the dark ground and buildings to upstream's absolute tones
        // (background 4, land 2, building 3, outline 11, 3D 9, "the review's table"), which drew the
        // 3D city as black slabs on a black ground: «карта стала слишком неконтрастной». A first
        // fix in this branch restored the 0.7.63 palette values (5 / 9 / 16 / 23 / 24); on the AVD
        // that read hazy and grey (ground too light, buildings not light enough), and the ground
        // took a red or purple cast from warm and purple accents («я бы чутка фон ослабил»). Hence:
        // ground tone 4 / 6 at chroma 1 (a hint of hue, reads neutral), buildings tone 30-34 in
        // the accent's secondary hue.
        // Retraction, same day: the buildings first kept the old chroma cap of 5-6 and read as flat
        // neutral grey: «он будто бы боится дать saturation зданиям». 0.7.63's #31424B is chroma
        // ~8 at a cool hue. Dark buildings now take [darkBuildingChroma]: 12 for cool accents, 8
        // for warm ones (orange, pink, red, yellow), where 12 turns a whole city brown or rose.
        background = subdued(tones.neutralVariant(if (dark) 4 else 96), if (dark) 1.0 else 3.0),
        land = subdued(tones.neutralVariant(if (dark) 6 else 91), if (dark) 1.0 else 3.0),
        building = if (dark) subdued(tones.secondary(30), darkBuildingChroma(tones)) else subdued(tones.neutralVariant(93), 5.0),
        buildingOutline = if (dark) subdued(tones.secondary(34), darkBuildingChroma(tones)) else subdued(tones.secondary(88), 6.0),
        building3d = if (dark) subdued(tones.secondary(31), darkBuildingChroma(tones)) else subdued(tones.secondary(90), 5.0),
        // Dark roads keep upstream's relations (casing L25 above major L17 above minor L8, motorway
        // L8 with a black inner, path L10) by a per-class tone shift, compressed so every road line,
        // casing included, stays darker than the buildings: minor 20, motorway 21, major 26,
        // casing 29, footpaths 15 (dimmer than any road; their dash widens to 10 px at high zoom).
        // The owner checked the subdued roads on the AVD and asked for them «кааапельку сильнее»,
        // but not as bright as 0.7.63's, which were lighter than its buildings: +2 over the first
        // fix's 18 / 18 / 24 / 27 / 13.
        // Retraction: b5d7e964 lifted every class "+8 for night legibility" (20 / 30 / 16, casing
        // 36 after 00c12bff's further lift) to read against a near-black ground and near-black
        // buildings, and the major casings became a bright double line that dominated the map:
        // «дороги сильно выделяются в сравнении с тем что было до этого» (owner, 2026-11-04,
        // 0.7.64 screenshots). 00c12bff's "minor roads barely visible" (central Moscow) is now
        // answered by roads at L20 on an L4-6 ground, not by out-shining the buildings.
        // Earlier: footpaths sat on text-muted tone 65 in 0.7.63 and outshone their roads; the first
        // review proposed 34/46/14 (minor/major/casing), which put the minor fill above the casing.
        road = subdued(tones.neutralVariant(if (dark) 20 else 100)),
        roadMajor = subdued(tones.neutralVariant(if (dark) 26 else 94), 5.0),
        roadMotorway = subdued(tones.neutralVariant(if (dark) 21 else 85), 5.0),
        roadOutline = subdued(tones.neutralVariant(if (dark) 29 else 75)),
        path = subdued(tones.neutralVariant(if (dark) 15 else 77)),
        text = subdued(tones.neutral(if (dark) 85 else 20)),
        textMuted = subdued(tones.neutralVariant(if (dark) 65 else 38)),
        halo = subdued(tones.neutralVariant(if (dark) 4 else 96), if (dark) 1.0 else 3.0),
        accent = tones.primary(if (dark) 80 else 40),
        accentSecondary = tones.secondary(if (dark) 72 else 48),
        routeInactive = subdued(tones.neutralVariant(if (dark) 48 else 62)),
        hudSurface = subdued(tones.neutralVariant(if (dark) 9 else 94)),
        hudSurfaceStrong = subdued(tones.neutralVariant(if (dark) 16 else 87), 5.0),
    )
}

/**
 * Chroma cap for the dark theme's building fill, outline and 3D extrusions, by the accent's hue.
 * Cool hues (green through violet, HCT hue 110-320) carry 12 without looking garish on a
 * near-black map; warm hues (red, orange, yellow, pink) are held to 8, where 12 reads as a brown or
 * rose city rather than a tinted one. Owner feedback 2026-11-04 (AVD).
 */
internal fun darkBuildingChroma(tones: VoltyTonalColors): Double {
    val hue = Hct.fromInt(tones.secondary(31)).hue
    return if (hue in 110.0..320.0) 12.0 else 8.0
}

val LocalVoltyMapPalette = staticCompositionLocalOf {
    mapThemePalette(seededTonalColors(0xFF5B53D4.toInt()), false)
}
