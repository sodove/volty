package ru.sodovaya.volty.data.bms

import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.ControllerType

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
            // Begode / Gotway wheels advertise a model-family name, e.g.
            // "GotWay_75042" (ET Max), "EXN-…", "MTEN3", "Master", "T4".
            // The wheel also exposes FFE0, but the JK fallback below only fires
            // when no name prefix matched, so these must stay in the name list.
            name.startsWith("GotWay", ignoreCase = true) ||
                name.startsWith("Begode", ignoreCase = true) ||
                name.startsWith("GW", ignoreCase = true) ||
                name.startsWith("EX", ignoreCase = true) ||
                name.startsWith("MTEN", ignoreCase = true) ||
                name.startsWith("Master", ignoreCase = true) ||
                name.startsWith("T4", ignoreCase = true) -> BmsType.BEGODE
            else -> null
        }
    }

    /**
     * Controller detection, deliberately separate from [detect]: a controller is
     * not a BMS, and the Nordic UART service is generic enough (any nRF-based
     * gadget exposes it) that this is a CANDIDATE signal only — the picker
     * confirms it by a successful GET_VALUES_SETUP handshake. Kept out of
     * [detect] so no battery flow can ever be handed a controller type.
     */
    fun detectController(name: String?, serviceUuids: List<String>): ControllerType? {
        // A device that already looks like a known BMS is never a controller.
        // This guard must win over controller-family substrings: a VESC retrofit
        // renamed into a Begode wheel (e.g. "GW-VESC") matches both the BMS
        // prefix and the controller substring.
        // A wheel's FFE0 service is shared with JK/ANT fallbacks.  A strong
        // controller-family name must therefore be evaluated before the
        // service-only BMS fallback; otherwise "Ninebot One" is silently
        // handed to JK merely because it exposes FFE0.
        controllerNameMatch(name, serviceUuids)?.let {
            if (nameMatch(name) == null) return it
        }
        if (detect(name, serviceUuids) != null) return null
        controllerNameMatch(name, serviceUuids)?.let { return it }
        val hasNus = serviceUuids.any { it.equals(VescProtocol.NUS_SERVICE, ignoreCase = true) }
        return if (hasNus) ControllerType.VESC else null
    }

    private fun controllerNameMatch(name: String?, serviceUuids: List<String>): ControllerType? {
        if (name.isNullOrEmpty()) return null
        return when {
            name.contains("VESC", ignoreCase = true) ||
                name.startsWith("uBox", ignoreCase = true) ||
                name.startsWith("ubox", ignoreCase = true) -> ControllerType.VESC
            name.contains("Ninebot", ignoreCase = true) ||
                name.contains("Segway", ignoreCase = true) -> {
                if (serviceUuids.any { it.contains("6e400001", ignoreCase = true) }) {
                    ControllerType.NINEBOT
                } else {
                    ControllerType.NINEBOT_LEGACY
                }
            }
            name.contains("KingSong", ignoreCase = true) ||
                name.startsWith("KS-", ignoreCase = true) ||
                name.startsWith("KS_", ignoreCase = true) -> ControllerType.KINGSONG
            name.contains("InMotion", ignoreCase = true) ||
                name.startsWith("V8", ignoreCase = true) ||
                name.startsWith("V10", ignoreCase = true) ||
                name.startsWith("V11", ignoreCase = true) ||
                name.startsWith("V12", ignoreCase = true) ||
                name.startsWith("V13", ignoreCase = true) -> ControllerType.INMOTION
            name.contains("Nosfet", ignoreCase = true) -> ControllerType.NOSFET
            name.contains("Veteran", ignoreCase = true) ||
                name.contains("Sherman", ignoreCase = true) ||
                name.contains("Abrams", ignoreCase = true) ||
                name.contains("Lynx", ignoreCase = true) ||
                name.contains("Patton", ignoreCase = true) -> ControllerType.VETERAN
            // Kelly's Nordic-UART module is shared with generic NUS devices,
            // so only its observed product-family names are enough to propose
            // Kelly. A service UUID alone remains the conservative VESC
            // candidate handled by detectController.
            name.startsWith("Kelly", ignoreCase = true) ||
                name.startsWith("KLS", ignoreCase = true) ||
                name.startsWith("KBLS", ignoreCase = true) -> ControllerType.KELLY
            else -> null
        }
    }
}
