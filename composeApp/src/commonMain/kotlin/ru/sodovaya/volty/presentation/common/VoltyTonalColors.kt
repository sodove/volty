package ru.sodovaya.volty.presentation.common

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.materialkolor.hct.Hct
import com.materialkolor.palettes.TonalPalette

/** Full tone families, independent of the finite set of Material UI roles. */
class VoltyTonalColors(
    val primary: (Int) -> Int,
    val secondary: (Int) -> Int,
    val tertiary: (Int) -> Int,
    val neutral: (Int) -> Int,
    val neutralVariant: (Int) -> Int,
    val error: (Int) -> Int = ERROR_FAMILY,
)

/** Fixed green for the tertiary family: the same hue as the hand-tuned default scheme (0xFF2E6B3A). */
private const val TERTIARY_HUE = 145.0
private const val TERTIARY_CHROMA = 30.0

/** Fixed red family for error roles, independent of the accent. */
private val ERROR_FAMILY: (Int) -> Int = TonalPalette.fromHueAndChroma(25.0, 84.0)::tone

fun seededTonalColors(seedArgb: Int): VoltyTonalColors {
    val seed = Hct.fromInt(seedArgb)
    fun family(hue: Double, chroma: Double): (Int) -> Int =
        TonalPalette.fromHueAndChroma(hue, chroma)::tone
    return VoltyTonalColors(
        primary = family(seed.hue, 48.0),
        secondary = family(seed.hue, 20.0),
        // Retraction: tertiary used to be seed hue + 60. Screens read it as "connected"/"ok", so a
        // blue accent made the connection dot magenta and a pink one orange. It is a fixed green now
        // (chroma 30, hue 145) for every accent; status colours themselves live in VoltyStatusColors.
        tertiary = family(TERTIARY_HUE, TERTIARY_CHROMA),
        neutral = family(seed.hue, 6.0),
        neutralVariant = family(seed.hue, 12.0),
    )
}

fun seededVoltyColorScheme(tones: VoltyTonalColors, dark: Boolean): ColorScheme {
    fun p(t: Int) = Color(tones.primary(t))
    fun s(t: Int) = Color(tones.secondary(t))
    fun t(t: Int) = Color(tones.tertiary(t))
    fun n(t: Int) = Color(tones.neutral(t))
    fun v(t: Int) = Color(tones.neutralVariant(t))
    fun e(t: Int) = Color(tones.error(t))
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        // Explicit rather than inherited from the baseline scheme: an alarm colour must not depend
        // on whatever defaults a library update brings.
        error = e(if (dark) 80 else 40), onError = e(if (dark) 20 else 100),
        errorContainer = e(if (dark) 30 else 90), onErrorContainer = e(if (dark) 90 else 10),
        primary = p(if (dark) 80 else 40), onPrimary = p(if (dark) 20 else 100),
        primaryContainer = p(if (dark) 30 else 90), onPrimaryContainer = p(if (dark) 90 else 10),
        inversePrimary = p(if (dark) 40 else 80), surfaceTint = p(if (dark) 80 else 40),
        secondary = s(if (dark) 80 else 40), onSecondary = s(if (dark) 20 else 100),
        secondaryContainer = s(if (dark) 30 else 90), onSecondaryContainer = s(if (dark) 90 else 10),
        tertiary = t(if (dark) 80 else 40), onTertiary = t(if (dark) 20 else 100),
        tertiaryContainer = t(if (dark) 30 else 90), onTertiaryContainer = t(if (dark) 90 else 10),
        background = n(if (dark) 6 else 98), onBackground = n(if (dark) 90 else 10),
        surface = n(if (dark) 6 else 98), onSurface = n(if (dark) 90 else 10),
        surfaceVariant = v(if (dark) 30 else 90), onSurfaceVariant = v(if (dark) 80 else 30),
        surfaceDim = n(if (dark) 6 else 87), surfaceBright = n(if (dark) 24 else 98),
        surfaceContainerLowest = n(if (dark) 4 else 100), surfaceContainerLow = n(if (dark) 10 else 96),
        surfaceContainer = n(if (dark) 12 else 94), surfaceContainerHigh = n(if (dark) 17 else 92),
        surfaceContainerHighest = n(if (dark) 22 else 90),
        inverseSurface = n(if (dark) 90 else 20), inverseOnSurface = n(if (dark) 20 else 95),
        outline = v(if (dark) 60 else 50), outlineVariant = v(if (dark) 30 else 80),
        primaryFixed = p(90), primaryFixedDim = p(80), onPrimaryFixed = p(10), onPrimaryFixedVariant = p(30),
        secondaryFixed = s(90), secondaryFixedDim = s(80), onSecondaryFixed = s(10), onSecondaryFixedVariant = s(30),
        tertiaryFixed = t(90), tertiaryFixedDim = t(80), onTertiaryFixed = t(10), onTertiaryFixedVariant = t(30),
    )
}
