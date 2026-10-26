package ru.sodovaya.volty.data.ble

/**
 * Notifications per second over a sliding window, measured by the time each notification arrived
 * (the injected [clock]), not by when anything downstream got round to looking.
 *
 * The rate is (n - 1) / span over the notifications still inside [windowMs]: n timestamps bound
 * n - 1 intervals. One notification, or none left in the window, is not a rate and reads null.
 * Not thread-safe: called from the single observe collector of one session.
 */
internal class NotificationRateMeter(
    private val clock: () -> Long,
    private val windowMs: Long = DEFAULT_WINDOW_MS,
) {
    private val stamps = ArrayDeque<Long>()

    /** Records a notification arriving now; returns the rate including it, or null if not measurable. */
    fun onNotification(): Float? {
        val now = clock()
        stamps.addLast(now)
        while (stamps.isNotEmpty() && now - stamps.first() > windowMs) stamps.removeFirst()
        if (stamps.size < 2) return null
        val spanMs = stamps.last() - stamps.first()
        if (spanMs <= 0L) return null
        return (stamps.size - 1) * 1000f / spanMs
    }

    companion object {
        const val DEFAULT_WINDOW_MS = 5_000L
    }
}
