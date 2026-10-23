package ru.sodovaya.volty.domain.voice

/**
 * Decides when music should be ducked because someone else is speaking in the channel.
 * Pure: time is always passed in. Only remote speakers count (the caller filters out the
 * local participant). The duck is held for [holdMillis] after the last remote speech report,
 * so the gaps between words do not pump the music volume.
 */
class MusicDuckGate(private val holdMillis: Long = 1_500) {
    private var speaking = false
    private var silentSince: Long? = null

    /** @return true = keep the duck. */
    fun onSpeakers(remoteSpeaking: Boolean, nowMillis: Long): Boolean {
        if (remoteSpeaking) {
            speaking = true
            silentSince = null
        } else if (speaking && silentSince == null) {
            silentSince = nowMillis
        }
        return onTick(nowMillis)
    }

    /** @return true = keep the duck. */
    fun onTick(nowMillis: Long): Boolean {
        val since = silentSince
        if (speaking && since != null && nowMillis - since >= holdMillis) {
            speaking = false
            silentSince = null
        }
        return speaking
    }

    /** Session closed or the setting turned off: drop the duck at once. Always false. */
    fun release(): Boolean {
        speaking = false
        silentSince = null
        return false
    }
}
