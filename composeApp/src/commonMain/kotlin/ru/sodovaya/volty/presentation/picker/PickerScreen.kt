package ru.sodovaya.volty.presentation.picker

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
import androidx.compose.material3.ModalBottomSheet
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
import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.ControllerType
import ru.sodovaya.volty.domain.model.DemoProfile
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.model.primaryAddress
import ru.sodovaya.volty.domain.repository.DiscoveredDevice
import ru.sodovaya.volty.presentation.common.bmsTypeLabel
import ru.sodovaya.volty.presentation.common.iconKeyToEmoji
import ru.sodovaya.volty.presentation.common.vehicleSourceLabel
import org.jetbrains.compose.resources.stringResource
import volty.composeapp.generated.resources.Res
import volty.composeapp.generated.resources.picker_add_new
import volty.composeapp.generated.resources.picker_add_title
import volty.composeapp.generated.resources.picker_cold_title
import volty.composeapp.generated.resources.picker_detected
import volty.composeapp.generated.resources.picker_error
import volty.composeapp.generated.resources.picker_found_nearby
import volty.composeapp.generated.resources.picker_guest_subtitle
import volty.composeapp.generated.resources.picker_guest_title
import volty.composeapp.generated.resources.picker_my_in_range
import volty.composeapp.generated.resources.picker_other_nearby
import volty.composeapp.generated.resources.picker_pick_type_title
import volty.composeapp.generated.resources.picker_scanning
import volty.composeapp.generated.resources.picker_section_battery
import volty.composeapp.generated.resources.picker_section_controller
import volty.composeapp.generated.resources.picker_show_all
import volty.composeapp.generated.resources.picker_try_demo
import volty.composeapp.generated.resources.picker_try_demo_wheel
import volty.composeapp.generated.resources.picker_type_unknown

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PickerScreen(component: PickerComponent) {
    val state by component.state.collectAsState()
    val title = when (state.mode) {
        "add" -> stringResource(Res.string.picker_add_title)
        "guest" -> stringResource(Res.string.picker_guest_title)
        else -> stringResource(Res.string.picker_cold_title)
    }
    val subtitle = when (state.mode) {
        "guest" -> stringResource(Res.string.picker_guest_subtitle)
        else -> stringResource(Res.string.picker_found_nearby, state.myInRange.size + state.otherNearby.size)
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
                    stringResource(Res.string.picker_error, state.error ?: ""),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = 8.dp),
                    fontSize = 12.sp
                )
            }

            LazyColumn(modifier = Modifier.weight(1f)) {
                if (state.mode == "cold" && state.myInRange.isNotEmpty()) {
                    item { SectionHeader(stringResource(Res.string.picker_my_in_range)) }
                    items(state.myInRange, key = { "v-" + it.id }) { v ->
                        VehicleRow(
                            vehicle = v,
                            // Mirrors what PickerComponent.onConnectKnown stores.
                            isConnecting = state.connecting == v.primaryAddress,
                            onClick = { component.onConnectKnown(v) }
                        )
                    }
                }

                if (state.otherNearby.isNotEmpty()) {
                    item {
                        SectionHeader(
                            if (state.mode == "guest" || (state.mode == "cold" && state.myInRange.isEmpty()))
                                stringResource(Res.string.picker_detected)
                            else stringResource(Res.string.picker_other_nearby)
                        )
                    }
                    items(state.otherNearby, key = { "d-" + it.address }) { d ->
                        DeviceRow(
                            device = d,
                            isConnecting = state.connecting == d.address,
                            onClick = { component.onDeviceTapped(d) }
                        )
                    }
                }

                if (state.otherDevices.isNotEmpty()) {
                    item {
                        TextButton(
                            onClick = component::onToggleShowAll,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        ) {
                            Text(stringResource(Res.string.picker_show_all, state.otherDevices.size))
                        }
                    }
                    if (state.showAll) {
                        items(state.otherDevices, key = { "u-" + it.address }) { d ->
                            DeviceRow(
                                device = d,
                                isConnecting = state.connecting == d.address,
                                onClick = { component.onDeviceTapped(d) }
                            )
                        }
                    }
                }

                if (state.myInRange.isEmpty() && state.otherNearby.isEmpty() && state.otherDevices.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(stringResource(Res.string.picker_scanning), color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    Text(stringResource(Res.string.picker_add_new))
                }
                TextButton(
                    onClick = { component.onTryDemo(DemoProfile.SCOOTER) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(Res.string.picker_try_demo))
                }
                TextButton(
                    onClick = { component.onTryDemo(DemoProfile.WHEEL) },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                ) {
                    Text(stringResource(Res.string.picker_try_demo_wheel))
                }
            }
        }

        state.typePickerFor?.let { device ->
            ModalBottomSheet(onDismissRequest = component::onTypeSheetDismissed) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(
                        stringResource(Res.string.picker_pick_type_title),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    // Single source of truth for both the pre-selected row and
                    // the section order below — see [preselectedChoice]. Relies
                    // on device.bmsType and device.controllerType being
                    // mutually exclusive (BmsTypeDetector.detectController
                    // returns null whenever detect() already matched, see
                    // BmsTypeDetector.kt:82 and KableBmsRepository.kt:454-455),
                    // so at most one of the two sections below is ever
                    // preselected.
                    val preselected = preselectedChoice(device)

                    val controllerSection: @Composable () -> Unit = {
                        SectionHeader(stringResource(Res.string.picker_section_controller))
                        // All four are legal manual choices even where the protocol lands
                        // later (e.g. FarDriver) — Task 5 decides what connecting does,
                        // this sheet never hides or disables a type.
                        ControllerType.entries.forEach { type ->
                            TypeRow(
                                label = type.label,
                                selected = preselected == SourceChoice.Controller(type),
                                onClick = { component.onConnectWithType(device, SourceChoice.Controller(type)) }
                            )
                        }
                    }
                    val batterySection: @Composable () -> Unit = {
                        SectionHeader(stringResource(Res.string.picker_section_battery))
                        // VESC_BMS is gateway-hosted (produced only via the VESC gateway in a
                        // later part), never a manually-picked direct BMS — excluding it here
                        // keeps the not-yet-implemented createProtocol stub unreachable.
                        BmsType.entries.filter { it != BmsType.VESC_BMS }.forEach { type ->
                            TypeRow(
                                label = bmsTypeLabel(type),
                                selected = preselected == SourceChoice.Battery(type),
                                onClick = { component.onConnectWithType(device, SourceChoice.Battery(type)) }
                            )
                        }
                    }

                    // The section matching this device's detection renders first (and
                    // carries the highlight below), so the common case is one tap.
                    // Both always render — detection is a hint, not a lock, so an
                    // unrecognised (or misdetected) device can still pick either kind.
                    if (preselected is SourceChoice.Battery) {
                        batterySection()
                        controllerSection()
                    } else {
                        controllerSection()
                        batterySection()
                    }
                }
            }
        }
    }
}

@Composable
private fun TypeRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(14.dp)
    ) {
        Text(
            label,
            fontSize = 14.sp,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurface
        )
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
        Avatar(letter = iconKeyToEmoji(vehicle.iconKey), bg = MaterialTheme.colorScheme.primary)
        Column(modifier = Modifier.weight(1f)) {
            Text(vehicle.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimaryContainer)
            // Drop the whole source segment (and its separator) when there is no
            // label, rather than leaving a dangling "·" or an empty slot.
            val source = vehicleSourceLabel(vehicle)
            Text(
                if (source != null) "$source  ·  saved" else "saved",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
            )
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
            Text(device.name ?: "BMS ${device.address.takeLast(4)}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val typeLabel = when {
                device.bmsType != null -> bmsTypeLabel(device.bmsType)
                device.controllerType != null -> device.controllerType.label
                else -> stringResource(Res.string.picker_type_unknown)
            }
            Text("$typeLabel  ·  ${device.rssi} dBm", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
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
