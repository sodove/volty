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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import ru.sodovaya.volty.domain.alert.AlarmModalities
import ru.sodovaya.volty.domain.model.DashboardStyle
import ru.sodovaya.volty.util.UnitSystem

class AppPrefs(private val store: DataStore<Preferences>) {

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
        .map { it[Keys.SCAN_TIMEOUT_SEC] ?: 5 }
        .stateIn(scope, SharingStarted.Eagerly, 5)

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
        .map { runCatching { DashboardStyle.valueOf(it[Keys.DASHBOARD_STYLE] ?: "CLEAN") }.getOrDefault(DashboardStyle.CLEAN) }
        .stateIn(scope, SharingStarted.Eagerly, DashboardStyle.CLEAN)

    /** How long a cleared controller/BMS fault remains readable on the ride dashboard. */
    val faultDisplayDurationSec: StateFlow<Int> = store.data
        .map { (it[Keys.FAULT_DISPLAY_DURATION_SEC] ?: DEFAULT_FAULT_DISPLAY_DURATION_SEC).coerceAtLeast(0) }
        .stateIn(scope, SharingStarted.Eagerly, DEFAULT_FAULT_DISPLAY_DURATION_SEC)

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
        combine(alarmEnabled, alarmToneEnabled, alarmVibrationEnabled) { master, tone, vibration ->
            AlarmModalities(alarmEnabled = master, toneEnabled = tone, vibrationEnabled = vibration)
        }.stateIn(scope, SharingStarted.Eagerly, AlarmModalities.DEFAULT)

    suspend fun setLastVehicleId(id: String?) = store.edit { p ->
        if (id == null) p.remove(Keys.LAST_VEHICLE_ID) else p[Keys.LAST_VEHICLE_ID] = id
    }
    suspend fun setThemeMode(mode: String) = store.edit { it[Keys.THEME_MODE] = mode }
    suspend fun setDynamicColorEnabled(enabled: Boolean) = store.edit { it[Keys.DYNAMIC_COLOR] = enabled }
    suspend fun setFirstLaunchDone() = store.edit { it[Keys.FIRST_LAUNCH_DONE] = true }
    suspend fun setScanTimeoutSec(sec: Int) = store.edit { it[Keys.SCAN_TIMEOUT_SEC] = sec }
    suspend fun setAutoConnectCountdownSec(sec: Int) = store.edit { it[Keys.AUTO_CONNECT_COUNTDOWN_SEC] = sec }
    suspend fun setGuestModeShowSaved(show: Boolean) = store.edit { it[Keys.GUEST_MODE_SHOW_SAVED] = show }
    suspend fun setUnitSystem(system: UnitSystem) = store.edit { it[Keys.UNIT_SYSTEM] = system.name }
    suspend fun setDefaultDashboardStyle(style: DashboardStyle) = store.edit { it[Keys.DASHBOARD_STYLE] = style.name }
    suspend fun setFaultDisplayDurationSec(seconds: Int) = store.edit {
        it[Keys.FAULT_DISPLAY_DURATION_SEC] = seconds.coerceAtLeast(0)
    }
    suspend fun setAlarmEnabled(enabled: Boolean) = store.edit { it[Keys.ALARM_ENABLED] = enabled }
    suspend fun setAlarmToneEnabled(enabled: Boolean) = store.edit { it[Keys.ALARM_TONE_ENABLED] = enabled }
    suspend fun setAlarmVibrationEnabled(enabled: Boolean) = store.edit { it[Keys.ALARM_VIBRATION_ENABLED] = enabled }

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
        val ALARM_ENABLED = booleanPreferencesKey("alarm_enabled")
        val ALARM_TONE_ENABLED = booleanPreferencesKey("alarm_tone_enabled")
        val ALARM_VIBRATION_ENABLED = booleanPreferencesKey("alarm_vibration_enabled")
    }
}

private const val DEFAULT_FAULT_DISPLAY_DURATION_SEC = 60
