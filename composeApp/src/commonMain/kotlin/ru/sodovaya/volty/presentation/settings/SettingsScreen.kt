package ru.sodovaya.volty.presentation.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.sodovaya.volty.domain.model.DashboardStyle
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.social.VoiceMicrophoneSource
import ru.sodovaya.volty.domain.voice.VoiceOutputPreference
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionPackageState
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionPackageFailure
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionPackageStatus
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionPreparationState
import ru.sodovaya.volty.domain.navigation.region.OfflineByteFormat
import ru.sodovaya.volty.domain.navigation.region.OfflineByteUnit
import ru.sodovaya.volty.domain.navigation.region.OfflineCurrentCoverage
import volty.composeapp.generated.resources.offline_unit_b
import volty.composeapp.generated.resources.offline_unit_kb
import volty.composeapp.generated.resources.offline_unit_mb
import volty.composeapp.generated.resources.offline_unit_gb
import volty.composeapp.generated.resources.settings_offline_coverage_unavailable
import volty.composeapp.generated.resources.settings_offline_coverage_download
import volty.composeapp.generated.resources.settings_offline_storage_line
import ru.sodovaya.volty.domain.navigation.offline.OfflineMapPackState
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
import volty.composeapp.generated.resources.settings_connection
import volty.composeapp.generated.resources.settings_fault_display_duration
import volty.composeapp.generated.resources.settings_fault_display_duration_active
import volty.composeapp.generated.resources.settings_fault_display_duration_hint
import volty.composeapp.generated.resources.settings_critical_alert_display_duration
import volty.composeapp.generated.resources.settings_critical_alert_display_duration_hint
import volty.composeapp.generated.resources.settings_warning_alert_display_duration
import volty.composeapp.generated.resources.settings_warning_alert_display_duration_hint
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
import volty.composeapp.generated.resources.settings_offline_attribution
import volty.composeapp.generated.resources.settings_offline_status_failed_cancelled
import volty.composeapp.generated.resources.settings_offline_status_failed_checksum
import volty.composeapp.generated.resources.settings_offline_status_failed_incompatible
import volty.composeapp.generated.resources.settings_offline_status_failed_network
import volty.composeapp.generated.resources.settings_offline_status_failed_storage
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
import volty.composeapp.generated.resources.settings_voice_microphone_auto_hint
import volty.composeapp.generated.resources.settings_voice_microphone_phone_hint
import volty.composeapp.generated.resources.settings_voice_microphone_headset_hint
import volty.composeapp.generated.resources.settings_voice_microphone_phone
import volty.composeapp.generated.resources.settings_voice_microphone_subtitle
import volty.composeapp.generated.resources.settings_voice_audio
import volty.composeapp.generated.resources.settings_sound_section
import volty.composeapp.generated.resources.settings_mic_section
import volty.composeapp.generated.resources.settings_mic_auto_gain
import volty.composeapp.generated.resources.settings_mic_auto_gain_hint
import volty.composeapp.generated.resources.settings_mic_sensitivity
import volty.composeapp.generated.resources.settings_mic_ceiling
import volty.composeapp.generated.resources.settings_mic_ceiling_hint
import volty.composeapp.generated.resources.settings_mic_phone_ns
import volty.composeapp.generated.resources.settings_mic_phone_ns_hint
import volty.composeapp.generated.resources.settings_mic_speech_gate
import volty.composeapp.generated.resources.settings_mic_speech_gate_hint
import volty.composeapp.generated.resources.settings_mic_speech_gate_threshold
import volty.composeapp.generated.resources.settings_mic_speech_gate_threshold_hint
import ru.sodovaya.volty.domain.audio.MIN_SPEECH_GATE_THRESHOLD
import ru.sodovaya.volty.domain.audio.MAX_SPEECH_GATE_THRESHOLD
import volty.composeapp.generated.resources.settings_mic_db
import volty.composeapp.generated.resources.settings_mic_dbfs
import volty.composeapp.generated.resources.settings_voice_volume
import ru.sodovaya.volty.domain.audio.MAX_MIC_SENSITIVITY_DB
import volty.composeapp.generated.resources.settings_voice_volume_db
import ru.sodovaya.volty.domain.audio.MAX_LISTEN_GAIN_DB
import kotlin.math.roundToInt
import volty.composeapp.generated.resources.settings_voice_output
import volty.composeapp.generated.resources.settings_voice_output_auto
import volty.composeapp.generated.resources.settings_voice_output_headset
import volty.composeapp.generated.resources.settings_voice_output_speaker
import volty.composeapp.generated.resources.settings_offline_navigation
import volty.composeapp.generated.resources.settings_offline_navigation_subtitle
import volty.composeapp.generated.resources.settings_offline_mobile_data
import volty.composeapp.generated.resources.settings_offline_refresh
import volty.composeapp.generated.resources.settings_offline_refreshing
import volty.composeapp.generated.resources.settings_offline_catalog_failed
import volty.composeapp.generated.resources.settings_offline_not_configured
import volty.composeapp.generated.resources.settings_offline_add_region
import volty.composeapp.generated.resources.settings_offline_no_region_match
import volty.composeapp.generated.resources.settings_offline_size_line
import volty.composeapp.generated.resources.settings_offline_download
import volty.composeapp.generated.resources.settings_offline_download_mobile
import volty.composeapp.generated.resources.settings_offline_prepare
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
import volty.composeapp.generated.resources.settings_offline_status_preparing
import volty.composeapp.generated.resources.settings_offline_status_installing
import volty.composeapp.generated.resources.settings_offline_status_failed
import volty.composeapp.generated.resources.settings_offline_status_deleting
import volty.composeapp.generated.resources.settings_offline_delete_title
import volty.composeapp.generated.resources.settings_offline_delete_text
import volty.composeapp.generated.resources.settings_tab_general
import volty.composeapp.generated.resources.settings_tab_debug

import volty.composeapp.generated.resources.settings_page_back
import volty.composeapp.generated.resources.settings_group_drive
import volty.composeapp.generated.resources.settings_group_sound
import volty.composeapp.generated.resources.settings_group_app
import volty.composeapp.generated.resources.settings_group_vehicles
import volty.composeapp.generated.resources.settings_page_dashboard
import volty.composeapp.generated.resources.settings_page_units
import volty.composeapp.generated.resources.settings_page_connection
import volty.composeapp.generated.resources.settings_page_alerts
import volty.composeapp.generated.resources.settings_page_voice
import volty.composeapp.generated.resources.settings_page_microphone
import volty.composeapp.generated.resources.settings_page_offline_maps
import volty.composeapp.generated.resources.settings_page_theme
import volty.composeapp.generated.resources.settings_page_developer
import volty.composeapp.generated.resources.settings_summary_connection
import volty.composeapp.generated.resources.settings_summary_theme_dynamic
import volty.composeapp.generated.resources.settings_summary_alerts
import volty.composeapp.generated.resources.settings_summary_voice_ducking
import volty.composeapp.generated.resources.settings_summary_voice_plain
import volty.composeapp.generated.resources.settings_summary_mic_none
import volty.composeapp.generated.resources.settings_summary_mic_auto_gain
import volty.composeapp.generated.resources.settings_summary_mic_phone_ns
import volty.composeapp.generated.resources.settings_summary_mic_speech_gate
import volty.composeapp.generated.resources.settings_summary_offline_none
import volty.composeapp.generated.resources.settings_summary_offline_installed
import volty.composeapp.generated.resources.settings_summary_offline_downloading
import volty.composeapp.generated.resources.settings_summary_developer
import volty.composeapp.generated.resources.settings_voice_duck_music
import volty.composeapp.generated.resources.settings_voice_duck_music_hint
import volty.composeapp.generated.resources.alerts_music_mode
import volty.composeapp.generated.resources.alerts_music_mode_subtitle
import volty.composeapp.generated.resources.alerts_music_mode_duck
import volty.composeapp.generated.resources.alerts_music_mode_overlay
import volty.composeapp.generated.resources.alerts_sound_kind
import volty.composeapp.generated.resources.alerts_sound_kind_subtitle
import volty.composeapp.generated.resources.alerts_sound_kind_navigation
import volty.composeapp.generated.resources.alerts_sound_kind_alarm
import volty.composeapp.generated.resources.alerts_gain
import volty.composeapp.generated.resources.alerts_gain_value
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import ru.sodovaya.volty.domain.alert.AlarmMusicMode
import ru.sodovaya.volty.domain.audio.AlertSoundKind
import ru.sodovaya.volty.domain.audio.MAX_ALERT_GAIN_DB
import ru.sodovaya.volty.domain.audio.VoiceListenSettings
import ru.sodovaya.volty.presentation.common.settings.SettingsChoiceRow
import ru.sodovaya.volty.presentation.ride.LINGER_OPTIONS_SEC
import ru.sodovaya.volty.presentation.ride.lingerOptionFor
import ru.sodovaya.volty.presentation.common.settings.PageDirection
import ru.sodovaya.volty.presentation.common.settings.SettingsExpandableRow
import ru.sodovaya.volty.presentation.common.settings.SettingsGroup
import ru.sodovaya.volty.presentation.common.settings.pageDirection
import ru.sodovaya.volty.presentation.common.settings.toggleExpanded
import ru.sodovaya.volty.presentation.common.settings.SettingsNavRow
import ru.sodovaya.volty.presentation.common.settings.SettingsRowDivider
import ru.sodovaya.volty.presentation.common.settings.SettingsSliderRow
import ru.sodovaya.volty.presentation.common.settings.SettingsSwitchRow
import ru.sodovaya.volty.presentation.common.settings.SettingsTint
private const val SETTINGS_PAGE_MS = 250

/** The settings slider stops at -10 dB; the per-participant slider alone reaches the -30 dB floor. */
private const val SETTINGS_VOICE_GAIN_MIN_DB = -10f
private const val SETTINGS_MIC_CEILING_MIN_DBFS = -12f
private const val SETTINGS_MIC_CEILING_MAX_DBFS = -1f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(component: SettingsComponent) {
    val state by component.state.collectAsState()
    val page by component.page.collectAsState()
    var pendingOfflineDelete by remember { mutableStateOf<OfflineRegionPackageState?>(null) }
    var offlineRegionQuery by remember { mutableStateOf("") }
    var expandedName by rememberSaveable { mutableStateOf<String?>(null) }
    val expanded = SettingsInlineSection.entries.firstOrNull { it.name == expandedName }
    val hubScroll = rememberScrollState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        page?.let { settingsPageTitle(it) } ?: stringResource(Res.string.settings_title),
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = component::onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.settings_page_back))
                    }
                }
            )
        }
    ) { padding ->
        AnimatedContent(
            targetState = page,
            modifier = Modifier.padding(padding).fillMaxSize(),
            transitionSpec = {
                val back = pageDirection(targetIsHub = targetState == null) == PageDirection.BACK
                val spec = tween<IntOffset>(SETTINGS_PAGE_MS)
                val fade = tween<Float>(SETTINGS_PAGE_MS)
                if (back) {
                    (slideInHorizontally(spec) { -it / 4 } + fadeIn(fade)) togetherWith
                        (slideOutHorizontally(spec) { it } + fadeOut(fade))
                } else {
                    (slideInHorizontally(spec) { it } + fadeIn(fade)) togetherWith
                        (slideOutHorizontally(spec) { -it / 4 } + fadeOut(fade))
                }
            },
            label = "settings-page",
        ) { shown ->
        // The hub keeps its scroll position while a page is open; a page starts at the top each time.
        val scroll = if (shown == null) hubScroll else rememberScrollState()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            when (shown) {
                null -> SettingsHub(
                    state = state,
                    component = component,
                    expanded = expanded,
                    onToggle = { expandedName = toggleExpanded(expanded, it)?.name },
                )
                SettingsPage.ALERT_SOUND -> AlertSoundPage(state, component)
                SettingsPage.VOICE -> VoicePage(state, component)
                SettingsPage.MICROPHONE -> MicrophonePage(state, component)
                SettingsPage.OFFLINE_MAPS -> OfflineMapsPage(
                    state = state,
                    component = component,
                    query = offlineRegionQuery,
                    onQueryChange = { offlineRegionQuery = it },
                    onDeleteRequest = { pendingOfflineDelete = it },
                )
                SettingsPage.DEVELOPER -> DeveloperPage(state, component)
            }
            Spacer(Modifier.height(24.dp))
        }
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
                title = { Text(stringResource(Res.string.settings_offline_delete_title, OfflineRegionDisplayNamePolicy.displayName(region.region))) },
                text = { Text(stringResource(Res.string.settings_offline_delete_text)) },
            )
        }
    }
}

@Composable
private fun settingsPageTitle(page: SettingsPage): String = stringResource(
    when (page) {
        SettingsPage.ALERT_SOUND -> Res.string.settings_page_alerts
        SettingsPage.VOICE -> Res.string.settings_page_voice
        SettingsPage.MICROPHONE -> Res.string.settings_page_microphone
        SettingsPage.OFFLINE_MAPS -> Res.string.settings_page_offline_maps
        SettingsPage.DEVELOPER -> Res.string.settings_page_developer
    }
)

// ---------------------------------------------------------------------------------------------
// The hub
// ---------------------------------------------------------------------------------------------

@Composable
private fun SettingsHub(
    state: SettingsComponent.State,
    component: SettingsComponent,
    expanded: SettingsInlineSection?,
    onToggle: (SettingsInlineSection) -> Unit,
) {
    SettingsGroup(stringResource(Res.string.settings_group_drive)) {
        SettingsExpandableRow(
            icon = Icons.Filled.Speed,
            tint = SettingsTint.DRIVE,
            title = stringResource(Res.string.settings_page_dashboard),
            summary = dashboardStyleLabel(state.defaultDashboardStyle),
            expanded = expanded == SettingsInlineSection.DASHBOARD,
            onToggle = { onToggle(SettingsInlineSection.DASHBOARD) },
        ) {
            DashboardControls(state, component)
        }
        SettingsRowDivider()
        SettingsExpandableRow(
            icon = Icons.Filled.Straighten,
            tint = SettingsTint.DRIVE,
            title = stringResource(Res.string.settings_page_units),
            summary = unitSystemLabel(state.unitSystem),
            expanded = expanded == SettingsInlineSection.UNITS,
            onToggle = { onToggle(SettingsInlineSection.UNITS) },
        ) {
            UnitsControls(state, component)
        }
        SettingsRowDivider()
        SettingsExpandableRow(
            icon = Icons.Filled.Bluetooth,
            tint = SettingsTint.DRIVE,
            title = stringResource(Res.string.settings_page_connection),
            summary = stringResource(Res.string.settings_summary_connection, state.scanTimeoutSec, state.autoConnectCountdownSec),
            expanded = expanded == SettingsInlineSection.CONNECTION,
            onToggle = { onToggle(SettingsInlineSection.CONNECTION) },
        ) {
            ConnectionControls(state, component)
        }
    }
    SettingsGroup(stringResource(Res.string.settings_group_sound)) {
        SettingsNavRow(
            icon = Icons.Filled.NotificationsActive,
            tint = SettingsTint.SOUND,
            title = stringResource(Res.string.settings_page_alerts),
            summary = stringResource(
                Res.string.settings_summary_alerts,
                alertSoundKindLabel(state.alertSound.kind),
                state.alertSound.gainDb.roundToInt(),
            ),
            onClick = { component.onOpenPage(SettingsPage.ALERT_SOUND) },
        )
        SettingsRowDivider()
        val (gainDb, duck) = voiceSummary(
            VoiceListenSettings(duckMusicWhileSpeaking = state.voiceDuckMusic, masterGainDb = state.voiceMasterGainDb)
        )
        SettingsNavRow(
            icon = Icons.Filled.Headphones,
            tint = SettingsTint.SOUND,
            title = stringResource(Res.string.settings_page_voice),
            summary = stringResource(
                if (duck) Res.string.settings_summary_voice_ducking else Res.string.settings_summary_voice_plain,
                gainDb.roundToInt(),
            ),
            onClick = { component.onOpenPage(SettingsPage.VOICE) },
        )
        SettingsRowDivider()
        SettingsNavRow(
            icon = Icons.Filled.Mic,
            tint = SettingsTint.SOUND,
            title = stringResource(Res.string.settings_page_microphone),
            summary = micSummaryText(state),
            onClick = { component.onOpenPage(SettingsPage.MICROPHONE) },
        )
    }
    SettingsGroup(stringResource(Res.string.settings_group_app)) {
        SettingsNavRow(
            icon = Icons.Filled.Map,
            tint = SettingsTint.MAPS,
            title = stringResource(Res.string.settings_page_offline_maps),
            summary = offlineSummaryText(state),
            onClick = { component.onOpenPage(SettingsPage.OFFLINE_MAPS) },
        )
        SettingsRowDivider()
        SettingsExpandableRow(
            icon = Icons.Filled.Palette,
            tint = SettingsTint.MAPS,
            title = stringResource(Res.string.settings_page_theme),
            summary = themeSummaryText(state),
            expanded = expanded == SettingsInlineSection.THEME,
            onToggle = { onToggle(SettingsInlineSection.THEME) },
        ) {
            ThemeControls(state, component)
        }
    }
    SettingsGroup(stringResource(Res.string.settings_group_vehicles)) {
        state.vehicles.forEach { v ->
            VehicleRow(vehicle = v, onEdit = { component.onEditVehicle(v.id) })
            SettingsRowDivider()
        }
        TextButton(onClick = component::onAddBattery, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(Res.string.settings_add_new_battery))
        }
    }
    SettingsGroup(null) {
        SettingsNavRow(
            icon = Icons.Filled.Code,
            tint = SettingsTint.NEUTRAL,
            title = stringResource(Res.string.settings_page_developer),
            summary = stringResource(Res.string.settings_summary_developer),
            onClick = { component.onOpenPage(SettingsPage.DEVELOPER) },
        )
    }
}

@Composable
private fun unitSystemLabel(system: UnitSystem): String = stringResource(
    when (system) {
        UnitSystem.METRIC -> Res.string.settings_units_metric
        UnitSystem.IMPERIAL -> Res.string.settings_units_imperial
    }
)

@Composable
private fun themeLabel(mode: String): String = stringResource(
    when (mode) {
        "light" -> Res.string.settings_theme_light
        "dark" -> Res.string.settings_theme_dark
        else -> Res.string.settings_theme_system
    }
)

@Composable
private fun themeSummaryText(state: SettingsComponent.State): String =
    if (state.dynamicColor) {
        stringResource(Res.string.settings_summary_theme_dynamic, themeLabel(state.themeMode))
    } else {
        themeLabel(state.themeMode)
    }

@Composable
private fun alertSoundKindLabel(kind: AlertSoundKind): String = stringResource(
    when (kind) {
        AlertSoundKind.NAVIGATION -> Res.string.alerts_sound_kind_navigation
        AlertSoundKind.ALARM -> Res.string.alerts_sound_kind_alarm
    }
)

@Composable
private fun micSummaryText(state: SettingsComponent.State): String {
    val parts = micSummaryParts(state.mic)
    if (parts.isEmpty()) return stringResource(Res.string.settings_summary_mic_none)
    val labels = parts.map { part ->
        stringResource(
            when (part) {
                MicSummaryPart.AUTO_GAIN -> Res.string.settings_summary_mic_auto_gain
                MicSummaryPart.PHONE_NS -> Res.string.settings_summary_mic_phone_ns
                MicSummaryPart.SPEECH_GATE -> Res.string.settings_summary_mic_speech_gate
            }
        )
    }
    return labels.joinToString(", ").replaceFirstChar { it.uppercase() }
}

@Composable
private fun offlineSummaryText(state: SettingsComponent.State): String {
    val summary = offlineMapsSummary(state.offlineRegions)
    val base = if (summary.installedRegions == 0) {
        stringResource(Res.string.settings_summary_offline_none)
    } else {
        stringResource(
            Res.string.settings_summary_offline_installed,
            summary.installedRegions,
            formatOfflineBytes(summary.installedBytes),
        )
    }
    return if (summary.downloading > 0) {
        stringResource(Res.string.settings_summary_offline_downloading, base, summary.downloading)
    } else {
        base
    }
}

// ---------------------------------------------------------------------------------------------
// Pages. Each existing setting lives on exactly one of them.
// ---------------------------------------------------------------------------------------------

@Composable
private fun ColumnScope.ThemeControls(state: SettingsComponent.State, component: SettingsComponent) {
    run {
        SettingsChoiceRow(
            title = stringResource(Res.string.settings_theme),
            options = listOf("system", "light", "dark").map { it to themeLabel(it) },
            selected = state.themeMode,
            onSelect = component::onThemeChanged,
        )
        SettingsRowDivider()
        SettingsSwitchRow(
            title = stringResource(Res.string.settings_dynamic_color),
            hint = stringResource(Res.string.settings_dynamic_color_subtitle),
            checked = state.dynamicColor,
            onChange = component::onDynamicColorChanged,
        )
    }
}

@Composable
private fun ColumnScope.ConnectionControls(state: SettingsComponent.State, component: SettingsComponent) {
    run {
        SettingsSliderRow(
            title = stringResource(Res.string.settings_scan_timeout),
            valueText = stringResource(Res.string.settings_seconds, state.scanTimeoutSec),
            value = state.scanTimeoutSec.toFloat(),
            range = 1f..15f,
            steps = 13,
            onChange = { component.onScanTimeoutChanged(it.toInt()) },
        )
        SettingsRowDivider()
        SettingsSliderRow(
            title = stringResource(Res.string.settings_auto_connect_countdown),
            valueText = stringResource(Res.string.settings_seconds, state.autoConnectCountdownSec),
            value = state.autoConnectCountdownSec.toFloat(),
            range = 0f..10f,
            steps = 9,
            onChange = { component.onAutoConnectCountdownChanged(it.toInt()) },
        )
    }
}

@Composable
private fun ColumnScope.UnitsControls(state: SettingsComponent.State, component: SettingsComponent) {
    run {
        SettingsChoiceRow(
            title = stringResource(Res.string.settings_units),
            options = listOf(UnitSystem.METRIC, UnitSystem.IMPERIAL).map { it to unitSystemLabel(it) },
            selected = state.unitSystem,
            onSelect = component::onUnitSystemChanged,
        )
    }
}

@Composable
private fun ColumnScope.DashboardControls(state: SettingsComponent.State, component: SettingsComponent) {
    run {
        SettingsChoiceRow(
            title = stringResource(Res.string.settings_dashboard_style),
            options = DashboardStyle.entries.map { it to dashboardStyleLabel(it) },
            selected = state.defaultDashboardStyle,
            onSelect = component::onDefaultDashboardStyleChanged,
        )
        SettingsRowDivider()
        SettingsChoiceRow(
            title = stringResource(Res.string.settings_fault_display_duration),
            hint = stringResource(Res.string.settings_fault_display_duration_hint),
            options = LINGER_OPTIONS_SEC.map { it to lingerLabel(it) },
            selected = lingerOptionFor(state.faultDisplayDurationSec),
            onSelect = component::onFaultDisplayDurationChanged,
        )
        SettingsRowDivider()
        SettingsChoiceRow(
            title = stringResource(Res.string.settings_critical_alert_display_duration),
            hint = stringResource(Res.string.settings_critical_alert_display_duration_hint),
            options = LINGER_OPTIONS_SEC.map { it to lingerLabel(it) },
            selected = lingerOptionFor(state.criticalAlertDisplayDurationSec),
            onSelect = component::onCriticalAlertDisplayDurationChanged,
        )
        SettingsRowDivider()
        SettingsChoiceRow(
            title = stringResource(Res.string.settings_warning_alert_display_duration),
            hint = stringResource(Res.string.settings_warning_alert_display_duration_hint),
            options = LINGER_OPTIONS_SEC.map { it to lingerLabel(it) },
            selected = lingerOptionFor(state.warningAlertDisplayDurationSec),
            onSelect = component::onWarningAlertDisplayDurationChanged,
        )
    }
}

@Composable
private fun lingerLabel(seconds: Int): String = when {
    seconds == 0 -> stringResource(Res.string.settings_fault_display_duration_active)
    seconds < 60 -> stringResource(Res.string.settings_seconds, seconds)
    else -> stringResource(Res.string.settings_minutes, seconds / 60)
}

/** The global alert sound; the vehicle alerts screen keeps the same controls over the same prefs. */
@Composable
private fun AlertSoundPage(state: SettingsComponent.State, component: SettingsComponent) {
    SettingsGroup(null) {
        SettingsChoiceRow(
            title = stringResource(Res.string.alerts_music_mode),
            hint = stringResource(Res.string.alerts_music_mode_subtitle),
            options = listOf(
                AlarmMusicMode.DUCK_MEDIA to stringResource(Res.string.alerts_music_mode_duck),
                AlarmMusicMode.PLAY_OVER_MEDIA to stringResource(Res.string.alerts_music_mode_overlay),
            ),
            selected = state.alarmMusicMode,
            onSelect = component::onAlarmMusicModeChanged,
        )
        SettingsRowDivider()
        SettingsChoiceRow(
            title = stringResource(Res.string.alerts_sound_kind),
            hint = stringResource(Res.string.alerts_sound_kind_subtitle),
            options = listOf(
                AlertSoundKind.NAVIGATION to stringResource(Res.string.alerts_sound_kind_navigation),
                AlertSoundKind.ALARM to stringResource(Res.string.alerts_sound_kind_alarm),
            ),
            selected = state.alertSound.kind,
            onSelect = component::onAlertSoundKindChanged,
        )
        SettingsRowDivider()
        SettingsSliderRow(
            title = stringResource(Res.string.alerts_gain),
            valueText = stringResource(Res.string.alerts_gain_value, state.alertSound.gainDb.toInt()),
            value = state.alertSound.gainDb,
            range = 0f..MAX_ALERT_GAIN_DB,
            steps = MAX_ALERT_GAIN_DB.toInt() - 1,
            onChange = { component.onAlertGainChanged(it.toInt().toFloat()) },
        )
    }
}

@Composable
private fun VoicePage(state: SettingsComponent.State, component: SettingsComponent) {
    SettingsGroup(null) {
        SettingsSliderRow(
            title = stringResource(Res.string.settings_voice_volume),
            valueText = stringResource(Res.string.settings_voice_volume_db, state.voiceMasterGainDb.roundToInt()),
            value = state.voiceMasterGainDb.coerceIn(SETTINGS_VOICE_GAIN_MIN_DB, MAX_LISTEN_GAIN_DB),
            range = SETTINGS_VOICE_GAIN_MIN_DB..MAX_LISTEN_GAIN_DB,
            steps = (MAX_LISTEN_GAIN_DB - SETTINGS_VOICE_GAIN_MIN_DB).roundToInt() - 1,
            onChange = { component.onVoiceMasterGainChanged(it.roundToInt().toFloat()) },
        )
        SettingsRowDivider()
        SettingsSwitchRow(
            title = stringResource(Res.string.settings_voice_duck_music),
            hint = stringResource(Res.string.settings_voice_duck_music_hint),
            checked = state.voiceDuckMusic,
            onChange = component::onDuckMusicWhileSpeakingChanged,
        )
        SettingsRowDivider()
        SettingsChoiceRow(
            title = stringResource(Res.string.settings_voice_output),
            options = VoiceOutputPreference.entries.map { preference ->
                preference to stringResource(
                    when (preference) {
                        VoiceOutputPreference.AUTO -> Res.string.settings_voice_output_auto
                        VoiceOutputPreference.HEADSET -> Res.string.settings_voice_output_headset
                        VoiceOutputPreference.SPEAKER -> Res.string.settings_voice_output_speaker
                    }
                )
            },
            selected = state.voiceOutputPreference,
            enabled = state.voiceOutputPreference != null,
            onSelect = component::onVoiceOutputPreferenceChanged,
        )
    }
}

@Composable
private fun MicrophonePage(state: SettingsComponent.State, component: SettingsComponent) {
    // No group label: the row title below is the same words, and a label above it said them twice.
    SettingsGroup(null) {
        // Phone vs headset is a latency trade on Bluetooth: the phone mic forces A2DP playback.
        SettingsChoiceRow(
            title = stringResource(Res.string.settings_voice_microphone),
            hint = if (state.voiceOutputPreference != null) {
                stringResource(when (state.voiceMicrophoneSource) {
                    VoiceMicrophoneSource.AUTO -> Res.string.settings_voice_microphone_auto_hint
                    VoiceMicrophoneSource.PHONE -> Res.string.settings_voice_microphone_phone_hint
                    VoiceMicrophoneSource.HEADSET -> Res.string.settings_voice_microphone_headset_hint
                })
            } else {
                stringResource(Res.string.settings_voice_microphone_subtitle)
            },
            options = listOf(
                VoiceMicrophoneSource.AUTO to stringResource(Res.string.settings_voice_microphone_auto),
                VoiceMicrophoneSource.PHONE to stringResource(Res.string.settings_voice_microphone_phone),
                VoiceMicrophoneSource.HEADSET to stringResource(Res.string.settings_voice_microphone_headset),
            ),
            // The initial AUTO is not loaded evidence: nothing is selected until the stored choice arrives.
            selected = if (state.voiceOutputPreference != null) state.voiceMicrophoneSource else null,
            enabled = state.voiceOutputPreference != null,
            onSelect = component::onVoiceMicrophoneSourceChanged,
        )
    }
    SettingsGroup(stringResource(Res.string.settings_mic_section)) {
        SettingsSwitchRow(
            title = stringResource(Res.string.settings_mic_auto_gain),
            hint = stringResource(Res.string.settings_mic_auto_gain_hint),
            checked = state.mic.autoGain,
            onChange = component::onMicAutoGainChanged,
        )
        SettingsRowDivider()
        SettingsSliderRow(
            title = stringResource(Res.string.settings_mic_sensitivity),
            valueText = stringResource(Res.string.settings_mic_db, state.mic.sensitivityDb.roundToInt()),
            value = state.mic.sensitivityDb.coerceIn(0f, MAX_MIC_SENSITIVITY_DB),
            range = 0f..MAX_MIC_SENSITIVITY_DB,
            steps = MAX_MIC_SENSITIVITY_DB.roundToInt() - 1,
            onChange = { component.onMicSensitivityChanged(it.roundToInt().toFloat()) },
        )
        SettingsRowDivider()
        SettingsSliderRow(
            title = stringResource(Res.string.settings_mic_ceiling),
            valueText = stringResource(Res.string.settings_mic_dbfs, state.mic.limiterCeilingDbfs.roundToInt()),
            value = state.mic.limiterCeilingDbfs.coerceIn(SETTINGS_MIC_CEILING_MIN_DBFS, SETTINGS_MIC_CEILING_MAX_DBFS),
            range = SETTINGS_MIC_CEILING_MIN_DBFS..SETTINGS_MIC_CEILING_MAX_DBFS,
            steps = (SETTINGS_MIC_CEILING_MAX_DBFS - SETTINGS_MIC_CEILING_MIN_DBFS).roundToInt() - 1,
            hint = stringResource(Res.string.settings_mic_ceiling_hint),
            onChange = { component.onMicLimiterCeilingChanged(it.roundToInt().toFloat()) },
        )
        SettingsRowDivider()
        SettingsSwitchRow(
            title = stringResource(Res.string.settings_mic_phone_ns),
            hint = stringResource(Res.string.settings_mic_phone_ns_hint),
            checked = state.mic.phoneNoiseSuppressor,
            onChange = component::onMicPhoneNoiseSuppressorChanged,
        )
        SettingsRowDivider()
        SettingsSwitchRow(
            title = stringResource(Res.string.settings_mic_speech_gate),
            hint = stringResource(Res.string.settings_mic_speech_gate_hint),
            checked = state.mic.speechGate,
            onChange = component::onMicSpeechGateChanged,
        )
        if (state.mic.speechGate) {
            SettingsRowDivider()
            SettingsSliderRow(
                title = stringResource(Res.string.settings_mic_speech_gate_threshold),
                valueText = "${(state.mic.speechGateThreshold * 100).roundToInt()}%",
                value = state.mic.speechGateThreshold.coerceIn(MIN_SPEECH_GATE_THRESHOLD, MAX_SPEECH_GATE_THRESHOLD),
                range = MIN_SPEECH_GATE_THRESHOLD..MAX_SPEECH_GATE_THRESHOLD,
                steps = 15,
                hint = stringResource(Res.string.settings_mic_speech_gate_threshold_hint),
                onChange = { component.onMicSpeechGateThresholdChanged((it * 20).roundToInt() / 20f) },
            )
        }
    }
}

@Composable
private fun OfflineMapsPage(
    state: SettingsComponent.State,
    component: SettingsComponent,
    query: String,
    onQueryChange: (String) -> Unit,
    onDeleteRequest: (OfflineRegionPackageState) -> Unit,
) {
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
    (state.offlineCoverage as? OfflineCurrentCoverage.NotAvailable)
        // Not while that very cell is already being prepared or downloaded.
        ?.takeIf { coverage ->
            state.offlineRegions.firstOrNull { it.region.regionId == coverage.regionId }?.status.let {
                it == null || it == OfflineRegionPackageStatus.NOT_INSTALLED || it == OfflineRegionPackageStatus.FAILED
            }
        }
        ?.let { coverage ->
        // Information first, action second: nothing was downloaded because of this.
        Column(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(stringResource(Res.string.settings_offline_coverage_unavailable), fontSize = 12.sp)
            TextButton(onClick = { component.onDownloadOfflineRegion(coverage.regionId) }) {
                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.size(6.dp))
                Text(stringResource(Res.string.settings_offline_coverage_download))
            }
        }
    }
    state.offlineStorage?.let { usage ->
        Text(
            stringResource(
                Res.string.settings_offline_storage_line,
                formatOfflineBytes(usage.totalBytes),
                formatOfflineBytes(usage.packagesBytes),
                formatOfflineBytes(usage.mapBytes),
                formatOfflineBytes(usage.downloadsBytes + usage.legacyBytes),
            ),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    SettingsGroup(null) {
        SettingsSwitchRow(
            title = stringResource(Res.string.settings_offline_mobile_data),
            hint = null,
            checked = state.offlineSkipMeteredConfirmation,
            onChange = component::onOfflineSkipMeteredConfirmationChanged,
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
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text(stringResource(Res.string.settings_offline_add_region)) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
    )
    val visibleOfflineRegions = OfflineRegionListPolicy.filterAndOrder(
        states = state.offlineRegions,
        query = query,
    )
    if (state.offlineRegions.isEmpty() && !state.offlineCatalogError) {
        Text(
            stringResource(Res.string.settings_offline_not_configured),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else if (visibleOfflineRegions.isEmpty()) {
        Text(
            stringResource(Res.string.settings_offline_no_region_match),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        visibleOfflineRegions.forEach { region ->
            OfflineRegionRow(
                region = region,
                preparation = state.offlinePreparation.firstOrNull { it.regionId == region.region.regionId },
                component = component,
                onDeleteRequest = { onDeleteRequest(region) },
            )
        }
    }
}

/** The former "Debug" tab plus the log export that used to sit under "Diagnostics". */
@Composable
private fun DeveloperPage(state: SettingsComponent.State, component: SettingsComponent) {
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
    DebugSettingsTab(state, component::onStartBleCapture, component::onStopBleCapture, component::onClearBleCapture, component::onShareBleCapture)
}

@Composable
private fun OfflineRegionRow(
    region: OfflineRegionPackageState,
    preparation: OfflineRegionPreparationState?,
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
        OfflineRegionPackageStatus.PREPARING,
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
            actionText = if (region.status == OfflineRegionPackageStatus.NOT_INSTALLED &&
                region.onDemand && release == null
            ) {
                stringResource(Res.string.settings_offline_prepare)
            } else {
                stringResource(Res.string.settings_offline_download)
            }
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
        Text(OfflineRegionDisplayNamePolicy.displayName(region.region), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(
            offlineRegionStatusText(region, version),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        preparation?.let { progress ->
            val mapStatus = when (val map = progress.map) {
                OfflineMapPackState.Missing -> null
                OfflineMapPackState.Preparing -> stringResource(Res.string.settings_offline_status_preparing)
                is OfflineMapPackState.Downloading -> stringResource(Res.string.settings_offline_status_downloading, "${map.completedTiles} tiles", "")
                OfflineMapPackState.Ready -> stringResource(Res.string.settings_offline_status_ready, "map")
                is OfflineMapPackState.Failed -> stringResource(Res.string.settings_offline_status_failed)
            }
            if (mapStatus != null) Text(mapStatus, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
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
    OfflineRegionPackageStatus.PREPARING ->
        stringResource(Res.string.settings_offline_status_preparing)
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

@Composable
private fun formatOfflineBytes(bytes: Long): String {
    val size = OfflineByteFormat.size(bytes)
    val unit = stringResource(
        when (size.unit) {
            OfflineByteUnit.B -> Res.string.offline_unit_b
            OfflineByteUnit.KB -> Res.string.offline_unit_kb
            OfflineByteUnit.MB -> Res.string.offline_unit_mb
            OfflineByteUnit.GB -> Res.string.offline_unit_gb
        },
    )
    val number = if (size.unit == OfflineByteUnit.B) "%d".format(size.value.toLong()) else "%.1f".format(size.value)
    return "$number $unit"
}

@Composable
internal fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.72f))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

/** Opens the vehicle editor; deleting a vehicle lives there now ("Удалить ТС"), not on this row. */
@Composable
private fun VehicleRow(vehicle: Vehicle, onEdit: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            modifier = Modifier.size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center
        ) {
            Text(iconKeyToEmoji(vehicle.iconKey), color = MaterialTheme.colorScheme.onPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(vehicle.name, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            // Chemistry always renders; the source segment and its separator
            // drop out together when the vehicle has no nameable source.
            val source = vehicleSourceLabel(vehicle)
            val chemistry = chemistryLabel(vehicle.chemistry)
            Text(
                if (source != null) "$source  ·  $chemistry" else chemistry,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
