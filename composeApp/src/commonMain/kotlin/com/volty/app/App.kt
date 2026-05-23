package com.volty.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.volty.app.presentation.common.VoltyTheme
import com.volty.app.presentation.root.RootComponent
import com.volty.app.presentation.root.RootScreen

@Composable
fun App(root: RootComponent) {
    VoltyTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            RootScreen(root)
        }
    }
}
