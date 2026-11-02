package ru.sodovaya.volty.presentation.common

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import ru.sodovaya.volty.data.prefs.AppPrefs
import org.koin.compose.koinInject
import ru.sodovaya.volty.domain.model.ThemeAccent
import ru.sodovaya.volty.presentation.map.LocalVoltyMapPalette
import ru.sodovaya.volty.presentation.map.mapThemePalette

val LocalVoltyDarkTheme = staticCompositionLocalOf { false }

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun VoltyTheme(content: @Composable () -> Unit) {
    val appPrefs: AppPrefs = koinInject()
    val themeMode by appPrefs.themeMode.collectAsState()
    val dynamicColorEnabled by appPrefs.dynamicColorEnabled.collectAsState()
    val accent by appPrefs.themeAccent.collectAsState()
    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (themeMode) {
        "light" -> false
        "dark" -> true
        else -> systemDark
    }
    val dynamic = dynamicColorEnabled && supportsDynamicColor()
    val tones = if (dynamic) dynamicVoltyTonalColors() else remember(accent) { seededTonalColors(accent.seedArgb) }
    val colors = when {
        dynamic -> dynamicVoltyColors(darkTheme)
        accent == ThemeAccent.DEFAULT -> if (darkTheme) voltyDarkColors else voltyLightColors
        else -> remember(tones, darkTheme) { seededVoltyColorScheme(tones, darkTheme) }
    }
    val mapPalette = remember(tones, darkTheme) { mapThemePalette(tones, darkTheme) }
    SyncSystemBarsAppearance(darkTheme = darkTheme)
    CompositionLocalProvider(LocalVoltyDarkTheme provides darkTheme, LocalVoltyMapPalette provides mapPalette) {
        MaterialExpressiveTheme(
            colorScheme = colors,
            motionScheme = MotionScheme.expressive(),
            content = content
        )
    }
}
