package ru.sodovaya.volty.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import ru.sodovaya.volty.domain.energy.BegodeCurrentSettings
import ru.sodovaya.volty.domain.energy.BegodeCurrentSign
import ru.sodovaya.volty.domain.energy.BegodeCurrentStore
import ru.sodovaya.volty.domain.energy.MAX_BEGODE_CURRENT_OFFSET_A
import kotlin.math.abs

internal class DataStoreBegodeCurrentStore(private val store: DataStore<Preferences>) : BegodeCurrentStore {
    override val settings = store.data.map { decode(it[key]) }

    override suspend fun setManualSign(address: String, sign: BegodeCurrentSign?) = update(address) {
        it.copy(manualSign = sign)
    }

    override suspend fun setManualOffset(address: String, amps: Float?) {
        require(amps == null || (amps.isFinite() && abs(amps) <= MAX_BEGODE_CURRENT_OFFSET_A))
        update(address) { it.copy(manualOffsetA = amps) }
    }

    override suspend fun saveEvidence(
        address: String,
        sign: BegodeCurrentSign?,
        offsetA: Float?,
        bmsSign: BegodeCurrentSign?,
        referenceSign: BegodeCurrentSign?,
    ) = update(address) { stored ->
        val signed = if (stored.confirmedSign == null && sign != null) stored.copy(learnedSign = sign) else stored
        if (referenceSign == null || signed.confirmedSign != referenceSign) return@update signed
        val withOffset = if (offsetA != null && offsetA.isFinite() && abs(offsetA) <= MAX_BEGODE_CURRENT_OFFSET_A &&
            signed.offsetA == null
        ) signed.copy(learnedOffsetA = offsetA, learnedOffsetSign = referenceSign) else signed
        if (bmsSign != null && withOffset.learnedBmsSign == null) {
            withOffset.copy(learnedBmsSign = bmsSign)
        } else withOffset
    }

    private suspend fun update(address: String, change: (BegodeCurrentSettings) -> BegodeCurrentSettings) {
        store.edit { prefs ->
            val all = decode(prefs[key])
            val before = all[address] ?: BegodeCurrentSettings()
            val after = change(before)
            if (after != before) prefs[key] = json.encodeToString(all + (address to after))
        }
    }

    private fun decode(raw: String?): Map<String, BegodeCurrentSettings> =
        raw?.let { json.decodeFromString<Map<String, BegodeCurrentSettings>>(it) }.orEmpty()

    private companion object {
        val key = stringPreferencesKey("begode_current_calibration_v1")
        val json = Json { ignoreUnknownKeys = true }
    }
}
