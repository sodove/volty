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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
    }
}
