package ru.sodovaya.volty.data.ble

/**
 * Centralised tuning constants for the Kable BLE pipeline.
 *
 * Promoted from inline magic numbers in [KableBmsRepository] / [ConnectionSession]
 * so reviewers don't have to hunt them down and so platform-specific overrides
 * can be slotted in if we ever need them (e.g. iOS supervision is different).
 */
object BleConfig {
    /** Hard cap on `peripheral.connect()`. BLE supervision is too slow for our UX. */
    const val connectTimeoutMs: Long = 7_000L

    /** Time we'll wait scanning for an unknown advertisement before giving up. */
    const val advertisementSearchMs: Long = 5_000L

    /** Delay between consecutive write commands during handshake / polling. */
    const val writeSpacingMs: Long = 50L

    /** Pause between subscribing-to-notifications and writing the handshake. */
    const val handshakeWarmupMs: Long = 200L

    /** Grace period after connect before the stale-sample watchdog starts judging. */
    const val watchdogGraceMs: Long = 2_000L

    /** Watchdog evaluation tick. */
    const val watchdogTickMs: Long = 1_000L

    /** If we got at least one sample, this is the max allowed age before we declare stale. */
    const val staleSampleMs: Long = 5_000L

    /** If we never got a sample, this is how long after connect we wait before declaring stuck. */
    const val noSampleEverMs: Long = 10_000L

    /** Delay between reconnect attempts for the first few attempts. */
    const val reconnectDelayMs: Long = 3_000L

    /** Delay between reconnect attempts once we've tried >= 10 times — back off. */
    const val reconnectDelayAfter10Ms: Long = 10_000L

    /** Threshold of attempts at which the back-off kicks in. */
    const val reconnectBackoffAfter: Int = 10
}
