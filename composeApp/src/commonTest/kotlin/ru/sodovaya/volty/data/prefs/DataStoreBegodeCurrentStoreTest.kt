package ru.sodovaya.volty.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import ru.sodovaya.volty.domain.energy.BegodeCurrentSign.INVERTED
import ru.sodovaya.volty.domain.energy.BegodeCurrentSign.RAW
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DataStoreBegodeCurrentStoreTest {
    @Test
    fun learnedAndManualValuesRoundTripAndLateEvidenceRespectsTheActiveSign() = runTest {
        val data = object : DataStore<Preferences> {
            override val data = MutableStateFlow(emptyPreferences())
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                transform(data.value).also { data.value = it }
        }
        val store = DataStoreBegodeCurrentStore(data)
        store.saveEvidence("wheel", INVERTED, -1.9f, RAW, INVERTED)
        store.setManualSign("wheel", RAW)
        store.saveEvidence("wheel", null, -7f, INVERTED, INVERTED)
        val reopened = DataStoreBegodeCurrentStore(data)
        val direct = assertNotNull(reopened.settings.first()["wheel"])
        assertEquals(RAW, direct.sign)
        assertNull(direct.offsetA)

        reopened.setManualOffset("wheel", 0f)
        reopened.setManualSign("wheel", null)
        val overridden = assertNotNull(store.settings.first()["wheel"])
        assertEquals(INVERTED, overridden.sign)
        assertEquals(0f, overridden.offsetA)

        reopened.setManualOffset("wheel", null)
        assertEquals(-1.9f, store.settings.first()["wheel"]?.offsetA)
        assertNull(store.settings.first()["other"])
    }
}
