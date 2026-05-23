package com.volty.app.presentation.root

import androidx.compose.runtime.Composable
import com.arkivanov.decompose.extensions.compose.stack.Children
import com.volty.app.presentation.autoconnect.AutoConnectScreen
import com.volty.app.presentation.cells.CellsScreen
import com.volty.app.presentation.dashboard.DashboardScreen
import com.volty.app.presentation.graph.GraphScreen
import com.volty.app.presentation.permissions.PermissionsGateScreen
import com.volty.app.presentation.picker.PickerScreen
import com.volty.app.presentation.scanning.ScanningScreen
import com.volty.app.presentation.settings.SettingsScreen
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
            is RootComponent.Child.Cells -> CellsScreen(instance.component)
            is RootComponent.Child.Graph -> GraphScreen(instance.component)
            is RootComponent.Child.Settings -> SettingsScreen(instance.component)
        }
    }
}
