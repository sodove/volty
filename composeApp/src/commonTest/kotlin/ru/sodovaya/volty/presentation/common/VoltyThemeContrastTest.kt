package ru.sodovaya.volty.presentation.common

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.materialkolor.hct.Hct
import ru.sodovaya.volty.domain.model.ThemeAccent
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** WCAG 2.x contrast over every accent, light and dark. Pure colour maths, no Compose runtime. */
class VoltyThemeContrastTest {
    private fun channel(c: Int): Double {
        val v = c / 255.0
        return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    private fun luminance(color: Color): Double {
        val argb = color.toArgb()
        return 0.2126 * channel(argb shr 16 and 255) + 0.7152 * channel(argb shr 8 and 255) + 0.0722 * channel(argb and 255)
    }

    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a); val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun schemes(): List<Pair<String, ColorScheme>> =
        ThemeAccent.entries.flatMap { accent ->
            listOf(false, true).map { dark ->
                "${accent.id}/${if (dark) "dark" else "light"}" to seededVoltyColorScheme(seededTonalColors(accent.seedArgb), dark)
            }
        } + listOf("default-handtuned/light" to voltyLightColors, "default-handtuned/dark" to voltyDarkColors)

    @Test fun every_on_colour_pair_meets_AA_for_every_accent_light_and_dark() {
        for ((name, s) in schemes()) {
            val pairs = mapOf(
                "onPrimary/primary" to (s.onPrimary to s.primary),
                "onPrimaryContainer/primaryContainer" to (s.onPrimaryContainer to s.primaryContainer),
                "onSecondary/secondary" to (s.onSecondary to s.secondary),
                "onSecondaryContainer/secondaryContainer" to (s.onSecondaryContainer to s.secondaryContainer),
                "onTertiary/tertiary" to (s.onTertiary to s.tertiary),
                "onTertiaryContainer/tertiaryContainer" to (s.onTertiaryContainer to s.tertiaryContainer),
                "onError/error" to (s.onError to s.error),
                "onErrorContainer/errorContainer" to (s.onErrorContainer to s.errorContainer),
                "onSurface/surface" to (s.onSurface to s.surface),
                "onBackground/background" to (s.onBackground to s.background),
                "onSurfaceVariant/surfaceVariant" to (s.onSurfaceVariant to s.surfaceVariant),
                "onSurface/surfaceContainerHigh" to (s.onSurface to s.surfaceContainerHigh),
            )
            for ((label, pair) in pairs) {
                val ratio = contrast(pair.first, pair.second)
                assertTrue(ratio >= 4.5, "$name $label contrast $ratio < 4.5")
            }
        }
    }

    @Test fun outline_is_a_visible_3_to_1_against_the_surface() {
        for ((name, s) in schemes()) {
            val ratio = contrast(s.outline, s.surface)
            assertTrue(ratio >= 3.0, "$name outline/surface $ratio < 3")
        }
    }

    @Test fun status_colours_are_AA_text_on_every_surface_they_are_drawn_on() {
        for ((name, s) in schemes()) {
            val dark = name.endsWith("dark")
            val status = voltyStatusColors(dark)
            for ((label, colour) in listOf("connected" to status.connected, "warn" to status.warn)) {
                for ((surfaceName, surface) in listOf("surface" to s.surface, "surfaceVariant" to s.surfaceVariant, "surfaceContainerHigh" to s.surfaceContainerHigh)) {
                    val ratio = contrast(colour, surface)
                    assertTrue(ratio >= 4.5, "$name $label on $surfaceName contrast $ratio < 4.5")
                }
            }
        }
    }

    @Test fun status_colours_do_not_depend_on_the_accent() {
        for (dark in listOf(false, true)) {
            val expected = voltyStatusColors(dark)
            for (accent in ThemeAccent.entries) {
                // The only inputs are the theme mode; the accent never reaches them.
                assertEquals(expected, voltyStatusColors(dark), accent.id)
            }
        }
    }

    @Test fun connected_is_green_and_warn_is_amber_never_the_accent_hue() {
        for (dark in listOf(false, true)) {
            val status = voltyStatusColors(dark)
            assertTrue(Hct.fromInt(status.connected.toArgb()).hue in 120.0..165.0, "connected hue")
            assertTrue(Hct.fromInt(status.warn.toArgb()).hue in 50.0..95.0, "warn hue")
        }
    }

    @Test fun tertiary_stays_green_for_every_accent_so_decorative_uses_cannot_turn_into_a_wrong_status() {
        for ((name, s) in schemes().filter { !it.first.startsWith("default-handtuned") }) {
            val hue = Hct.fromInt(s.tertiary.toArgb()).hue
            assertTrue(hue in 125.0..165.0, "$name tertiary hue $hue")
        }
    }

    @Test fun error_roles_are_explicit_red_for_every_accent() {
        for ((name, s) in schemes()) {
            for ((label, colour) in listOf("error" to s.error, "errorContainer" to s.errorContainer)) {
                val hct = Hct.fromInt(colour.toArgb())
                assertTrue(hct.hue < 45.0 || hct.hue > 345.0, "$name $label hue ${hct.hue}")
                assertTrue(hct.chroma > 8.0, "$name $label chroma ${hct.chroma}")
            }
        }
    }
}
