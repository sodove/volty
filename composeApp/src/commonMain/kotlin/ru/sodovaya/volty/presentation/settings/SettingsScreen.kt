package ru.sodovaya.volty.presentation.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.presentation.common.bmsTypeLabel
import ru.sodovaya.volty.presentation.common.chemistryLabel
import ru.sodovaya.volty.presentation.common.iconKeyToEmoji
import org.jetbrains.compose.resources.stringResource
import volty.composeapp.generated.resources.Res
import volty.composeapp.generated.resources.action_cancel
import volty.composeapp.generated.resources.settings_add_new_battery
import volty.composeapp.generated.resources.settings_auto_connect_countdown
import volty.composeapp.generated.resources.settings_delete
import volty.composeapp.generated.resources.settings_delete_text
import volty.composeapp.generated.resources.settings_delete_title
import volty.composeapp.generated.resources.settings_dynamic_color
import volty.composeapp.generated.resources.settings_dynamic_color_subtitle
import volty.composeapp.generated.resources.settings_my_batteries
import volty.composeapp.generated.resources.settings_scan_timeout
import volty.composeapp.generated.resources.settings_seconds
import volty.composeapp.generated.resources.settings_theme
import volty.composeapp.generated.resources.settings_theme_dark
import volty.composeapp.generated.resources.settings_theme_light
import volty.composeapp.generated.resources.settings_theme_system
import volty.composeapp.generated.resources.settings_title

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(component: SettingsComponent) {
    val state by component.state.collectAsState()
    var pendingDelete by remember { mutableStateOf<Vehicle?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.settings_title), fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = component::onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // THEME
            SectionLabel(stringResource(Res.string.settings_theme))
            val themes = listOf("system", "light", "dark")
            val themeLabels = mapOf(
                "system" to stringResource(Res.string.settings_theme_system),
                "light" to stringResource(Res.string.settings_theme_light),
                "dark" to stringResource(Res.string.settings_theme_dark)
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                themes.forEachIndexed { idx, t ->
                    SegmentedButton(
                        selected = state.themeMode == t,
                        onClick = { component.onThemeChanged(t) },
                        shape = SegmentedButtonDefaults.itemShape(index = idx, count = themes.size)
                    ) { Text(themeLabels[t] ?: t) }
                }
            }

            // DYNAMIC COLOR
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(Res.string.settings_dynamic_color), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(stringResource(Res.string.settings_dynamic_color_subtitle), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = state.dynamicColor, onCheckedChange = component::onDynamicColorChanged)
            }

            HorizontalDivider()

            // SCAN TIMEOUT
            SectionLabel(stringResource(Res.string.settings_scan_timeout))
            Text(stringResource(Res.string.settings_seconds, state.scanTimeoutSec), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(
                value = state.scanTimeoutSec.toFloat(),
                onValueChange = { component.onScanTimeoutChanged(it.toInt()) },
                valueRange = 3f..15f,
                steps = 11
            )

            // AUTO CONNECT COUNTDOWN
            SectionLabel(stringResource(Res.string.settings_auto_connect_countdown))
            Text(stringResource(Res.string.settings_seconds, state.autoConnectCountdownSec), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(
                value = state.autoConnectCountdownSec.toFloat(),
                onValueChange = { component.onAutoConnectCountdownChanged(it.toInt()) },
                valueRange = 0f..10f,
                steps = 9
            )

            HorizontalDivider()

            SectionLabel(stringResource(Res.string.settings_my_batteries))
            state.vehicles.forEach { v ->
                VehicleRow(
                    vehicle = v,
                    onEdit = { component.onEditVehicle(v.id) },
                    onDelete = { pendingDelete = v }
                )
            }
            TextButton(onClick = component::onAddBattery, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(Res.string.settings_add_new_battery))
            }
            Spacer(Modifier.height(24.dp))
        }

        pendingDelete?.let { v ->
            AlertDialog(
                onDismissRequest = { pendingDelete = null },
                confirmButton = {
                    TextButton(onClick = {
                        component.onDeleteVehicle(v.id)
                        pendingDelete = null
                    }) { Text(stringResource(Res.string.settings_delete), color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text(stringResource(Res.string.action_cancel)) } },
                title = { Text(stringResource(Res.string.settings_delete_title, v.name)) },
                text = { Text(stringResource(Res.string.settings_delete_text)) }
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp)
    )
}

@Composable
private fun VehicleRow(vehicle: Vehicle, onEdit: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onEdit)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier.size(36.dp)
                .clip(RoundedCornerShape(14.dp, 22.dp, 22.dp, 14.dp))
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center
        ) {
            Text(iconKeyToEmoji(vehicle.iconKey), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(vehicle.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text("${bmsTypeLabel(vehicle.bmsType)}  ·  ${chemistryLabel(vehicle.chemistry)}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = onDelete) { Text(stringResource(Res.string.settings_delete), color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
    }
}
