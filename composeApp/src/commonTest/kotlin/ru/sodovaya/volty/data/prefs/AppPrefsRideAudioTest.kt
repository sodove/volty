package ru.sodovaya.volty.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import ru.sodovaya.volty.domain.audio.AlertSoundKind
import kotlin.test.Test
import kotlin.test.assertEquals

class AppPrefsRideAudioTest {
    private fun prefs(): AppPrefs {
        val current = MutableStateFlow<Preferences>(mutablePreferencesOf())
        val store = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = current
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                transform(current.value).also { current.value = it }
        }
        return AppPrefs(store)
    }

    @Test
    fun settersClampAndPersist() = runTest {
        val p = prefs()
        p.setAlertSoundKind(AlertSoundKind.ALARM)
        p.setAlertGainDb(-5f)
        p.setMasterListenGainDb(-99f)
        p.setMicSensitivityDb(50f)
        p.setMicLimiterCeilingDbfs(0f)
        p.setDuckMusicWhileSpeaking(false)
        p.setMicAutoGain(false)
        p.setParticipantGainDb("a", 99f)
        p.setParticipantGainDb("b", -4f)
        p.setParticipantGainDb("bad;id", 3f)

        val alert = p.alertSoundSettings.first { it.kind == AlertSoundKind.ALARM }
        assertEquals(0f, alert.gainDb)
        val listen = p.voiceListenSettings.first { it.participantGainDb.size == 2 }
        assertEquals(-30f, listen.masterGainDb)
        assertEquals(mapOf("a" to 12f, "b" to -4f), listen.participantGainDb)
        assertEquals(false, listen.duckMusicWhileSpeaking)
        val mic = p.micProcessingSettings.first { !it.autoGain }
        assertEquals(12f, mic.sensitivityDb)
        assertEquals(-1f, mic.limiterCeilingDbfs)
    }

    @Test
    fun theNoiseOptionsAreOffByDefaultAndTheThresholdIsClamped() = runTest {
        val p = prefs()
        val initial = p.micProcessingSettings.first()
        assertEquals(false, initial.phoneNoiseSuppressor, "the vendor suppressor varies by phone: opt-in")
        assertEquals(false, initial.speechGate, "the speech gate is opt-in")
        assertEquals(0.5f, initial.speechGateThreshold)
        p.setMicPhoneNoiseSuppressor(true); p.setMicSpeechGate(true); p.setMicSpeechGateThreshold(5f)
        val after = p.micProcessingSettings.first { it.speechGate }
        assertEquals(true, after.phoneNoiseSuppressor)
        assertEquals(0.9f, after.speechGateThreshold)
        p.setMicSpeechGateThreshold(-1f)
        assertEquals(0.1f, p.micProcessingSettings.first { it.speechGateThreshold < 0.5f }.speechGateThreshold)
    }
}
