package ru.sodovaya.volty.data.voice

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.*
import ru.sodovaya.volty.data.prefs.AppPrefs
import ru.sodovaya.volty.domain.social.VoiceMicrophoneSource
import ru.sodovaya.volty.domain.voice.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceAudioPreferencesReadTest {
    private val input = stringPreferencesKey("voice_microphone_source")
    private val output = stringPreferencesKey("voice_output_preference")

    @Test fun panelPreferencesWaitForActualStorageAndNeverPublishInitialAutoAsLoaded()=runTest {
        val store=Store(mutablePreferencesOf(input to "PHONE",output to "HEADSET"),ready=false)
        val prefs=AppPrefs(store)
        val reader=async {prefs.voiceAudioPreferences.first()}
        try {
            runCurrent();assertFalse(reader.isCompleted)
            store.loaded.complete(Unit)
            assertEquals(VoiceAudioPreferences(VoiceInputPreference.PHONE,VoiceOutputPreference.HEADSET),reader.await())
        } finally {store.loaded.complete(Unit);reader.cancelAndJoin()}
    }

    @Test fun panelPreferencesObserveOneAtomicSnapshotAndSeparateInputOutputWrites()=runTest {
        val current=MutableStateFlow<Preferences>(mutablePreferencesOf(output to "HEADSET"))
        val store=object:DataStore<Preferences> {
            override val data:Flow<Preferences> = current
            override suspend fun updateData(transform:suspend (Preferences)->Preferences):Preferences = transform(current.value).also {current.value=it}
        }
        val prefs=AppPrefs(store);val samples=mutableListOf<VoiceAudioPreferences>()
        val reader=launch {prefs.voiceAudioPreferences.take(3).toList(samples)}
        try {
            runCurrent();assertEquals(listOf(VoiceAudioPreferences(VoiceInputPreference.AUTO,VoiceOutputPreference.HEADSET)),samples)
            prefs.setVoiceMicrophoneSource(VoiceMicrophoneSource.PHONE);runCurrent()
            prefs.setVoiceOutputPreference(VoiceOutputPreference.SPEAKER);runCurrent();reader.join()
            assertEquals(listOf(VoiceAudioPreferences(VoiceInputPreference.AUTO,VoiceOutputPreference.HEADSET),
                VoiceAudioPreferences(VoiceInputPreference.PHONE,VoiceOutputPreference.HEADSET),
                VoiceAudioPreferences(VoiceInputPreference.PHONE,VoiceOutputPreference.SPEAKER)),samples)
            assertEquals(samples.last(),prefs.readVoiceAudioPreferences())
        } finally {reader.cancelAndJoin()}
    }

    @Test fun panelMalformedPersistedPreferenceDoesNotEraseTheOtherValidChoice()=runTest {
        assertEquals(VoiceAudioPreferences(VoiceInputPreference.AUTO,VoiceOutputPreference.HEADSET),
            AppPrefs(Store(mutablePreferencesOf(input to "invalid",output to "HEADSET"))).voiceAudioPreferences.first())
        assertEquals(VoiceAudioPreferences(VoiceInputPreference.PHONE,VoiceOutputPreference.AUTO),
            AppPrefs(Store(mutablePreferencesOf(input to "PHONE",output to "invalid"))).voiceAudioPreferences.first())
    }

    @Test fun nativeSnapshotWaitsForStorageInsteadOfUsingInitialAuto() = runTest {
        val store = Store(mutablePreferencesOf(input to "PHONE", output to "HEADSET"), ready = false)
        val prefs = AppPrefs(store)
        val reader = async { prefs.readVoiceAudioPreferences() }
        try {
            runCurrent()
            assertFalse(reader.isCompleted)
            assertEquals(VoiceMicrophoneSource.AUTO, prefs.voiceMicrophoneSource.value)
            store.loaded.complete(Unit)
            assertEquals(VoiceAudioPreferences(VoiceInputPreference.PHONE, VoiceOutputPreference.HEADSET), reader.await())
        } finally { store.loaded.complete(Unit); reader.cancelAndJoin() }
    }

    @Test fun snapshotsReadActualWritesAndMicrophoneChangesKeepOutputPreference() = runTest {
        val store = Store(mutablePreferencesOf())
        val prefs = AppPrefs(store)
        prefs.setVoiceMicrophoneSource(VoiceMicrophoneSource.PHONE)
        prefs.setVoiceOutputPreference(VoiceOutputPreference.HEADSET)
        assertEquals(VoiceAudioPreferences(VoiceInputPreference.PHONE, VoiceOutputPreference.HEADSET), prefs.readVoiceAudioPreferences())
        prefs.setVoiceMicrophoneSource(VoiceMicrophoneSource.HEADSET)
        assertEquals(VoiceAudioPreferences(VoiceInputPreference.HEADSET, VoiceOutputPreference.HEADSET), prefs.readVoiceAudioPreferences())
        assertEquals("HEADSET", store.current[output])
        assertEquals(VoiceOutputPreference.HEADSET, AppPrefs(store).readVoiceAudioPreferences().output)
    }

    @Test fun unavailableStorageCannotFabricateAutoPreferences() = runTest {
        // The constructor captures finite empty flows for all eager UI readers.
        // Only the explicit later read sees a storage failure, without background exceptions.
        var throwOnRead = false
        val changing = object : DataStore<Preferences> {
            override val data: Flow<Preferences>
                get() = if (throwOnRead) flow { error("Storage unavailable") } else flow { emit(mutablePreferencesOf()) }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = error("Unexpected write")
        }
        val actual = AppPrefs(changing)
        throwOnRead = true
        assertFailsWith<IllegalStateException> { actual.readVoiceAudioPreferences() }
        throwOnRead = false
        assertEquals(VoiceAudioPreferences(), actual.readVoiceAudioPreferences())
    }

    private class Store(initial: Preferences, ready: Boolean = true) : DataStore<Preferences> {
        val loaded = CompletableDeferred<Unit>().also { if (ready) it.complete(Unit) }
        var current: Preferences = initial
            private set
        override val data: Flow<Preferences> = flow { loaded.await(); emit(current) }
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            val next = transform(current)
            current = next
            return next
        }
    }
}
