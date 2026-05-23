package com.volty.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import com.volty.app.presentation.debug.DebugScreen
import com.volty.app.presentation.root.RootComponent

@Composable
fun App(root: RootComponent) {
    MaterialTheme {
        Surface { DebugScreen(root.debug) }
    }
}
