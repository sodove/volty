package ru.sodovaya.volty.data.voice

/** Hardware reads need not align with a WebRTC frame; retain partial PCM under the original route/intent. */
internal class VoiceCaptureFrameBuffer(val capacity: Int) {
    private val pcm = ByteArray(capacity)
    var size = 0
        private set
    var device: Int? = null
        private set
    private var revision = -1L
    fun begin(value: Long) { if (revision != value) { revision = value; consume() } }
    fun append(source: ByteArray, count: Int, routedDevice: Int?) {
        require(count in 0..source.size)
        if (count == 0) return
        if (size > 0 && device != routedDevice) consume()
        require(count <= capacity - size)
        source.copyInto(pcm, size, 0, count)
        size += count
        device = routedDevice
    }
    fun complete(): ByteArray? = pcm.takeIf { size == capacity }
    fun consume() { size = 0; device = null }
}
