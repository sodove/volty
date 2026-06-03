package ru.sodovaya.volty

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ru.sodovaya.volty.presentation.common.VoltyTheme
import ru.sodovaya.volty.presentation.root.RootComponent
import ru.sodovaya.volty.presentation.root.RootScreen

@Composable
fun App(root: RootComponent) {
    VoltyTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            RootScreen(root)
        }
    }
}
