package ru.sodovaya.volty.domain.radio

enum class RadioClientPhase { JOINING, CONNECTED, FULL, LOST }

/**
 * Pure client state machine for a phone that joined someone else's radio. Sends Hello every
 * second, transmits mic frames only while the voice gate is open and its slot is known, plays the
 * hub's mix (arriving as slot 0) through one jitter buffer and follows the hub's mode.
 * No clocks, no coroutines: time arrives through `nowMillis`; everything [tick] returns goes to the hub.
 *
 * While LOST the session keeps probing with Hello (never audio): the hub forgets a silent client,
 * so without probes nothing could ever bring the session back. A Roster ends LOST.
 */
class RadioClientSession(
    private val userId: String,
    private val name: String,
    private val streamId: Int,
    private val lostAfterMillis: Long = 3_000,
) {
    var phase: RadioClientPhase = RadioClientPhase.JOINING
        private set
    var roster: List<RadioMember> = emptyList()
        private set
    var rttMillis: Long? = null
        private set
    var mode: RadioMode = RadioMode.BALANCED
        private set
    var slot: Int? = null
        private set

    private val gate = RadioVoiceGate()
    private var jitter = RadioJitterBuffer(mode.jitterTargetFrames)
    private var lastHubStreamId: Int? = null
    private var lastHeardAt: Long? = null
    private var lastHelloAt: Long? = null
    private var nextSeq = 0L

    fun receive(message: RadioMessage, nowMillis: Long) {
        when (message) {
            is RadioMessage.Roster -> onRoster(message, nowMillis)
            is RadioMessage.Audio -> {
                if (phase == RadioClientPhase.LOST || phase == RadioClientPhase.FULL) return
                lastHeardAt = nowMillis
                jitter.push(message.seq, message.pcm)
            }
            RadioMessage.Full -> {
                lastHeardAt = nowMillis
                phase = RadioClientPhase.FULL
            }
            // Client-originated kinds are meaningless inbound.
            is RadioMessage.Hello, is RadioMessage.Bye -> Unit
        }
    }

    private fun onRoster(msg: RadioMessage.Roster, now: Long) {
        val wasLost = phase == RadioClientPhase.LOST
        lastHeardAt = now
        val newMode = RadioMode.fromWire(msg.modeWire) ?: mode
        val streamChanged = lastHubStreamId != null && lastHubStreamId != msg.hubStreamId
        if (wasLost || streamChanged || newMode != mode) jitter = RadioJitterBuffer(newMode.jitterTargetFrames)
        mode = newMode
        lastHubStreamId = msg.hubStreamId
        roster = msg.entries.map { RadioMember(it.slot, it.userId, it.name, now, null) }
        slot = msg.entries.firstOrNull { it.userId == userId }?.slot
        phase = RadioClientPhase.CONNECTED
        val helloAt = lastHelloAt
        val echo = msg.echoMillis
        if (helloAt != null && echo != null && now - helloAt in 0..RTT_WINDOW_MILLIS) rttMillis = now - echo
    }

    fun tick(nowMillis: Long, micFrame: ShortArray): List<RadioMessage> {
        val heardAt = lastHeardAt ?: nowMillis.also { lastHeardAt = it }
        if ((phase == RadioClientPhase.JOINING || phase == RadioClientPhase.CONNECTED) &&
            nowMillis - heardAt >= lostAfterMillis
        ) {
            phase = RadioClientPhase.LOST
        }
        if (phase == RadioClientPhase.FULL) return emptyList()

        val out = mutableListOf<RadioMessage>()
        val helloAt = lastHelloAt
        if (helloAt == null || nowMillis - helloAt >= HELLO_EVERY_MILLIS) {
            lastHelloAt = nowMillis
            out += RadioMessage.Hello(userId, name, nowMillis, streamId)
        }
        // The gate sees every frame so its noise floor keeps adapting even while nothing is sent.
        val open = gate.process(micFrame)
        val mySlot = slot
        if (open && mySlot != null && phase == RadioClientPhase.CONNECTED) {
            out += RadioMessage.Audio(mySlot, nextSeq++, micFrame)
        }
        return out
    }

    fun playback(): ShortArray? =
        if (phase == RadioClientPhase.CONNECTED) jitter.pop() else null

    private companion object {
        const val HELLO_EVERY_MILLIS = 1_000L
        const val RTT_WINDOW_MILLIS = 1_000L
    }
}
