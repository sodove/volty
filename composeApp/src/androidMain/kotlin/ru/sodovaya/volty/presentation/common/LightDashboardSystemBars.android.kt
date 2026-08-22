package ru.sodovaya.volty.presentation.common

import android.app.Activity
import android.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

@Composable
actual fun SyncLightDashboardSystemBars(darkTheme: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    val window = (view.context as Activity).window
    val controller = WindowCompat.getInsetsController(window, view)
    DisposableEffect(view) {
        val oldStatusColor = window.statusBarColor
        val oldNavigationColor = window.navigationBarColor
        val oldLightStatusBars = controller.isAppearanceLightStatusBars
        val oldLightNavigationBars = controller.isAppearanceLightNavigationBars
        onDispose {
            window.statusBarColor = oldStatusColor
            window.navigationBarColor = oldNavigationColor
            controller.isAppearanceLightStatusBars = oldLightStatusBars
            controller.isAppearanceLightNavigationBars = oldLightNavigationBars
        }
    }
    SideEffect {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        controller.isAppearanceLightStatusBars = !darkTheme
        controller.isAppearanceLightNavigationBars = !darkTheme
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            window.isNavigationBarContrastEnforced = false
        }
    }
}
