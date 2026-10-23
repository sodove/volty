package ru.sodovaya.volty.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import ru.sodovaya.volty.domain.alert.AlarmMusicMode
import ru.sodovaya.volty.domain.alert.AlarmModalities
import ru.sodovaya.volty.domain.audio.AlertSoundKind
import ru.sodovaya.volty.domain.audio.AlertSoundSettings
import ru.sodovaya.volty.domain.audio.MAX_ALERT_GAIN_DB
import ru.sodovaya.volty.domain.audio.MAX_MIC_SENSITIVITY_DB
import ru.sodovaya.volty.domain.audio.MicProcessingSettings
import ru.sodovaya.volty.domain.audio.VoiceListenSettings
import ru.sodovaya.volty.domain.audio.clampListenGainDb
import ru.sodovaya.volty.domain.audio.decodeParticipantGains
import ru.sodovaya.volty.domain.audio.encodeParticipants
import ru.sodovaya.volty.domain.navigation.routing.NavigationPreferencesStore
import ru.sodovaya.volty.domain.navigation.routing.RouteStyle
import ru.sodovaya.volty.domain.model.DashboardStyle
import ru.sodovaya.volty.domain.social.VoiceMicrophoneSource
import ru.sodovaya.volty.domain.voice.VoiceAudioPreferences
import ru.sodovaya.volty.domain.voice.VoiceInputPreference
import ru.sodovaya.volty.domain.radio.RadioMode
import ru.sodovaya.volty.domain.voice.VoiceOutputPreference
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
    /** One stored pair, with no synthetic loaded value before DataStore emits. */
    val voiceAudioPreferences: Flow<VoiceAudioPreferences>
        get() = store.data.map(::decodeVoiceAudioPreferences)

    /** Offline radio: look for a radio of any of the rider's servers and join it while the app is open. Off by default (it keeps Wi-Fi scanning). */
    val radioAutoJoin: StateFlow<Boolean> = store.data
        .map { it[Keys.RADIO_AUTO_JOIN] ?: false }
        .stateIn(scope, SharingStarted.Eagerly, false)

    /** The connection mode this phone uses when it raises a radio. */
    val radioMode: StateFlow<RadioMode> = store.data
        .map { prefs -> RadioMode.entries.firstOrNull { it.name == prefs[Keys.RADIO_MODE] } ?: RadioMode.BALANCED }
        .stateIn(scope, SharingStarted.Eagerly, RadioMode.BALANCED)

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

    val alertSoundSettings: StateFlow<AlertSoundSettings> = store.data
        .map {
            AlertSoundSettings(
                kind = runCatching {
                    AlertSoundKind.valueOf(it[Keys.ALERT_SOUND_KIND] ?: AlertSoundKind.NAVIGATION.name)
                }.getOrDefault(AlertSoundKind.NAVIGATION),
                gainDb = (it[Keys.ALERT_GAIN_DB] ?: 0f).coerceIn(0f, MAX_ALERT_GAIN_DB),
            )
        }
        .stateIn(scope, SharingStarted.Eagerly, AlertSoundSettings())

    val voiceListenSettings: StateFlow<VoiceListenSettings> = store.data
        .map {
            VoiceListenSettings(
                duckMusicWhileSpeaking = it[Keys.VOICE_DUCK_MUSIC] ?: true,
                masterGainDb = clampListenGainDb(it[Keys.VOICE_MASTER_GAIN_DB] ?: 0f),
                participantGainDb = decodeParticipantGains(it[Keys.VOICE_PARTICIPANT_GAINS]),
            )
        }
        .stateIn(scope, SharingStarted.Eagerly, VoiceListenSettings())

    val micProcessingSettings: StateFlow<MicProcessingSettings> = store.data
        .map {
            MicProcessingSettings(
                autoGain = it[Keys.MIC_AUTO_GAIN] ?: true,
                sensitivityDb = (it[Keys.MIC_SENSITIVITY_DB] ?: 0f).coerceIn(0f, MAX_MIC_SENSITIVITY_DB),
                limiterCeilingDbfs = (it[Keys.MIC_LIMITER_CEILING_DBFS] ?: -3f).coerceIn(-12f, -1f),
            )
        }
        .stateIn(scope, SharingStarted.Eagerly, MicProcessingSettings())

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
    /** Native preparation waits for one actual snapshot; UI StateFlow's initial AUTO is not a read. */
    suspend fun readVoiceAudioPreferences(): VoiceAudioPreferences = voiceAudioPreferences.first()
    private fun decodeVoiceAudioPreferences(snapshot: Preferences): VoiceAudioPreferences {
        val input = when (VoiceMicrophoneSource.fromPersisted(snapshot[Keys.VOICE_MICROPHONE_SOURCE])) {
            VoiceMicrophoneSource.AUTO -> VoiceInputPreference.AUTO
            VoiceMicrophoneSource.PHONE -> VoiceInputPreference.PHONE
            VoiceMicrophoneSource.HEADSET -> VoiceInputPreference.HEADSET
        }
        val output = VoiceOutputPreference.entries.firstOrNull {
            it.name == snapshot[Keys.VOICE_OUTPUT_PREFERENCE]
        } ?: VoiceOutputPreference.AUTO
        return VoiceAudioPreferences(input, output)
    }
    suspend fun setVoiceOutputPreference(preference: VoiceOutputPreference) = store.edit {
        it[Keys.VOICE_OUTPUT_PREFERENCE] = preference.name
    }
    suspend fun setRadioAutoJoin(enabled: Boolean) = store.edit { it[Keys.RADIO_AUTO_JOIN] = enabled }
    suspend fun setRadioMode(mode: RadioMode) = store.edit { it[Keys.RADIO_MODE] = mode.name }
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

    suspend fun setAlertSoundKind(kind: AlertSoundKind) = store.edit { it[Keys.ALERT_SOUND_KIND] = kind.name }
    suspend fun setAlertGainDb(db: Float) = store.edit { it[Keys.ALERT_GAIN_DB] = db.coerceIn(0f, MAX_ALERT_GAIN_DB) }
    suspend fun setDuckMusicWhileSpeaking(on: Boolean) = store.edit { it[Keys.VOICE_DUCK_MUSIC] = on }
    suspend fun setMasterListenGainDb(db: Float) =
        store.edit { it[Keys.VOICE_MASTER_GAIN_DB] = clampListenGainDb(db) }

    /** Read-modify-write inside one `edit`, so concurrent participants never lose each other's gain. */
    suspend fun setParticipantGainDb(actorId: String, db: Float) {
        if (actorId.isBlank() || ';' in actorId || '=' in actorId) return
        store.edit {
            val current = decodeParticipantGains(it[Keys.VOICE_PARTICIPANT_GAINS])
            it[Keys.VOICE_PARTICIPANT_GAINS] =
                VoiceListenSettings(participantGainDb = current + (actorId to clampListenGainDb(db)))
                    .encodeParticipants()
        }
    }

    suspend fun setMicAutoGain(on: Boolean) = store.edit { it[Keys.MIC_AUTO_GAIN] = on }
    suspend fun setMicSensitivityDb(db: Float) =
        store.edit { it[Keys.MIC_SENSITIVITY_DB] = db.coerceIn(0f, MAX_MIC_SENSITIVITY_DB) }
    suspend fun setMicLimiterCeilingDbfs(dbfs: Float) =
        store.edit { it[Keys.MIC_LIMITER_CEILING_DBFS] = dbfs.coerceIn(-12f, -1f) }

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
        val VOICE_OUTPUT_PREFERENCE = stringPreferencesKey("voice_output_preference")
        val OFFLINE_SKIP_METERED_CONFIRMATION =
            booleanPreferencesKey("offline_skip_metered_confirmation")
        val RADIO_AUTO_JOIN = booleanPreferencesKey("radio_auto_join")
        val RADIO_MODE = stringPreferencesKey("radio_mode")
        val NAVIGATION_ROUTE_STYLE = stringPreferencesKey("navigation_route_style")
        val NAVIGATION_TOP_SPEED_KPH = intPreferencesKey("navigation_top_speed_kph")
        val ALARM_ENABLED = booleanPreferencesKey("alarm_enabled")
        val ALARM_TONE_ENABLED = booleanPreferencesKey("alarm_tone_enabled")
        val ALARM_VIBRATION_ENABLED = booleanPreferencesKey("alarm_vibration_enabled")
        val ALARM_MUSIC_MODE = stringPreferencesKey("alarm_music_mode")
        val ALERT_SOUND_KIND = stringPreferencesKey("alert_sound_kind")
        val ALERT_GAIN_DB = floatPreferencesKey("alert_gain_db")
        val VOICE_DUCK_MUSIC = booleanPreferencesKey("voice_duck_music")
        val VOICE_MASTER_GAIN_DB = floatPreferencesKey("voice_master_gain_db")
        val VOICE_PARTICIPANT_GAINS = stringPreferencesKey("voice_participant_gains")
        val MIC_AUTO_GAIN = booleanPreferencesKey("mic_auto_gain")
        val MIC_SENSITIVITY_DB = floatPreferencesKey("mic_sensitivity_db")
        val MIC_LIMITER_CEILING_DBFS = floatPreferencesKey("mic_limiter_ceiling_dbfs")
    }
}

private const val DEFAULT_FAULT_DISPLAY_DURATION_SEC = 60
private const val DEFAULT_NAVIGATION_TOP_SPEED_KPH = 50
private const val MIN_NAVIGATION_TOP_SPEED_KPH = 20
private const val MAX_NAVIGATION_TOP_SPEED_KPH = 130

private fun persistedRouteStyle(value: String?): RouteStyle =
    value?.let { runCatching { RouteStyle.valueOf(it) }.getOrNull() }
        ?: RouteStyle.FAST_WITH_HIGHWAYS
