package ru.sodovaya.volty.data.ble

import kotlin.time.TimeSource

/**
 * Notifications per second over a sliding window, measured by the time each notification arrived
 * (the injected [clock]), not by when anything downstream got round to looking.
 *
 * The rate is (n - 1) / span over the notifications still inside [windowMs]: n timestamps bound
 * n - 1 intervals. One notification, or none left in the window, is not a rate and reads null.
 * Not thread-safe: called from the single observe collector of one session.
 *
 * **The clock must be monotonic** ([monotonicClockMs] is the production one). Pruning drops stamps
 * older than the window relative to the newest, so a clock stepping BACKWARDS would leave every
 * earlier stamp "in the window" and the deque would grow with each notification for as long as the
 * link lives. The meter therefore also defends itself: a stamp earlier than the newest one means
 * the clock is not what it claims, the window is discarded and measurement restarts, and the window
 * is hard-capped at [MAX_STAMPS] besides.
 */
internal class NotificationRateMeter(
    private val clock: () -> Long,
    private val windowMs: Long = DEFAULT_WINDOW_MS,
) {
    private val stamps = ArrayDeque<Long>()

    /** Timestamps currently held; bounded, exposed so a test can prove it. */
    internal val windowSize: Int get() = stamps.size

    /** Records a notification arriving now; returns the rate including it, or null if not measurable. */
    fun onNotification(): Float? {
        val now = clock()
        if (stamps.isNotEmpty() && now < stamps.last()) stamps.clear()
        stamps.addLast(now)
        while (stamps.isNotEmpty() && now - stamps.first() > windowMs) stamps.removeFirst()
        while (stamps.size > MAX_STAMPS) stamps.removeFirst()
        if (stamps.size < 2) return null
        val spanMs = stamps.last() - stamps.first()
        if (spanMs <= 0L) return null
        return (stamps.size - 1) * 1000f / spanMs
    }

    companion object {
        const val DEFAULT_WINDOW_MS = 5_000L

        /** More than any BLE link delivers in one window; a bound, not a tuning knob. */
        const val MAX_STAMPS = 4_096
    }
}

/**
 * Milliseconds on a monotonic clock, from an arbitrary origin: unaffected by the wall clock being
 * set, by NTP, or by a time-zone change. Only differences mean anything.
 */
internal fun monotonicClockMs(source: TimeSource.WithComparableMarks = TimeSource.Monotonic): () -> Long {
    val origin = source.markNow()
    return { origin.elapsedNow().inWholeMilliseconds }
}
