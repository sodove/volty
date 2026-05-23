package com.volty.app.data.bms

import com.volty.app.domain.model.BmsType

/**
 * Heuristic detection of BMS type from BLE advertisement data.
 *
 * Name prefix is the primary signal. Service UUID is a secondary signal that
 * only works for BMS whose short service code is unique to that vendor.
 * JK and ANT share short code `ffe0` - when the name doesn't disambiguate,
 * detection returns null and the user must pick manually.
 */
object BmsTypeDetector {

    /** Short service codes that uniquely identify a single BMS type. */
    private val UNIQUE_SHORTS = mapOf(
        "ff00" to BmsType.JBD_BMS,
        "fff0" to BmsType.DALY_BMS
    )

    fun detect(name: String?, serviceUuids: List<String>): BmsType? {
        nameMatch(name)?.let { return it }
        for (uuid in serviceUuids) {
            val lower = uuid.lowercase()
            if (lower.length >= 8) {
                val short = lower.substring(4, 8)
                UNIQUE_SHORTS[short]?.let { return it }
            }
        }
        return null
    }

    private fun nameMatch(name: String?): BmsType? {
        if (name.isNullOrEmpty()) return null
        return when {
            name.startsWith("JK_", ignoreCase = true) || name.startsWith("JK-", ignoreCase = true) -> BmsType.JK_BMS
            name.startsWith("xiaoxiang", ignoreCase = true) ||
                name.startsWith("JBD", ignoreCase = true) ||
                name.startsWith("SP", ignoreCase = true) -> BmsType.JBD_BMS
            name.startsWith("ANT", ignoreCase = true) -> BmsType.ANT_BMS
            name.startsWith("DL-", ignoreCase = true) ||
                name.startsWith("Daly", ignoreCase = true) -> BmsType.DALY_BMS
            else -> null
        }
    }
}
