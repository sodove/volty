package ru.sodovaya.volty.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class AppPrefsBannerLingerTest {
    private class Store(initial: Preferences) : DataStore<Preferences> {
        private val state = MutableStateFlow(initial)
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it }
    }

    private val faultKey = intPreferencesKey("fault_display_duration_sec")

    @Test
    fun an_old_install_keeps_its_faults_value_and_gets_the_alert_defaults() = runTest {
        // Only the original single linger key is stored, at a non-default value.
        val prefs = AppPrefs(Store(mutablePreferencesOf(faultKey to 45)))
        assertEquals(45, prefs.faultDisplayDurationSec.first { it == 45 })
        assertEquals(30, prefs.criticalAlertDisplayDurationSec.first())
        assertEquals(10, prefs.warningAlertDisplayDurationSec.first())
    }

    @Test
    fun the_three_lingers_are_stored_independently() = runTest {
        val prefs = AppPrefs(Store(mutablePreferencesOf()))
        prefs.setFaultDisplayDurationSec(120)
        prefs.setCriticalAlertDisplayDurationSec(5)
        prefs.setWarningAlertDisplayDurationSec(0)
        assertEquals(120, prefs.faultDisplayDurationSec.first { it == 120 })
        assertEquals(5, prefs.criticalAlertDisplayDurationSec.first { it == 5 })
        assertEquals(0, prefs.warningAlertDisplayDurationSec.first { it == 0 })
    }
}
