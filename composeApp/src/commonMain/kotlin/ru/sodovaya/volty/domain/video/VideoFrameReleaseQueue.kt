package ru.sodovaya.volty.domain.video

/** GL-thread confined ordering policy. Texture ownership stays with CameraX; this stores metadata only. */
class VideoFrameReleaseQueue {
    private val pending = mutableMapOf<Long, Long>()
    private var renderedThrough = Long.MIN_VALUE

    fun offer(timestampNanos: Long, receivedAtMillis: Long) {
        if (timestampNanos <= renderedThrough) return
        if (timestampNanos !in pending) pending[timestampNanos] = receivedAtMillis
        // A faulty or stopped producer must not grow metadata forever.
        while (pending.size > 64) pending.remove(pending.keys.min())
    }

    fun contains(timestampNanos: Long): Boolean = timestampNanos in pending

    fun next(nowMillis: Long, hasUpperBracket: (Long) -> Boolean): Long? {
        val first = pending.entries.minByOrNull { it.key } ?: return null
        val age = (nowMillis - first.value).coerceAtLeast(0)
        if (age < MIN_DELAY_MILLIS || (age < MAX_DELAY_MILLIS && !hasUpperBracket(first.key))) return null
        pending.remove(first.key)
        return first.key
    }

    fun retry(timestampNanos: Long, receivedAtMillis: Long) = offer(timestampNanos, receivedAtMillis)

    /** CameraX can release a texture automatically when its GPU queue is full. */
    fun rendered(timestampNanos: Long) {
        renderedThrough = maxOf(renderedThrough, timestampNanos)
        pending.keys.filter { it <= renderedThrough }.forEach(pending::remove)
    }

    fun flush(): List<Long> = pending.keys.sorted().also { pending.clear() }

    companion object {
        const val MIN_DELAY_MILLIS = 80L
        const val MAX_DELAY_MILLIS = 300L
    }
}

/** RGBA queue textures, capped at 192 MiB rather than allocating ~700 MiB at UHD/60. */
fun videoFrameBufferDepth(width: Int, height: Int, framesPerSecond: Int): Int {
    require(width > 0 && height > 0 && framesPerSecond > 0)
    val bytesPerFrame = width.toLong() * height * 4
    val desired = (framesPerSecond.coerceAtMost(60) * 300 + 999) / 1000 + 4
    return minOf(desired, (192L * 1024 * 1024 / bytesPerFrame).coerceAtMost(64).toInt()).coerceAtLeast(1)
}
