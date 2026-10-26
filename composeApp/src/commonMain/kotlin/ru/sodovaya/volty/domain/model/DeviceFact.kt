package ru.sodovaya.volty.domain.model

/**
 * Stable identity of one protocol-specific fact. Protocols emit these keys with raw values; the
 * words that name them come from string resources, never from the protocol.
 */
enum class DeviceFactKey {
    // Begode
    ODOMETER_KM,
    POWER_ON_DISTANCE_KM,
    TILTBACK_SPEED_KMH,
    WHEEL_ALERTS,
    BOARD_TEMP_C,
    LIVE_PWM_RAW,
    MOTION_DUTY_RAW,
    SERIES_CELLS_PROOF,
    SMART_BMS_SEEN,
    BRANCH_VOLTAGE_V,
    BRANCH_CURRENT_A,

    // VESC
    CONTROLLER_ID,
    FIRMWARE,
    CAN_IDS,
    FAULT_CODES,
}

/**
 * One fact a protocol reports. [value] is already formatted and carries no words (a number, a
 * hex string, a comma list); [unit] is a bare unit symbol such as "km" or "V", or null.
 *
 * [index] distinguishes repeats of one key: a per-branch fact such as `BRANCH_VOLTAGE_V` is
 * emitted once per branch with `index = 1, 2, ...`. It is null for a fact that occurs once.
 */
data class DeviceFact(
    val key: DeviceFactKey,
    val value: String,
    val unit: String? = null,
    val index: Int? = null,
)
