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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import ru.sodovaya.volty.domain.model.DashboardStyle
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.social.VoiceMicrophoneSource
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionPackageState
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionPackageFailure
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionPackageStatus
import ru.sodovaya.volty.presentation.common.vehicleSourceLabel
import ru.sodovaya.volty.presentation.common.chemistryLabel
import ru.sodovaya.volty.presentation.common.dashboardStyleLabel
import ru.sodovaya.volty.presentation.common.iconKeyToEmoji
import ru.sodovaya.volty.util.UnitSystem
import org.jetbrains.compose.resources.stringResource
import volty.composeapp.generated.resources.Res
import volty.composeapp.generated.resources.action_cancel
import volty.composeapp.generated.resources.settings_add_new_battery
import volty.composeapp.generated.resources.settings_auto_connect_countdown
import volty.composeapp.generated.resources.settings_dashboard_style
import volty.composeapp.generated.resources.settings_fault_display_duration
import volty.composeapp.generated.resources.settings_fault_display_duration_active
import volty.composeapp.generated.resources.settings_minutes
import volty.composeapp.generated.resources.settings_delete
import volty.composeapp.generated.resources.settings_delete_text
import volty.composeapp.generated.resources.settings_diagnostics
import volty.composeapp.generated.resources.settings_send_logs
import volty.composeapp.generated.resources.settings_send_logs_subtitle
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
import volty.composeapp.generated.resources.settings_units
import volty.composeapp.generated.resources.settings_units_imperial
import volty.composeapp.generated.resources.settings_units_metric
import volty.composeapp.generated.resources.settings_voice_microphone
import volty.composeapp.generated.resources.settings_voice_microphone_auto
import volty.composeapp.generated.resources.settings_voice_microphone_headset
import volty.composeapp.generated.resources.settings_voice_microphone_phone
import volty.composeapp.generated.resources.settings_voice_microphone_subtitle
import volty.composeapp.generated.resources.settings_offline_navigation
import volty.composeapp.generated.resources.settings_offline_navigation_subtitle
import volty.composeapp.generated.resources.settings_offline_mobile_data
import volty.composeapp.generated.resources.settings_offline_refresh
import volty.composeapp.generated.resources.settings_offline_refreshing
import volty.composeapp.generated.resources.settings_offline_catalog_failed
import volty.composeapp.generated.resources.settings_offline_not_configured
import volty.composeapp.generated.resources.settings_offline_size_line
import volty.composeapp.generated.resources.settings_offline_download
import volty.composeapp.generated.resources.settings_offline_download_mobile
import volty.composeapp.generated.resources.settings_offline_pause
import volty.composeapp.generated.resources.settings_offline_resume
import volty.composeapp.generated.resources.settings_offline_delete
import volty.composeapp.generated.resources.settings_offline_status_ready
import volty.composeapp.generated.resources.settings_offline_status_update
import volty.composeapp.generated.resources.settings_offline_status_downloading
import volty.composeapp.generated.resources.settings_offline_status_paused
import volty.composeapp.generated.resources.settings_offline_status_waiting_network
import volty.composeapp.generated.resources.settings_offline_status_metered
import volty.composeapp.generated.resources.settings_offline_status_queued
import volty.composeapp.generated.resources.settings_offline_status_installing
import volty.composeapp.generated.resources.settings_offline_status_failed
import volty.composeapp.generated.resources.settings_offline_status_deleting
import volty.composeapp.generated.resources.settings_offline_delete_title
import volty.composeapp.generated.resources.settings_offline_delete_text

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(component: SettingsComponent) {
    val state by component.state.collectAsState()
    var pendingDelete by remember { mutableStateOf<Vehicle?>(null) }
    var pendingOfflineDelete by remember { mutableStateOf<OfflineRegionPackageState?>(null) }

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
                valueRange = 1f..15f,
                steps = 13
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

            // UNITS
            SectionLabel(stringResource(Res.string.settings_units))
            val unitSystems = listOf(UnitSystem.METRIC, UnitSystem.IMPERIAL)
            val unitLabels = mapOf(
                UnitSystem.METRIC to stringResource(Res.string.settings_units_metric),
                UnitSystem.IMPERIAL to stringResource(Res.string.settings_units_imperial)
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                unitSystems.forEachIndexed { idx, u ->
                    SegmentedButton(
                        selected = state.unitSystem == u,
                        onClick = { component.onUnitSystemChanged(u) },
                        shape = SegmentedButtonDefaults.itemShape(index = idx, count = unitSystems.size)
                    ) { Text(unitLabels[u] ?: u.name) }
                }
            }

            // NEARBY VOICE MICROPHONE
            SectionLabel(stringResource(Res.string.settings_voice_microphone))
            Text(
                stringResource(Res.string.settings_voice_microphone_subtitle),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val microphoneSources = listOf(
                VoiceMicrophoneSource.AUTO,
                VoiceMicrophoneSource.PHONE,
                VoiceMicrophoneSource.HEADSET,
            )
            val microphoneLabels = mapOf(
                VoiceMicrophoneSource.AUTO to stringResource(Res.string.settings_voice_microphone_auto),
                VoiceMicrophoneSource.PHONE to stringResource(Res.string.settings_voice_microphone_phone),
                VoiceMicrophoneSource.HEADSET to stringResource(Res.string.settings_voice_microphone_headset),
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                microphoneSources.forEachIndexed { idx, source ->
                    SegmentedButton(
                        selected = state.voiceMicrophoneSource == source,
                        onClick = { component.onVoiceMicrophoneSourceChanged(source) },
                        shape = SegmentedButtonDefaults.itemShape(index = idx, count = microphoneSources.size),
                    ) { Text(microphoneLabels[source] ?: source.name) }
                }
            }

            // DASHBOARD STYLE (app default)
            SectionLabel(stringResource(Res.string.settings_dashboard_style))
            val dashboardStyles = DashboardStyle.entries
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                dashboardStyles.forEachIndexed { idx, style ->
                    SegmentedButton(
                        selected = state.defaultDashboardStyle == style,
                        onClick = { component.onDefaultDashboardStyleChanged(style) },
                        shape = SegmentedButtonDefaults.itemShape(index = idx, count = dashboardStyles.size)
                    ) { Text(dashboardStyleLabel(style)) }
                }
            }

            // FAULT DISPLAY DURATION
            SectionLabel(stringResource(Res.string.settings_fault_display_duration))
            Text(
                if (state.faultDisplayDurationSec == 0) {
                    stringResource(Res.string.settings_fault_display_duration_active)
                } else {
                    stringResource(Res.string.settings_minutes, state.faultDisplayDurationSec / 60)
                },
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Slider(
                value = state.faultDisplayDurationSec.toFloat(),
                onValueChange = { component.onFaultDisplayDurationChanged(it.toInt()) },
                valueRange = 0f..300f,
                steps = 9
            )

            HorizontalDivider()

            // OFFLINE NAVIGATION REGIONS
            SectionLabel(stringResource(Res.string.settings_offline_navigation))
            Text(
                stringResource(Res.string.settings_offline_navigation_subtitle),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(Res.string.settings_offline_attribution),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(Res.string.settings_offline_mobile_data),
                    modifier = Modifier.weight(1f),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
                Switch(
                    checked = state.offlineSkipMeteredConfirmation,
                    onCheckedChange = component::onOfflineSkipMeteredConfirmationChanged,
                )
            }
            TextButton(
                onClick = component::onRefreshOfflineRegions,
                enabled = !state.offlineCatalogRefreshing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.offlineCatalogRefreshing) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.size(6.dp))
                Text(
                    stringResource(
                        if (state.offlineCatalogRefreshing) {
                            Res.string.settings_offline_refreshing
                        } else {
                            Res.string.settings_offline_refresh
                        }
                    )
                )
            }
            if (state.offlineCatalogError) {
                Text(
                    stringResource(Res.string.settings_offline_catalog_failed),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (state.offlineRegions.isEmpty()) {
                Text(
                    stringResource(Res.string.settings_offline_not_configured),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                state.offlineRegions.forEach { region ->
                    OfflineRegionRow(
                        region = region,
                        component = component,
                        onDeleteRequest = { pendingOfflineDelete = region },
                    )
                }
            }

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

            HorizontalDivider()

            // DIAGNOSTICS
            SectionLabel(stringResource(Res.string.settings_diagnostics))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .clickable(onClick = component::onSendLogs)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    Icons.Default.Share,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(Res.string.settings_send_logs), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(
                        stringResource(Res.string.settings_send_logs_subtitle),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
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
        pendingOfflineDelete?.let { region ->
            AlertDialog(
                onDismissRequest = { pendingOfflineDelete = null },
                confirmButton = {
                    TextButton(onClick = {
                        component.onDeleteOfflineRegion(region.region.regionId)
                        pendingOfflineDelete = null
                    }) {
                        Text(
                            stringResource(Res.string.settings_offline_delete),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingOfflineDelete = null }) {
                        Text(stringResource(Res.string.action_cancel))
                    }
                },
                title = { Text(stringResource(Res.string.settings_offline_delete_title, region.region.displayName)) },
                text = { Text(stringResource(Res.string.settings_offline_delete_text)) },
            )
        }
    }
}

@Composable
private fun OfflineRegionRow(
    region: OfflineRegionPackageState,
    component: SettingsComponent,
    onDeleteRequest: () -> Unit,
) {
    val release = region.latestRelease
    val version = when (region.status) {
        OfflineRegionPackageStatus.UPDATE_AVAILABLE -> release?.releaseVersion
        else -> region.installedReleaseVersion ?: release?.releaseVersion
    } ?: "—"
    val action: (() -> Unit)?
    val actionIcon: androidx.compose.ui.graphics.vector.ImageVector?
    val actionText: String
    when (region.status) {
        OfflineRegionPackageStatus.READY -> {
            action = onDeleteRequest
            actionIcon = Icons.Default.Delete
            actionText = stringResource(Res.string.settings_offline_delete)
        }
        OfflineRegionPackageStatus.DOWNLOADING,
        OfflineRegionPackageStatus.INSTALLING,
        OfflineRegionPackageStatus.VERIFYING,
        OfflineRegionPackageStatus.DELETING -> {
            action = if (region.status == OfflineRegionPackageStatus.DOWNLOADING) {
                { component.onPauseOfflineRegion(region.region.regionId) }
            } else null
            actionIcon = if (action != null) Icons.Default.Pause else null
            actionText = stringResource(Res.string.settings_offline_pause)
        }
        OfflineRegionPackageStatus.PAUSED,
        OfflineRegionPackageStatus.WAITING_FOR_NETWORK,
        OfflineRegionPackageStatus.QUEUED -> {
            action = { component.onResumeOfflineRegion(region.region.regionId) }
            actionIcon = Icons.Default.PlayArrow
            actionText = stringResource(Res.string.settings_offline_resume)
        }
        OfflineRegionPackageStatus.AWAITING_METERED_APPROVAL -> {
            action = { component.onConfirmMeteredOfflineRegion(region.region.regionId) }
            actionIcon = Icons.Default.Download
            actionText = stringResource(Res.string.settings_offline_download_mobile)
        }
        OfflineRegionPackageStatus.NOT_INSTALLED,
        OfflineRegionPackageStatus.UPDATE_AVAILABLE,
        OfflineRegionPackageStatus.FAILED -> {
            action = { component.onDownloadOfflineRegion(region.region.regionId) }
            actionIcon = Icons.Default.Download
            actionText = stringResource(Res.string.settings_offline_download)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(region.region.displayName, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(
            offlineRegionStatusText(region, version),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (release != null) {
            Text(
                stringResource(
                    Res.string.settings_offline_size_line,
                    formatOfflineBytes(region.totalDownloadBytes),
                    formatOfflineBytes(region.totalInstalledBytes),
                ),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (action != null && actionIcon != null) {
            TextButton(onClick = action) {
                Icon(actionIcon, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.size(6.dp))
                Text(actionText)
            }
        }
    }
}

@Composable
private fun offlineRegionStatusText(
    region: OfflineRegionPackageState,
    version: String,
): String = when (region.status) {
    OfflineRegionPackageStatus.READY -> stringResource(Res.string.settings_offline_status_ready, version)
    OfflineRegionPackageStatus.UPDATE_AVAILABLE -> stringResource(Res.string.settings_offline_status_update, version)
    OfflineRegionPackageStatus.DOWNLOADING -> stringResource(
        Res.string.settings_offline_status_downloading,
        formatOfflineBytes(region.downloadedBytes),
        formatOfflineBytes(region.totalDownloadBytes),
    )
    OfflineRegionPackageStatus.PAUSED -> stringResource(
        Res.string.settings_offline_status_paused,
        formatOfflineBytes(region.downloadedBytes),
        formatOfflineBytes(region.totalDownloadBytes),
    )
    OfflineRegionPackageStatus.WAITING_FOR_NETWORK ->
        stringResource(Res.string.settings_offline_status_waiting_network)
    OfflineRegionPackageStatus.AWAITING_METERED_APPROVAL ->
        stringResource(Res.string.settings_offline_status_metered)
    OfflineRegionPackageStatus.QUEUED ->
        stringResource(Res.string.settings_offline_status_queued)
    OfflineRegionPackageStatus.INSTALLING,
    OfflineRegionPackageStatus.VERIFYING,
    OfflineRegionPackageStatus.DELETING -> stringResource(Res.string.settings_offline_status_deleting)
    OfflineRegionPackageStatus.FAILED -> when (region.failure) {
        OfflineRegionPackageFailure.NETWORK -> stringResource(Res.string.settings_offline_status_failed_network)
        OfflineRegionPackageFailure.STORAGE -> stringResource(Res.string.settings_offline_status_failed_storage)
        OfflineRegionPackageFailure.CHECKSUM -> stringResource(Res.string.settings_offline_status_failed_checksum)
        OfflineRegionPackageFailure.INCOMPATIBLE -> stringResource(Res.string.settings_offline_status_failed_incompatible)
        OfflineRegionPackageFailure.CANCELLED -> stringResource(Res.string.settings_offline_status_failed_cancelled)
        OfflineRegionPackageFailure.UNKNOWN,
        null -> stringResource(Res.string.settings_offline_status_failed)
    }
    OfflineRegionPackageStatus.NOT_INSTALLED -> version
}

private fun formatOfflineBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> "%.1f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
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
            // Chemistry always renders; the source segment and its separator
            // drop out together when the vehicle has no nameable source.
            val source = vehicleSourceLabel(vehicle)
            val chemistry = chemistryLabel(vehicle.chemistry)
            Text(
                if (source != null) "$source  ·  $chemistry" else chemistry,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        TextButton(onClick = onDelete) { Text(stringResource(Res.string.settings_delete), color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
    }
}
