package ru.sodovaya.volty.domain.energy

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

@Serializable
enum class BegodeCurrentSign(val multiplier: Int) { RAW(1), INVERTED(-1) }

@Serializable
data class BegodeCurrentSettings(
    val manualSign: BegodeCurrentSign? = null,
    val learnedSign: BegodeCurrentSign? = null,
    val manualOffsetA: Float? = null,
    val learnedOffsetA: Float? = null,
    val learnedOffsetSign: BegodeCurrentSign? = null,
    val learnedBmsSign: BegodeCurrentSign? = null,
) {
    val confirmedSign: BegodeCurrentSign? get() = manualSign ?: learnedSign
    val sign: BegodeCurrentSign get() = confirmedSign ?: BegodeCurrentSign.RAW
    val automaticOffsetA: Float? get() = learnedOffsetA?.takeIf { learnedOffsetSign == sign }
    val offsetA: Float? get() = manualOffsetA ?: automaticOffsetA
    val bmsMultiplier: Int get() = learnedBmsSign?.multiplier ?: 1

    fun dischargeAmps(rawA: Float): Float = rawA * sign.multiplier + (offsetA ?: 0f)

    companion object {
        val DEFAULT = BegodeCurrentSettings()
    }
}

/** A wheel's calibration follows its BLE address across reconnects and vehicle edits. */
interface BegodeCurrentStore {
    val settings: Flow<Map<String, BegodeCurrentSettings>>
    suspend fun setManualSign(address: String, sign: BegodeCurrentSign?)
    suspend fun setManualOffset(address: String, amps: Float?)
    suspend fun saveEvidence(
        address: String,
        sign: BegodeCurrentSign?,
        offsetA: Float?,
        bmsSign: BegodeCurrentSign?,
        referenceSign: BegodeCurrentSign?,
    )
}

const val MAX_BEGODE_CURRENT_OFFSET_A = 20f
