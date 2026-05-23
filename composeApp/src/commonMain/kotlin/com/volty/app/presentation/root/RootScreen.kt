package com.volty.app.presentation.root

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.arkivanov.decompose.extensions.compose.stack.Children
import com.volty.app.presentation.autoconnect.AutoConnectScreen
import com.volty.app.presentation.debug.DebugScreen
import com.volty.app.presentation.permissions.PermissionsGateScreen
import com.volty.app.presentation.scanning.ScanningScreen
import com.volty.app.presentation.welcome.WelcomeScreen

@Composable
fun RootScreen(component: RootComponent) {
    Children(stack = component.stack) { child ->
        when (val instance = child.instance) {
            is RootComponent.Child.Welcome -> WelcomeScreen(instance.component)
            is RootComponent.Child.Permissions -> PermissionsGateScreen(instance.component)
            is RootComponent.Child.Scanning -> ScanningScreen(instance.component)
            is RootComponent.Child.AutoConnect -> AutoConnectScreen(instance.component)
            is RootComponent.Child.Picker -> Stub(instance.component.label)
            is RootComponent.Child.Dashboard -> DebugScreen(instance.component)
            is RootComponent.Child.VehicleEdit -> Stub(instance.component.label)
        }
    }
}

@Composable
private fun Stub(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text)
    }
}
