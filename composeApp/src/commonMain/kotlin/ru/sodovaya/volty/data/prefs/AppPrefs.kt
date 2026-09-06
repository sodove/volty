package ru.sodovaya.volty.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import ru.sodovaya.volty.domain.alert.AlarmMusicMode
import ru.sodovaya.volty.domain.alert.AlarmModalities
import ru.sodovaya.volty.domain.navigation.routing.NavigationPreferencesStore
import ru.sodovaya.volty.domain.navigation.routing.RouteStyle
import ru.sodovaya.volty.domain.model.DashboardStyle
import ru.sodovaya.volty.domain.social.VoiceMicrophoneSource
import ru.sodovaya.volty.util.UnitSystem

class AppPrefs(private val store: DataStore<Preferences>) : NavigationPreferencesStore {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    val lastVehicleId: StateFlow<String?> = store.data
        .map { it[Keys.LAST_VEHICLE_ID] }
        .stateIn(scope, SharingStarted.Eagerly, null)

    val themeMode: StateFlow<String> = store.data
        .map { it[Keys.THEME_MODE] ?: "system" }
        .stateIn(scope, SharingStarted.Eagerly, "system")

    val dynamicColorEnabled: StateFlow<Boolean> = store.data
        .map { it[Keys.DYNAMIC_COLOR] ?: true }
        .stateIn(scope, SharingStarted.Eagerly, true)

    val firstLaunchDone: StateFlow<Boolean> = store.data
        .map { it[Keys.FIRST_LAUNCH_DONE] ?: false }
        .stateIn(scope, SharingStarted.Eagerly, false)

    val scanTimeoutSec: StateFlow<Int> = store.data
        .map { (it[Keys.SCAN_TIMEOUT_SEC] ?: 3).coerceIn(1, 15) }
        .stateIn(scope, SharingStarted.Eagerly, 3)

    val autoConnectCountdownSec: StateFlow<Int> = store.data
        .map { it[Keys.AUTO_CONNECT_COUNTDOWN_SEC] ?: 3 }
        .stateIn(scope, SharingStarted.Eagerly, 3)

    val guestModeShowSaved: StateFlow<Boolean> = store.data
        .map { it[Keys.GUEST_MODE_SHOW_SAVED] ?: true }
        .stateIn(scope, SharingStarted.Eagerly, true)

    val unitSystem: StateFlow<UnitSystem> = store.data
        .map { runCatching { UnitSystem.valueOf(it[Keys.UNIT_SYSTEM] ?: "METRIC") }.getOrDefault(UnitSystem.METRIC) }
        .stateIn(scope, SharingStarted.Eagerly, UnitSystem.METRIC)

    val defaultDashboardStyle: StateFlow<DashboardStyle> = store.data
        .map { DashboardStyle.fromPersistedName(it[Keys.DASHBOARD_STYLE]) ?: DashboardStyle.LIGHT }
        .stateIn(scope, SharingStarted.Eagerly, DashboardStyle.LIGHT)

    /** How long a cleared controller/BMS fault remains readable on the ride dashboard. */
    val faultDisplayDurationSec: StateFlow<Int> = store.data
        .map { (it[Keys.FAULT_DISPLAY_DURATION_SEC] ?: DEFAULT_FAULT_DISPLAY_DURATION_SEC).coerceAtLeast(0) }
        .stateIn(scope, SharingStarted.Eagerly, DEFAULT_FAULT_DISPLAY_DURATION_SEC)

    val voiceMicrophoneSource: StateFlow<VoiceMicrophoneSource> = store.data
        .map { VoiceMicrophoneSource.fromPersisted(it[Keys.VOICE_MICROPHONE_SOURCE]) }
        .stateIn(scope, SharingStarted.Eagerly, VoiceMicrophoneSource.AUTO)

    /** Allow automatic offline-region downloads over mobile data without a prompt. */
    val offlineSkipMeteredConfirmation: StateFlow<Boolean> = store.data
        .map { it[Keys.OFFLINE_SKIP_METERED_CONFIRMATION] ?: false }
        .stateIn(scope, SharingStarted.Eagerly, false)

    override fun routeStyleFor(vehicleId: String?): Flow<RouteStyle> = store.data
        .map { preferences ->
            persistedRouteStyle(
                preferences[routeStyleKey(vehicleId)]
                    ?: preferences[Keys.NAVIGATION_ROUTE_STYLE],
            )
        }

    override fun topSpeedKphFor(vehicleId: String?): Flow<Int> = store.data
        .map { preferences ->
            (preferences[topSpeedKey(vehicleId)]
                ?: preferences[Keys.NAVIGATION_TOP_SPEED_KPH]
                ?: DEFAULT_NAVIGATION_TOP_SPEED_KPH)
                .coerceIn(MIN_NAVIGATION_TOP_SPEED_KPH, MAX_NAVIGATION_TOP_SPEED_KPH)
        }

    /** Compatibility/default view for callers that do not have a vehicle yet. */
    val routeStyle: StateFlow<RouteStyle> = routeStyleFor(null)
        .stateIn(scope, SharingStarted.Eagerly, RouteStyle.FAST_WITH_HIGHWAYS)

    /** Compatibility/default view for callers that do not have a vehicle yet. */
    val topSpeedKph: StateFlow<Int> = topSpeedKphFor(null)
        .stateIn(scope, SharingStarted.Eagerly, DEFAULT_NAVIGATION_TOP_SPEED_KPH)

    // --- The audible alarm's three switches (F §4). All default **true**: the
    // alarm is the feature a rider depends on when they are not looking at the
    // screen, so a fresh install must warn them without being configured first.
    // Every one of them is a "turn it off" decision the rider makes deliberately.

    /** Master switch. Off means neither tone nor vibration, whatever the other two say. */
    val alarmEnabled: StateFlow<Boolean> = store.data
        .map { it[Keys.ALARM_ENABLED] ?: true }
        .stateIn(scope, SharingStarted.Eagerly, true)

    val alarmToneEnabled: StateFlow<Boolean> = store.data
        .map { it[Keys.ALARM_TONE_ENABLED] ?: true }
        .stateIn(scope, SharingStarted.Eagerly, true)

    val alarmVibrationEnabled: StateFlow<Boolean> = store.data
        .map { it[Keys.ALARM_VIBRATION_ENABLED] ?: true }
        .stateIn(scope, SharingStarted.Eagerly, true)

    /** Whether an alarm ducks media or plays over it without requesting focus. */
    val alarmMusicMode: StateFlow<AlarmMusicMode> = store.data
        .map {
            runCatching {
                AlarmMusicMode.valueOf(it[Keys.ALARM_MUSIC_MODE] ?: AlarmMusicMode.DUCK_MEDIA.name)
            }.getOrDefault(AlarmMusicMode.DUCK_MEDIA)
        }
        .stateIn(scope, SharingStarted.Eagerly, AlarmMusicMode.DUCK_MEDIA)

    /**
     * The three switches as the one value `AudibleAlarm.setModalities` takes.
     *
     * Combined here rather than at the call site so the service wires up one
     * collector instead of three, and so it cannot receive a half-applied
     * combination (tone already off, master not yet). The initial value is
     * [AlarmModalities.DEFAULT] — everything on — which is also what the flows
     * above report before DataStore has read from disk, so the alarm is never
     * briefly muted at start-up by prefs that simply have not loaded yet.
     */
    val alarmModalities: StateFlow<AlarmModalities> =
        combine(alarmEnabled, alarmToneEnabled, alarmVibrationEnabled, alarmMusicMode) { master, tone, vibration, musicMode ->
            AlarmModalities(
                alarmEnabled = master,
                toneEnabled = tone,
                vibrationEnabled = vibration,
                musicMode = musicMode
            )
        }.stateIn(scope, SharingStarted.Eagerly, AlarmModalities.DEFAULT)

    suspend fun setLastVehicleId(id: String?) = store.edit { p ->
        if (id == null) p.remove(Keys.LAST_VEHICLE_ID) else p[Keys.LAST_VEHICLE_ID] = id
    }
    suspend fun setThemeMode(mode: String) = store.edit { it[Keys.THEME_MODE] = mode }
    suspend fun setDynamicColorEnabled(enabled: Boolean) = store.edit { it[Keys.DYNAMIC_COLOR] = enabled }
    suspend fun setFirstLaunchDone() = store.edit { it[Keys.FIRST_LAUNCH_DONE] = true }
    suspend fun setScanTimeoutSec(sec: Int) = store.edit { it[Keys.SCAN_TIMEOUT_SEC] = sec.coerceIn(1, 15) }
    suspend fun setAutoConnectCountdownSec(sec: Int) = store.edit { it[Keys.AUTO_CONNECT_COUNTDOWN_SEC] = sec }
    suspend fun setGuestModeShowSaved(show: Boolean) = store.edit { it[Keys.GUEST_MODE_SHOW_SAVED] = show }
    suspend fun setUnitSystem(system: UnitSystem) = store.edit { it[Keys.UNIT_SYSTEM] = system.name }
    suspend fun setDefaultDashboardStyle(style: DashboardStyle) = store.edit { it[Keys.DASHBOARD_STYLE] = style.name }
    suspend fun setFaultDisplayDurationSec(seconds: Int) = store.edit {
        it[Keys.FAULT_DISPLAY_DURATION_SEC] = seconds.coerceAtLeast(0)
    }
    suspend fun setVoiceMicrophoneSource(source: VoiceMicrophoneSource) = store.edit {
        it[Keys.VOICE_MICROPHONE_SOURCE] = source.name
    }
    suspend fun setOfflineSkipMeteredConfirmation(skip: Boolean) = store.edit {
        it[Keys.OFFLINE_SKIP_METERED_CONFIRMATION] = skip
    }
    override suspend fun setRouteStyle(vehicleId: String?, style: RouteStyle) {
        store.edit { it[routeStyleKey(vehicleId)] = style.name }
    }
    override suspend fun setTopSpeedKph(vehicleId: String?, speedKph: Int) {
        store.edit {
            it[topSpeedKey(vehicleId)] = speedKph.coerceIn(
                MIN_NAVIGATION_TOP_SPEED_KPH,
                MAX_NAVIGATION_TOP_SPEED_KPH,
            )
        }
    }

    suspend fun setRouteStyle(style: RouteStyle) = setRouteStyle(null, style)
    suspend fun setTopSpeedKph(speedKph: Int) = setTopSpeedKph(null, speedKph)

    private fun routeStyleKey(vehicleId: String?) = if (vehicleId.isNullOrBlank()) {
        Keys.NAVIGATION_ROUTE_STYLE
    } else {
        stringPreferencesKey("navigation_route_style_vehicle_$vehicleId")
    }

    private fun topSpeedKey(vehicleId: String?) = if (vehicleId.isNullOrBlank()) {
        Keys.NAVIGATION_TOP_SPEED_KPH
    } else {
        intPreferencesKey("navigation_top_speed_kph_vehicle_$vehicleId")
    }
    suspend fun setAlarmEnabled(enabled: Boolean) = store.edit { it[Keys.ALARM_ENABLED] = enabled }
    suspend fun setAlarmToneEnabled(enabled: Boolean) = store.edit { it[Keys.ALARM_TONE_ENABLED] = enabled }
    suspend fun setAlarmVibrationEnabled(enabled: Boolean) = store.edit { it[Keys.ALARM_VIBRATION_ENABLED] = enabled }
    suspend fun setAlarmMusicMode(mode: AlarmMusicMode) = store.edit { it[Keys.ALARM_MUSIC_MODE] = mode.name }

    private object Keys {
        val LAST_VEHICLE_ID = stringPreferencesKey("last_vehicle_id")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color_enabled")
        val FIRST_LAUNCH_DONE = booleanPreferencesKey("first_launch_done")
        val SCAN_TIMEOUT_SEC = intPreferencesKey("scan_timeout_sec")
        val AUTO_CONNECT_COUNTDOWN_SEC = intPreferencesKey("auto_connect_countdown_sec")
        val GUEST_MODE_SHOW_SAVED = booleanPreferencesKey("guest_mode_show_saved")
        val UNIT_SYSTEM = stringPreferencesKey("unit_system")
        val DASHBOARD_STYLE = stringPreferencesKey("dashboard_style")
        val FAULT_DISPLAY_DURATION_SEC = intPreferencesKey("fault_display_duration_sec")
        val VOICE_MICROPHONE_SOURCE = stringPreferencesKey("voice_microphone_source")
        val OFFLINE_SKIP_METERED_CONFIRMATION =
            booleanPreferencesKey("offline_skip_metered_confirmation")
        val NAVIGATION_ROUTE_STYLE = stringPreferencesKey("navigation_route_style")
        val NAVIGATION_TOP_SPEED_KPH = intPreferencesKey("navigation_top_speed_kph")
        val ALARM_ENABLED = booleanPreferencesKey("alarm_enabled")
        val ALARM_TONE_ENABLED = booleanPreferencesKey("alarm_tone_enabled")
        val ALARM_VIBRATION_ENABLED = booleanPreferencesKey("alarm_vibration_enabled")
        val ALARM_MUSIC_MODE = stringPreferencesKey("alarm_music_mode")
    }
}

private const val DEFAULT_FAULT_DISPLAY_DURATION_SEC = 60
private const val DEFAULT_NAVIGATION_TOP_SPEED_KPH = 50
private const val MIN_NAVIGATION_TOP_SPEED_KPH = 20
private const val MAX_NAVIGATION_TOP_SPEED_KPH = 130

private fun persistedRouteStyle(value: String?): RouteStyle =
    value?.let { runCatching { RouteStyle.valueOf(it) }.getOrNull() }
        ?: RouteStyle.FAST_WITH_HIGHWAYS
