package com.volty.app.presentation.root

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkivanov.decompose.extensions.compose.stack.Children
import com.arkivanov.decompose.extensions.compose.stack.animation.fade
import com.arkivanov.decompose.extensions.compose.stack.animation.stackAnimation
import com.arkivanov.decompose.extensions.compose.subscribeAsState
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
    val stackState by component.stack.subscribeAsState()
    val active = stackState.active.instance

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f)) {
            Children(
                stack = component.stack,
                animation = stackAnimation(fade())
            ) { child ->
                when (val instance = child.instance) {
                    is RootComponent.Child.Loading -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) { CircularProgressIndicator() }
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
        // Persistent bottom tab bar — only for main destinations
        BottomTabBar(
            active = active,
            onTab = { tab -> component.onTab(tab) },
            modifier = Modifier.navigationBarsPadding()
        )
    }
}

@Composable
private fun BottomTabBar(
    active: RootComponent.Child,
    onTab: (RootComponent.Tab) -> Unit,
    modifier: Modifier = Modifier
) {
    val visible = active is RootComponent.Child.Dashboard ||
        active is RootComponent.Child.Cells ||
        active is RootComponent.Child.Graph ||
        active is RootComponent.Child.Settings
    if (!visible) return

    val current = when (active) {
        is RootComponent.Child.Dashboard -> RootComponent.Tab.Live
        is RootComponent.Child.Cells -> RootComponent.Tab.Cells
        is RootComponent.Child.Graph -> RootComponent.Tab.Graph
        is RootComponent.Child.Settings -> RootComponent.Tab.Settings
        else -> RootComponent.Tab.Live
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Tab("Live", current == RootComponent.Tab.Live) { onTab(RootComponent.Tab.Live) }
        Tab("Cells", current == RootComponent.Tab.Cells) { onTab(RootComponent.Tab.Cells) }
        Tab("Graph", current == RootComponent.Tab.Graph) { onTab(RootComponent.Tab.Graph) }
        Tab("⚙", current == RootComponent.Tab.Settings) { onTab(RootComponent.Tab.Settings) }
    }
}

@Composable
private fun RowScope.Tab(label: String, active: Boolean, onClick: () -> Unit) {
    val bg = if (active) MaterialTheme.colorScheme.primary else Color.Transparent
    val fg = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .weight(1f)
            .clip(
                RoundedCornerShape(
                    if (active) 16.dp else 20.dp,
                    if (active) 24.dp else 20.dp,
                    if (active) 16.dp else 20.dp,
                    if (active) 24.dp else 20.dp
                )
            )
            .background(bg)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = fg)
    }
}
