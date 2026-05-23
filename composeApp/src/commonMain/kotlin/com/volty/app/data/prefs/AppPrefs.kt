package com.volty.app.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class AppPrefs(private val store: DataStore<Preferences>) {

    val lastVehicleId: Flow<String?> = store.data.map { it[Keys.LAST_VEHICLE_ID] }
    val themeMode: Flow<String> = store.data.map { it[Keys.THEME_MODE] ?: "system" }
    val dynamicColorEnabled: Flow<Boolean> = store.data.map { it[Keys.DYNAMIC_COLOR] ?: true }
    val firstLaunchDone: Flow<Boolean> = store.data.map { it[Keys.FIRST_LAUNCH_DONE] ?: false }
    val scanTimeoutSec: Flow<Int> = store.data.map { it[Keys.SCAN_TIMEOUT_SEC] ?: 5 }
    val autoConnectCountdownSec: Flow<Int> = store.data.map { it[Keys.AUTO_CONNECT_COUNTDOWN_SEC] ?: 3 }
    val guestModeShowSaved: Flow<Boolean> = store.data.map { it[Keys.GUEST_MODE_SHOW_SAVED] ?: true }

    suspend fun setLastVehicleId(id: String?) = store.edit { p ->
        if (id == null) p.remove(Keys.LAST_VEHICLE_ID) else p[Keys.LAST_VEHICLE_ID] = id
    }
    suspend fun setThemeMode(mode: String) = store.edit { it[Keys.THEME_MODE] = mode }
    suspend fun setDynamicColorEnabled(enabled: Boolean) = store.edit { it[Keys.DYNAMIC_COLOR] = enabled }
    suspend fun setFirstLaunchDone() = store.edit { it[Keys.FIRST_LAUNCH_DONE] = true }
    suspend fun setScanTimeoutSec(sec: Int) = store.edit { it[Keys.SCAN_TIMEOUT_SEC] = sec }
    suspend fun setAutoConnectCountdownSec(sec: Int) = store.edit { it[Keys.AUTO_CONNECT_COUNTDOWN_SEC] = sec }
    suspend fun setGuestModeShowSaved(show: Boolean) = store.edit { it[Keys.GUEST_MODE_SHOW_SAVED] = show }

    private object Keys {
        val LAST_VEHICLE_ID = stringPreferencesKey("last_vehicle_id")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color_enabled")
        val FIRST_LAUNCH_DONE = booleanPreferencesKey("first_launch_done")
        val SCAN_TIMEOUT_SEC = intPreferencesKey("scan_timeout_sec")
        val AUTO_CONNECT_COUNTDOWN_SEC = intPreferencesKey("auto_connect_countdown_sec")
        val GUEST_MODE_SHOW_SAVED = booleanPreferencesKey("guest_mode_show_saved")
    }
}
