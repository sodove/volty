package ru.sodovaya.volty.data.bms

import ru.sodovaya.volty.domain.model.BmsType

object BmsTypeDetector {

    /**
     * Heuristic detection of BMS type. Name prefix is the primary, high-confidence
     * signal. When the name doesn't match a known prefix (e.g. the "DB…" stock BMS
     * on a Syccyba Goliath, or a JK unit whose local name is missing / unusual), we
     * fall back to the service UUID — but ONLY for short codes unique enough to be
     * worth the false-positive risk:
     *   - 0xFF00 → JBD   (unique to JBD; rarely used by generic gadgets)
     *   - 0xFFE0 → JK    (shared with ANT, but ANT advertises an "ANT…" name caught
     *                     by name match first, so an unmatched 0xFFE0 device is JK)
     * 0xFFF0 (Daly) is intentionally NOT used as a fallback: it's the same short code
     * DJI cameras and headphones advertise, so Daly requires a name match.
     */
    fun detect(name: String?, serviceUuids: List<String>): BmsType? =
        nameMatch(name) ?: serviceMatch(serviceUuids)

    /** Short service codes worth using as a fallback signal (see [detect]). */
    private val fallbackServiceShorts = mapOf(
        "ff00" to BmsType.JBD_BMS,
        "ffe0" to BmsType.JK_BMS
    )

    private fun serviceMatch(serviceUuids: List<String>): BmsType? {
        for (uuid in serviceUuids) {
            val lower = uuid.lowercase()
            if (lower.length < 8) continue
            // 128-bit Bluetooth base UUIDs carry the 16-bit short code at chars 4..7
            // ("0000ffe0-0000-1000-8000-00805f9b34fb").
            fallbackServiceShorts[lower.substring(4, 8)]?.let { return it }
        }
        return null
    }

    private fun nameMatch(name: String?): BmsType? {
        if (name.isNullOrEmpty()) return null
        return when {
            name.startsWith("JK_", ignoreCase = true) || name.startsWith("JK-", ignoreCase = true) -> BmsType.JK_BMS
            name.startsWith("xiaoxiang", ignoreCase = true) ||
                name.startsWith("JBD", ignoreCase = true) ||
                // JBD factory names look like "SP04S001" / "SP15S0001" — require a
                // digit after "SP" so generic gadgets ("Speaker", "SPORT-CAM") don't
                // get misdetected as a battery.
                (name.length > 2 && name.startsWith("SP", ignoreCase = true) && name[2].isDigit()) -> BmsType.JBD_BMS
            name.startsWith("ANT", ignoreCase = true) -> BmsType.ANT_BMS
            name.startsWith("DL-", ignoreCase = true) ||
                name.startsWith("Daly", ignoreCase = true) -> BmsType.DALY_BMS
            else -> null
        }
    }
}
