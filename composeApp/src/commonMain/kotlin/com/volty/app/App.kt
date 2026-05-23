package com.volty.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import com.volty.app.presentation.root.RootComponent
import com.volty.app.presentation.root.RootScreen

@Composable
fun App(root: RootComponent) {
    MaterialTheme {
        Surface { RootScreen(root) }
    }
}
