package ru.sodovaya.volty.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import ru.sodovaya.volty.domain.model.ThemeAccent
import kotlin.test.Test
import kotlin.test.assertEquals

class AppPrefsThemeTest {
    @Test
    fun accentDefaultsToDefault() = runTest {
        val fixture = prefs()

        assertEquals(ThemeAccent.DEFAULT, fixture.appPrefs.themeAccent.first())
    }

    @Test
    fun accentSelectionRoundTripsThroughPreferences() = runTest {
        val fixture = prefs()

        fixture.appPrefs.setThemeAccent(ThemeAccent.PINK)

        assertEquals(ThemeAccent.PINK, fixture.appPrefs.themeAccent.first { it == ThemeAccent.PINK })
        assertEquals("pink", fixture.store.data.first()[stringPreferencesKey("theme_accent")])
    }

    @Test
    fun unknownStoredAccentFallsBackToDefault() = runTest {
        val fixture = prefs(mutablePreferencesOf(stringPreferencesKey("theme_accent") to "orange"))
        fixture.appPrefs.themeAccent.first { it == ThemeAccent.ORANGE }

        fixture.store.edit { it[stringPreferencesKey("theme_accent")] = "future-accent" }

        assertEquals(ThemeAccent.DEFAULT, fixture.appPrefs.themeAccent.first { it == ThemeAccent.DEFAULT })
    }

    @Test
    fun accentSelectionIsIndependentOfAdaptiveColorToggle() = runTest {
        val fixture = prefs()
        fixture.appPrefs.setThemeAccent(ThemeAccent.TEAL)
        fixture.appPrefs.themeAccent.first { it == ThemeAccent.TEAL }

        fixture.appPrefs.setDynamicColorEnabled(false)
        assertEquals(false, fixture.appPrefs.dynamicColorEnabled.first { !it })
        assertEquals(ThemeAccent.TEAL, fixture.appPrefs.themeAccent.value)

        fixture.appPrefs.setDynamicColorEnabled(true)
        assertEquals(true, fixture.appPrefs.dynamicColorEnabled.first { it })
        assertEquals(ThemeAccent.TEAL, fixture.appPrefs.themeAccent.value)
    }

    @Test
    fun accentIdsAndSeedColorsAreStable() {
        assertEquals(
            listOf("default", "orange", "green", "teal", "blue", "pink"),
            ThemeAccent.entries.map(ThemeAccent::id),
        )
        assertEquals(
            listOf(
                0xFF5B53D4.toInt(),
                0xFFC45C20.toInt(),
                0xFF367C44.toInt(),
                0xFF007F82.toInt(),
                0xFF2165C0.toInt(),
                0xFFAA3F77.toInt(),
            ),
            ThemeAccent.entries.map(ThemeAccent::seedArgb),
        )
        assertEquals(ThemeAccent.DEFAULT, ThemeAccent.fromId("future-accent"))
    }

    private fun prefs(initial: Preferences = mutablePreferencesOf()): Fixture {
        val current = MutableStateFlow(initial)
        val store = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = current
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                transform(current.value).also { current.value = it }
        }
        return Fixture(AppPrefs(store), store)
    }

    private data class Fixture(
        val appPrefs: AppPrefs,
        val store: DataStore<Preferences>,
    )
}
