package ru.sodovaya.volty.domain.radio

/**
 * Whether the auto-join poll may try to join a radio right now. Only from Idle, only while the app
 * is visible (ruling R20: Android 14+ refuses a microphone service started from the background),
 * only with every permission granted, and not within [suppressedUntilMillis] of a manual leave.
 */
fun shouldAutoJoin(
    enabled: Boolean,
    state: RadioState,
    visible: Boolean,
    permitted: Boolean,
    nowMillis: Long,
    suppressedUntilMillis: Long,
): Boolean = enabled && state == RadioState.Idle && visible && permitted && nowMillis >= suppressedUntilMillis

private const val MIC = "android.permission.RECORD_AUDIO"
private const val NEARBY_WIFI = "android.permission.NEARBY_WIFI_DEVICES"
private const val FINE_LOCATION = "android.permission.ACCESS_FINE_LOCATION"

/**
 * Runtime permissions the radio needs, in request order. Empty below Android 10 (the radio is
 * unavailable there). Wi-Fi Direct needs NEARBY_WIFI_DEVICES on Android 13+ and fine location
 * before it; scanning (auto-join, duplicate detection) needs fine location on every level.
 */
fun radioRequiredPermissions(sdkInt: Int, scanning: Boolean): List<String> {
    if (sdkInt < 29) return emptyList()
    val list = mutableListOf(MIC, if (sdkInt >= 33) NEARBY_WIFI else FINE_LOCATION)
    if (scanning && FINE_LOCATION !in list) list += FINE_LOCATION
    return list
}
