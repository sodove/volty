package ru.sodovaya.volty.domain.video

/** Stop drain: preserve issue order, retry a late texture, and bound the entire wait rather than each frame. */
class VideoFrameFlush(timestamps: List<Long>, startedAtMillis: Long) {
    private val frames = timestamps.distinct().sorted().toMutableList()
    private var index = 0
    private val deadline = if (startedAtMillis > Long.MAX_VALUE - 80) Long.MAX_VALUE else startedAtMillis + 80
    val lastTimestamp: Long? get() = frames.lastOrNull()

    fun append(timestamps: List<Long>) {
        val last = frames.lastOrNull() ?: Long.MIN_VALUE
        frames += timestamps.filter { it > last }.distinct().sorted()
    }

    fun drain(nowMillis: Long, draw: (Long) -> Boolean): Boolean {
        while (index < frames.size) {
            if (!draw(frames[index]) && nowMillis < deadline) return false
            index++
        }
        return true
    }
}
