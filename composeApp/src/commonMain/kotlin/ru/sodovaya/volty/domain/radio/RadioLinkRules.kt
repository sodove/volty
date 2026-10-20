package ru.sodovaya.volty.domain.radio

private const val HOUR_MILLIS = 3_600_000L
private const val DAY_MILLIS = 24 * HOUR_MILLIS

/** Yesterday's network name is worth trying only this long after UTC midnight. */
private const val YESTERDAY_WINDOW_MILLIS = 6 * HOUR_MILLIS

/**
 * How a join's [totalMillis] is split between the day-keyed network names, in candidate order
 * (today, then yesterday). The names change at UTC midnight, so a hub raised before it keeps
 * yesterday's name; that only matters for a few hours. Outside the first [YESTERDAY_WINDOW_MILLIS]
 * of the UTC day today's name gets the whole budget and yesterday's is not tried at all; inside it
 * they split 70/30. The parts always sum to [totalMillis].
 *
 * An earlier revision split every join evenly between the two names, which halved the time the
 * name that is almost always right got (I3 of the final review).
 */
fun radioJoinBudgets(nowMillis: Long, totalMillis: Long): List<Long> {
    val sinceMidnight = nowMillis.mod(DAY_MILLIS)
    if (sinceMidnight >= YESTERDAY_WINDOW_MILLIS) return listOf(totalMillis)
    val today = totalMillis * 7 / 10
    return listOf(today, totalMillis - today)
}

/** One Wi-Fi scan result, without Android types. [timestampMicros] is microseconds since boot. */
data class RadioScanResult(val ssid: String?, val bssid: String?, val timestampMicros: Long)

object RadioScanFilter {
    /**
     * Only what the scan started at [scanStartedAtMillis] (elapsed-realtime clock) actually saw, and
     * never this phone's own group interface ([ownBssids], normalised by [normalizeMac]). The scan
     * result list also returns cached entries from earlier scans, which would keep reporting a hub
     * that has long gone (I4 of the final review).
     */
    fun fresh(results: List<RadioScanResult>, scanStartedAtMillis: Long, ownBssids: Set<String>): List<RadioScanResult> {
        val own = ownBssids.mapTo(HashSet(), ::normalizeMac)
        return results.filter { r ->
            r.timestampMicros / 1000 >= scanStartedAtMillis && (r.bssid == null || normalizeMac(r.bssid) !in own)
        }
    }

    /**
     * A P2P group's interface address is the owner's device address with the locally administered
     * bit (0x02 of the first octet) set; clearing it on both sides makes the two comparable.
     */
    fun normalizeMac(mac: String): String {
        val lower = mac.lowercase()
        val colon = lower.indexOf(':')
        if (colon < 0) return lower
        val first = lower.substring(0, colon).toIntOrNull(16) ?: return lower
        return (first and 0x02.inv()).toString(16).padStart(2, '0') + lower.substring(colon)
    }
}

/**
 * The group owner's host when this phone is already a client of one of [candidateNames]'s groups,
 * so a join can reuse that attachment instead of tearing it down and reconnecting (I2: after a
 * Lost the old link often comes back by itself). Null otherwise.
 */
fun reusableRadioAttachment(
    groupFormed: Boolean,
    isGroupOwner: Boolean,
    ownerHost: String?,
    networkName: String?,
    candidateNames: Collection<String>,
): String? =
    if (groupFormed && !isGroupOwner && ownerHost != null && networkName != null && networkName in candidateNames) ownerHost
    else null
