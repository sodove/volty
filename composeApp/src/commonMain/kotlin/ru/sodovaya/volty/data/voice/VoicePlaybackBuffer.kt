package ru.sodovaya.volty.data.voice

/** Bounded PCM backlog, serialized by its native stream owner. */
internal class VoicePlaybackBuffer(val capacity: Int, private val alignment: Int) {
    private val data = ByteArray(capacity)
    init { require(alignment > 0 && capacity > 0 && capacity % alignment == 0) }
    var size = 0
        private set
    fun append(pcm: ByteArray) {
        require(pcm.size % alignment == 0)
        val count = minOf(pcm.size, capacity)
        val discarded = maxOf(0, size + count - capacity)
        if (discarded > 0) { data.copyInto(data, 0, discarded, size); size -= discarded }
        pcm.copyInto(data, size, pcm.size - count, pcm.size)
        size += count
    }
    fun drain(write: (ByteArray, Int) -> Int): Int {
        if (size == 0) return 0
        val count = write(data, size)
        require(count in 0..size && count % alignment == 0)
        data.copyInto(data, 0, count, size)
        size -= count
        return count
    }
    fun clear() { size = 0 }
}
