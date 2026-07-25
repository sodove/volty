package ru.sodovaya.volty.presentation.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import ru.sodovaya.volty.data.prefs.AppPrefs
import ru.sodovaya.volty.util.UnitSystem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * No SettingsComponent test harness exists yet to copy: constructing
 * DefaultSettingsComponent needs a real [ru.sodovaya.volty.diagnostics.LogExporter],
 * an `expect class` whose Android `actual` takes a `Context` that commonTest can't
 * supply. Per the task brief's documented fallback, this exercises [AppPrefs]
 * directly instead — it's exactly what `SettingsComponent.onUnitSystemChanged` /
 * `.unitSystem` plumb straight through to, so the coverage is equivalent.
 *
 * [FakePreferencesDataStore] and the `.first { it == expected }` wait are the same
 * pattern RideDashboardComponentTest's `appPrefsWith()` helper uses: AppPrefs's own
 * StateFlows are `stateIn(scope, SharingStarted.Eagerly, ...)` over a real
 * `Dispatchers.Default` scope, not the test's virtual scheduler — so after a write,
 * a plain `.value` read (or a naive first `awaitItem()`) can race the eager
 * collector. Suspending on the condition itself sidesteps that race.
 */
class SettingsUnitsTest {

    private class FakePreferencesDataStore(initial: Preferences) : DataStore<Preferences> {
        private val state = MutableStateFlow(initial)
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            val next = transform(state.value)
            state.value = next
            return next
        }
    }

    @Test
    fun choosing_imperial_persists_and_shows_in_state() = runTest {
        val prefs = AppPrefs(FakePreferencesDataStore(mutablePreferencesOf()))
        assertEquals(UnitSystem.METRIC, prefs.unitSystem.value)

        prefs.setUnitSystem(UnitSystem.IMPERIAL)

        assertEquals(UnitSystem.IMPERIAL, prefs.unitSystem.first { it == UnitSystem.IMPERIAL })
    }
}
