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
     * Budget for the gap between two consecutive decodes of ONE pack while
     * the link is healthy — the longest a branch may stay silent and still
     * count as being on its normal cadence.
     *
     * Evidence, honestly stated: ONE wheel, ONE session. The real Begode
     * ET Max capture ([ru.sodovaya.volty.data.bms.BegodeDumpFixture], 13 s /
     * 228 notifications) shows the wheel cycling through all four `bmsnum`
     * values and both cell-frame types in ~1.4 s worst case; every polling
     * protocol cycles faster than that. 3 s is a little over twice that
     * observed worst case, and the margin is deliberate: this constant is no
     * longer a tuning knob — it is load-bearing for the [packOfflineAfterMs]
     * correctness bound and enters that sum TWICE (one healthy gap before a
     * link stall, one after), so underestimating it compounds. The failure
     * modes are asymmetric too: a false offline halves the displayed current
     * (or, under a series topology, reports the whole vehicle disconnected),
     * while flagging a genuinely dead branch a few seconds later costs
     * nothing — no downstream consumer is time-critical.
     */
    const val maxHealthyPackGapMs: Long = 3_000L

    /**
     * Max age of one pack's last sample before [VehicleConnection] marks it
     * offline, while OTHER packs on the same link keep reporting.
     *
     * Derived, not tuned — a link-wide stall (the whole connection quiet at
     * once, ordinary Android BLE behaviour) must not masquerade as one dead
     * branch. The worst age a perfectly healthy branch can show at a sweep
     * (a sweep runs on every OTHER branch's submit) is the sum of:
     *
     *  - up to [maxHealthyPackGapMs] of ordinary cadence offset before the
     *    stall begins — the surviving branch decoded last, so the lagging
     *    branch's timestamp is already up to one healthy gap old;
     *  - a stall of just under [staleSampleMs] + [watchdogTickMs], the
     *    longest the session watchdog can miss (the sample age must exceed
     *    [staleSampleMs] AT a tick for it to fire);
     *  - up to [maxHealthyPackGapMs] AGAIN after recovery: the lagging
     *    branch is entitled to a full healthy cycle before its first
     *    post-stall sample, and the surviving branch may legitimately
     *    complete its own next cycle — and sweep — inside that window.
     *
     * Two branches, two gaps: the budget enters the sum twice. What this
     * buys is a conditional guarantee, not an absolute one — as long as
     * every branch keeps its healthy gaps within [maxHealthyPackGapMs], no
     * sweep ever observes a live branch older than this sum, so the strict
     * `>` comparison never fires on it. A branch pushed past the bound has
     * either sat behind a stall long enough to trip the session watchdog
     * (which tears the whole link down first) or exceeded its own cadence
     * budget — and then flagging it is the intended behaviour, not a false
     * positive. The strict `>` makes the exact sum sufficient; deriving it
     * keeps the relationship intact when someone retunes the watchdog.
     *
     * The price is flagging a genuinely dead branch later (12 s at current
     * values vs the old flat 5 s) — nothing about that detection is
     * time-critical, whereas a false offline corrupts the aggregate as
     * described on [maxHealthyPackGapMs].
     */
    const val packOfflineAfterMs: Long = staleSampleMs + watchdogTickMs + 2 * maxHealthyPackGapMs

    /** If we never got a sample, this is how long after connect we wait before declaring stuck. */
    const val noSampleEverMs: Long = 10_000L

    /** Delay between reconnect attempts for the first few attempts. */
    const val reconnectDelayMs: Long = 3_000L

    /** Delay between reconnect attempts once we've tried >= 10 times — back off. */
    const val reconnectDelayAfter10Ms: Long = 10_000L

    /** Threshold of attempts at which the back-off kicks in. */
    const val reconnectBackoffAfter: Int = 10

    /**
     * Start offset between the links of one vehicle at connect time. Raising
     * several GATT connections at the exact same instant is flaky on Android,
     * so link i starts i × this many milliseconds after link 0. A single-link
     * vehicle never waits — the stagger only applies from the second link on.
     */
    const val linkStaggerMs: Long = 300L
}
