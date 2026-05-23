package com.volty.app.data.bms

import com.volty.app.domain.model.BmsType

object BmsTypeDetector {

    /**
     * Heuristic detection of BMS type by name prefix only.
     * Service-UUID-only matching produced too many false positives (cameras, headphones)
     * because shared short codes like 0xFFF0 are widely used.
     */
    fun detect(name: String?, serviceUuids: List<String>): BmsType? = nameMatch(name)

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
