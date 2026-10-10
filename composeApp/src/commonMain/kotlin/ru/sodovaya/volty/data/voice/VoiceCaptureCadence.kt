package ru.sodovaya.volty.data.voice

internal class VoiceCaptureCadence {
    private var previous: Long? = null
    fun deadline(nowNanos: Long): Long {
        val planned=previous?.plus(FRAME_NANOS)
        return (if(planned==null || nowNanos>planned+FRAME_NANOS) nowNanos+FRAME_NANOS else planned)
            .also {previous=it}
    }
    fun reset() {previous=null}
    private companion object {const val FRAME_NANOS=10_000_000L}
}
