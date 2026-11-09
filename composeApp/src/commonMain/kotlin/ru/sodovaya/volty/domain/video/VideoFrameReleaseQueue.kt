package ru.sodovaya.volty.domain.video

/** GL-thread confined ordering policy. Texture ownership stays with CameraX; this stores metadata only. */
class VideoFrameReleaseQueue {
    private val pending = mutableSetOf<Long>()
    private var renderedThrough = Long.MIN_VALUE

    fun offer(timestampNanos: Long) {
        if (timestampNanos <= renderedThrough) return
        pending += timestampNanos
        // A faulty or stopped producer must not grow metadata forever.
        while (pending.size > 64) pending.remove(pending.min())
    }

    fun contains(timestampNanos: Long): Boolean = timestampNanos in pending

    /** CameraX can release a texture automatically when its GPU queue is full. */
    fun rendered(timestampNanos: Long) {
        renderedThrough = maxOf(renderedThrough, timestampNanos)
        pending.removeAll { it <= renderedThrough }
    }

    fun flush(): List<Long> = pending.sorted().also { pending.clear() }
}

/** About 300 ms of camera-paced buffering, with a 192 MiB RGBA texture budget. */
fun videoFrameBufferDepth(width: Int, height: Int, framesPerSecond: Int): Int {
    require(width > 0 && height > 0 && framesPerSecond > 0)
    val bytesPerFrame = width.toLong() * height * 4
    val desired = (framesPerSecond.coerceAtMost(60) * 300 + 999) / 1000
    return minOf(desired, (192L * 1024 * 1024 / bytesPerFrame).coerceAtMost(64).toInt()).coerceAtLeast(1)
}
