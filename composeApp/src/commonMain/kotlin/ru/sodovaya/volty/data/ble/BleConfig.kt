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

    /**
     * Worst observed gap between two consecutive decodes for ONE pack while
     * the link is healthy. Evidence: the real Begode ET Max capture
     * ([ru.sodovaya.volty.data.bms.BegodeDumpFixture], 13 s / 228
     * notifications) shows the wheel cycling through all four `bmsnum` values
     * and both cell-frame types in ~1.4 s — in normal streaming no branch is
     * silent for much longer than about a second and a half. Every polling
     * protocol cycles faster than this too.
     */
    const val maxHealthyPackGapMs: Long = 1_500L

    /**
     * Max age of one pack's last sample before [VehicleConnection] marks it
     * offline, while OTHER packs on the same link keep reporting.
     *
     * Derived, not tuned — a link-wide stall (the whole connection quiet at
     * once, ordinary Android BLE behaviour) must always resolve as a LINK
     * problem, i.e. the session watchdog tearing the connection down, before
     * it can masquerade as one dead branch:
     *
     *  - the longest stall the watchdog can miss is just under
     *    [staleSampleMs] + [watchdogTickMs] (the sample age must exceed
     *    [staleSampleMs] AT a tick for it to fire);
     *  - when the link then recovers, the first sample belongs to one branch,
     *    and the sweep measures the OTHER branch's age as that stall plus the
     *    branch's own normal pre-stall gap — up to [maxHealthyPackGapMs] more.
     *
     * So the sum is the worst age a perfectly healthy branch can show after a
     * stall the watchdog overlooked; any stall long enough to push a branch
     * past it necessarily trips the watchdog first. The sweep compares with
     * strict `>`, which makes the exact sum sufficient. Deriving it keeps the
     * relationship intact when someone tunes the watchdog constants.
     *
     * The price is flagging a genuinely dead branch ~2.5 s later than the old
     * 5 s constant — nothing about that detection is time-critical.
     */
    const val packOfflineAfterMs: Long = staleSampleMs + watchdogTickMs + maxHealthyPackGapMs

    /** If we never got a sample, this is how long after connect we wait before declaring stuck. */
    const val noSampleEverMs: Long = 10_000L

    /** Delay between reconnect attempts for the first few attempts. */
    const val reconnectDelayMs: Long = 3_000L

    /** Delay between reconnect attempts once we've tried >= 10 times — back off. */
    const val reconnectDelayAfter10Ms: Long = 10_000L

    /** Threshold of attempts at which the back-off kicks in. */
    const val reconnectBackoffAfter: Int = 10
}
