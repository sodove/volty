package ru.sodovaya.volty.presentation.common

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import ru.sodovaya.volty.data.prefs.AppPrefs
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun VoltyTheme(content: @Composable () -> Unit) {
    val appPrefs: AppPrefs = koinInject()
    val themeMode by appPrefs.themeMode.collectAsState()
    val dynamicColorEnabled by appPrefs.dynamicColorEnabled.collectAsState()
    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (themeMode) {
        "light" -> false
        "dark" -> true
        else -> systemDark
    }
    val colors = when {
        dynamicColorEnabled && supportsDynamicColor() -> dynamicVoltyColors(darkTheme)
        darkTheme -> voltyDarkColors
        else -> voltyLightColors
    }
    SyncSystemBarsAppearance(darkTheme = darkTheme)
    MaterialExpressiveTheme(
        colorScheme = colors,
        motionScheme = MotionScheme.expressive(),
        content = content
    )
}
