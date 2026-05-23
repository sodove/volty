package com.volty.app.presentation.root

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.arkivanov.decompose.extensions.compose.stack.Children
import com.volty.app.presentation.autoconnect.AutoConnectScreen
import com.volty.app.presentation.dashboard.DashboardScreen
import com.volty.app.presentation.permissions.PermissionsGateScreen
import com.volty.app.presentation.picker.PickerScreen
import com.volty.app.presentation.scanning.ScanningScreen
import com.volty.app.presentation.vehicle.VehicleEditScreen
import com.volty.app.presentation.welcome.WelcomeScreen

@Composable
fun RootScreen(component: RootComponent) {
    Children(stack = component.stack) { child ->
        when (val instance = child.instance) {
            is RootComponent.Child.Welcome -> WelcomeScreen(instance.component)
            is RootComponent.Child.Permissions -> PermissionsGateScreen(instance.component)
            is RootComponent.Child.Scanning -> ScanningScreen(instance.component)
            is RootComponent.Child.AutoConnect -> AutoConnectScreen(instance.component)
            is RootComponent.Child.Picker -> PickerScreen(instance.component)
            is RootComponent.Child.Dashboard -> DashboardScreen(instance.component)
            is RootComponent.Child.VehicleEdit -> VehicleEditScreen(instance.component)
            RootComponent.Child.Cells -> StubScreen("Cells coming in T5")
            RootComponent.Child.Graph -> StubScreen("Graph coming in T6")
            RootComponent.Child.Settings -> StubScreen("Settings coming in T7")
        }
    }
}

@Composable
private fun StubScreen(message: String) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(message)
    }
}
