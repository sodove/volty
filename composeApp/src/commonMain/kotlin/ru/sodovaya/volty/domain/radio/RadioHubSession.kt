package ru.sodovaya.volty.domain.radio

data class RadioAddress(val host: String, val port: Int)

/** Domain roster entry; the transport layer maps it to and from the wire type. */
data class RadioRosterEntry(val slot: Int, val userId: String, val name: String)

sealed interface RadioMessage {
    data class Hello(
        val userId: String,
        val name: String,
        val sentAtMillis: Long,
        val streamId: Int,
    ) : RadioMessage

    class Audio(val slot: Int, val seq: Long, val pcm: ShortArray) : RadioMessage {
        override fun equals(other: Any?): Boolean =
            other is Audio && slot == other.slot && seq == other.seq && pcm.contentEquals(other.pcm)

        override fun hashCode(): Int = (slot * 31 + seq.hashCode()) * 31 + pcm.contentHashCode()

        override fun toString(): String = "Audio(slot=$slot, seq=$seq, samples=${pcm.size})"
    }

    data class Bye(val slot: Int) : RadioMessage

    data class Roster(
        val entries: List<RadioRosterEntry>,
        val modeWire: Int,
        val echoMillis: Long?,
        val hubStreamId: Int,
    ) : RadioMessage

    data object Full : RadioMessage
}

data class RadioDatagram(val peer: RadioAddress, val message: RadioMessage)

data class RadioMember(
    val slot: Int,
    val userId: String,
    val name: String,
    val lastSeenMillis: Long,
    val rttMillis: Long?,
)

/**
 * Pure hub state machine: admits clients into slots 1..[maxClients], keeps one jitter buffer per
 * client and, on every 20 ms [tick], sends each client a mix of everyone except that client.
 * Slot 0 is the hub itself. No clocks, no coroutines: time arrives through `nowMillis`.
 */
class RadioHubSession(
    private val hubUserId: String,
    private val hubName: String,
    mode: RadioMode = RadioMode.BALANCED,
    private val maxClients: Int = 9,
    private val evictAfterMillis: Long = 3_000,
    private val hubStreamId: Int = 0,
) {
    private class Client(
        val slot: Int,
        var address: RadioAddress,
        val userId: String,
        var name: String,
        var lastSeen: Long,
        var lastHelloSentAt: Long,
        var streamId: Int,
        var jitter: RadioJitterBuffer,
    ) {
    }

    private var mode = mode
    private val clients = sortedMapOf<Int, Client>()
    // Next hub-to-client audio seq per userId, kept for the hub's lifetime so a client that
    // rejoins continues the sequence (its jitter buffer never forgets what it already played).
    private val sendSeqByUser = mutableMapOf<String, Long>()
    private var ticks = 0L
    private var playback: ShortArray? = null

    val members: List<RadioMember>
        get() = clients.values.map { RadioMember(it.slot, it.userId, it.name, it.lastSeen, null) }

    fun setMode(mode: RadioMode) {
        this.mode = mode
        clients.values.forEach { it.jitter = RadioJitterBuffer(mode.jitterTargetFrames) }
    }

    fun hubPlayback(): ShortArray? = playback

    fun receive(datagram: RadioDatagram, nowMillis: Long): List<RadioDatagram> {
        val peer = datagram.peer
        return when (val msg = datagram.message) {
            is RadioMessage.Hello -> hello(peer, msg, nowMillis)
            is RadioMessage.Audio -> {
                clients.values.firstOrNull { it.address == peer }?.let {
                    it.lastSeen = nowMillis
                    it.jitter.push(msg.seq, msg.pcm)
                }
                emptyList()
            }
            is RadioMessage.Bye -> {
                val client = clients.values.firstOrNull { it.address == peer }
                if (client != null && client.slot == msg.slot) clients.remove(client.slot)
                emptyList()
            }
            // Hub-originated message kinds are meaningless inbound.
            is RadioMessage.Roster, RadioMessage.Full -> emptyList()
        }
    }

    private fun hello(peer: RadioAddress, msg: RadioMessage.Hello, now: Long): List<RadioDatagram> {
        val existing = clients.values.firstOrNull { it.address == peer }
            ?: clients.values.firstOrNull { it.userId == msg.userId }
        if (existing != null) {
            if (existing.address != peer) {
                // Same person from a new address: keep the slot, start from clean audio state.
                existing.address = peer
                existing.jitter = RadioJitterBuffer(mode.jitterTargetFrames)
            }
            if (existing.streamId != msg.streamId) {
                // Sender restarted: its seq numbering began again, so the old high-water mark is stale.
                existing.streamId = msg.streamId
                existing.jitter = RadioJitterBuffer(mode.jitterTargetFrames)
            }
            existing.name = msg.name
            existing.lastSeen = now
            existing.lastHelloSentAt = msg.sentAtMillis
            return listOf(rosterFor(existing))
        }
        val slot = (1..maxClients).firstOrNull { it !in clients }
            ?: return listOf(RadioDatagram(peer, RadioMessage.Full))
        val client = Client(
            slot, peer, msg.userId, msg.name, now, msg.sentAtMillis, msg.streamId,
            RadioJitterBuffer(mode.jitterTargetFrames),
        )
        clients[slot] = client
        // Tell the newcomer its slot straight away instead of making it wait for the next second.
        return listOf(rosterFor(client))
    }

    fun tick(nowMillis: Long, hubFrame: ShortArray?): List<RadioDatagram> {
        clients.values.removeAll { nowMillis - it.lastSeen > evictAfterMillis }
        ticks++

        // One frame per source: slot 0 (hub) plus whatever each client's buffer releases.
        val frames = linkedMapOf<Int, ShortArray>()
        if (hubFrame != null) frames[0] = hubFrame
        for (client in clients.values) client.jitter.pop()?.let { frames[client.slot] = it }

        val heardByHub = frames.filterKeys { it != 0 }.values.toList()
        playback = if (heardByHub.isEmpty()) null else RadioMixer.mix(heardByHub)

        val out = mutableListOf<RadioDatagram>()
        val rosterDue = ticks % ROSTER_EVERY_TICKS == 0L
        for (client in clients.values) {
            val others = frames.filterKeys { it != client.slot }.values.toList()
            if (others.isNotEmpty()) {
                out += RadioDatagram(
                    client.address,
                    RadioMessage.Audio(0, nextSeq(client.userId), RadioMixer.mix(others)),
                )
            }
            if (rosterDue) out += rosterFor(client)
        }
        return out
    }

    private fun nextSeq(userId: String): Long {
        val seq = sendSeqByUser[userId] ?: 0L
        sendSeqByUser[userId] = seq + 1
        return seq
    }

    private fun rosterFor(client: Client): RadioDatagram {
        val entries = buildList {
            add(RadioRosterEntry(0, hubUserId, hubName))
            clients.values.forEach { add(RadioRosterEntry(it.slot, it.userId, it.name)) }
        }
        return RadioDatagram(
            client.address,
            RadioMessage.Roster(entries, mode.wire, client.lastHelloSentAt, hubStreamId),
        )
    }

    private companion object {
        const val ROSTER_EVERY_TICKS = 50L
    }
}
