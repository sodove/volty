package com.volty.app.presentation.picker

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.volty.app.domain.model.Vehicle
import com.volty.app.domain.repository.DiscoveredDevice

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PickerScreen(component: PickerComponent) {
    val state by component.state.collectAsState()
    val title = when (state.mode) {
        "add" -> "Add new battery"
        "guest" -> "Quick connect"
        else -> "Pick a battery"
    }
    val subtitle = when (state.mode) {
        "guest" -> "Guest mode — won't save"
        else -> "${state.myInRange.size + state.otherNearby.size} found nearby"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = component::onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().padding(horizontal = 12.dp)
        ) {
            if (state.error != null) {
                Text(
                    "Error: ${state.error}",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = 8.dp),
                    fontSize = 12.sp
                )
            }

            LazyColumn(modifier = Modifier.weight(1f)) {
                if (state.mode == "cold" && state.myInRange.isNotEmpty()) {
                    item { SectionHeader("My batteries · in range") }
                    items(state.myInRange, key = { "v-" + it.id }) { v ->
                        VehicleRow(
                            vehicle = v,
                            isConnecting = state.connecting == v.bmsAddress,
                            onClick = { component.onConnectKnown(v) }
                        )
                    }
                }

                if (state.otherNearby.isNotEmpty()) {
                    item {
                        SectionHeader(
                            if (state.mode == "guest" || (state.mode == "cold" && state.myInRange.isEmpty())) "BMS detected"
                            else "Other BMS nearby"
                        )
                    }
                    items(state.otherNearby, key = { "d-" + it.address }) { d ->
                        DeviceRow(
                            device = d,
                            isConnecting = state.connecting == d.address,
                            onClick = { component.onConnectOther(d) }
                        )
                    }
                }

                if (state.myInRange.isEmpty() && state.otherNearby.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("Scanning…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            if (state.mode != "add") {
                HorizontalDivider()
                TextButton(
                    onClick = component::onAddNewBattery,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                ) {
                    Text("+ Add new battery")
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
    )
}

@Composable
private fun VehicleRow(vehicle: Vehicle, isConnecting: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .clickable(enabled = !isConnecting, onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Avatar(letter = "⚡", bg = MaterialTheme.colorScheme.primary)
        Column(modifier = Modifier.weight(1f)) {
            Text(vehicle.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text("${vehicle.bmsType.label}  ·  saved", fontSize = 11.sp, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
        }
        if (isConnecting) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
    }
}

@Composable
private fun DeviceRow(device: DiscoveredDevice, isConnecting: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(enabled = !isConnecting, onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Avatar(letter = "?", bg = MaterialTheme.colorScheme.outline)
        Column(modifier = Modifier.weight(1f)) {
            Text(device.name ?: device.address, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${device.bmsType.label}  ·  ${device.rssi} dBm", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
        }
        if (isConnecting) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
    }
}

@Composable
private fun Avatar(letter: String, bg: Color) {
    Box(
        modifier = Modifier.size(36.dp).clip(RoundedCornerShape(14.dp, 22.dp, 22.dp, 14.dp)).background(bg),
        contentAlignment = Alignment.Center
    ) {
        Text(letter, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}
