package ru.sodovaya.volty.data.radio

import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong
import ru.sodovaya.volty.domain.radio.RadioAddress
import ru.sodovaya.volty.domain.radio.RadioDatagram
import ru.sodovaya.volty.domain.radio.RadioMessage
import ru.sodovaya.volty.domain.radio.RadioRosterEntry as DomainRosterEntry

/**
 * Maps domain [RadioMessage]s to encrypted wire packets and back. No Android APIs, so the
 * security-relevant rules are unit-testable on the JVM.
 *
 * Nonce rule (ruling R12): the packet header `seq` is, together with [session], the AES-GCM nonce
 * under a key shared by the whole group. It is therefore ONE counter per process incremented for
 * every packet sent to anyone, never a message-level number: the hub keeps a separate audio
 * sequence per recipient and those would repeat under the same session, reusing a nonce. The
 * message-level [RadioMessage.Audio.seq] travels inside the encrypted payload as an 8-byte
 * big-endian prefix before the codec bytes.
 *
 * Identity: `Hello.streamId` and `Roster.hubStreamId` are the sender's header [session];
 * `Audio.slot` and `Bye.slot` are the header sender field.
 *
 * The counter starts at a SecureRandom point in [0, 2^62) (I7 of the final review), not at 0: the
 * session is only 32 random bits, so if two senders ever drew the same session, counters that
 * both start at 0 would collide on their very first packets; random starts make that negligible.
 * 2^62 leaves room to count for the life of the universe without wrapping into negative seqs.
 *
 * Thread-safety: [encode] and [decode] may run on different threads; the counter is atomic.
 *
 * @param ownSender header sender used for Hello (client slot, or 255 while unassigned); Audio and
 *   Bye use the message's slot, Roster and Full are sent by the hub (0).
 * @param encodeFrame turns one PCM frame into codec bytes for a destination (per-destination codec state).
 * @param decodeFrame turns codec bytes from a source (address, packet session) into PCM.
 */
class RadioWireMapper(
    private val codec: RadioPacketCodec,
    private val session: Int,
    private val ownSender: () -> Int,
    private val clock: () -> Long,
    private val encodeFrame: (RadioAddress, ShortArray) -> ByteArray,
    private val decodeFrame: (RadioAddress, Int, ByteArray) -> ShortArray,
    private val replayGuard: RadioReplayGuard = RadioReplayGuard(),
) {
    private val headerSeq = AtomicLong(SecureRandom().nextLong() ushr 2)
    private val dropped = AtomicLong(0)

    /** Messages that could not be encoded (size or range checks) and were dropped. */
    val droppedEncodes: Long get() = dropped.get()

    /** Encodes [datagram].message for its peer, or returns null (and counts it) if it cannot be encoded. */
    fun encode(datagram: RadioDatagram): ByteArray? = try {
        val (type, sender, payload) = toWire(datagram)
        codec.encode(RadioPacket(type, sender, session, headerSeq.getAndIncrement(), payload))
    } catch (_: Exception) {
        dropped.incrementAndGet()
        null
    }

    /** Authenticates, rejects replays (keyed on the header seq) and maps; null for anything not acceptable. */
    fun decode(peer: RadioAddress, bytes: ByteArray): RadioDatagram? {
        val packet = codec.decode(bytes) ?: return null // authenticate before touching replay state
        if (!replayGuard.accept(packet.sender, packet.session, packet.seq)) return null
        val message = try {
            fromWire(peer, packet)
        } catch (_: Exception) {
            null
        } ?: return null
        return RadioDatagram(peer, message)
    }

    private fun toWire(d: RadioDatagram): Triple<RadioPacketType, Int, ByteArray> = when (val m = d.message) {
        is RadioMessage.Hello -> Triple(
            RadioPacketType.HELLO,
            ownSender(),
            RadioHello(m.userId, m.name, m.sentAtMillis, null).encode(),
        )
        is RadioMessage.Audio -> {
            val frame = encodeFrame(d.peer, m.pcm)
            val payload = ByteBuffer.allocate(8 + frame.size).putLong(m.seq).put(frame).array()
            Triple(RadioPacketType.AUDIO, m.slot, payload)
        }
        is RadioMessage.Bye -> Triple(RadioPacketType.BYE, m.slot, ByteArray(0))
        is RadioMessage.Roster -> Triple(
            RadioPacketType.ROSTER,
            0,
            RadioRoster(
                m.entries.map { RadioRosterEntry(it.slot, it.userId, it.name) },
                m.modeWire,
                clock(),
                m.echoMillis,
            ).encode(),
        )
        RadioMessage.Full -> Triple(RadioPacketType.FULL, 0, ByteArray(0))
    }

    private fun fromWire(peer: RadioAddress, p: RadioPacket): RadioMessage? = when (p.type) {
        RadioPacketType.HELLO -> RadioHello.decode(p.payload)?.let {
            RadioMessage.Hello(it.userId, it.displayName, it.sentAtMillis, p.session)
        }
        RadioPacketType.AUDIO -> if (p.payload.size < 8) {
            null
        } else {
            val seq = ByteBuffer.wrap(p.payload, 0, 8).getLong()
            val frame = decodeFrame(peer, p.session, p.payload.copyOfRange(8, p.payload.size))
            RadioMessage.Audio(p.sender, seq, frame)
        }
        RadioPacketType.BYE -> if (p.payload.isEmpty()) RadioMessage.Bye(p.sender) else null
        RadioPacketType.ROSTER -> RadioRoster.decode(p.payload)?.let { r ->
            RadioMessage.Roster(
                r.entries.map { DomainRosterEntry(it.slot, it.userId, it.displayName) },
                r.mode,
                r.echoMillis,
                p.session,
            )
        }
        RadioPacketType.FULL -> if (p.payload.isEmpty()) RadioMessage.Full else null
    }
}
