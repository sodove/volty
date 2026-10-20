package ru.sodovaya.volty.data.radio

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import ru.sodovaya.volty.domain.radio.RadioAddress
import ru.sodovaya.volty.domain.radio.RadioDatagram
import ru.sodovaya.volty.domain.radio.RadioMode

/**
 * Encrypted UDP between the hub and its clients, over the Wi-Fi Direct interface.
 *
 * - The socket is bound to [bindAddress] (the P2P interface address) and nothing else; this never
 *   binds the process to a network or touches routes, so mobile data keeps working.
 * - The hub binds [port] (47 820); a client binds the same port on its own P2P address and sends to
 *   the group owner's address on that port.
 * - Codecs live here (ruling R11): one frame encoder per destination address (the hub sends a
 *   different mix to every client) and one frame decoder per (source address, packet session).
 *   All are rebuilt when [mode] changes. Lost frames are not decoded here; the jitter buffer conceals.
 * - Header seq / nonce rules live in [RadioWireMapper]; encode failures are dropped and counted,
 *   never thrown (ruling R13).
 * - [send] is non-blocking: it encodes on the caller's thread (cheap) and queues the bytes for a
 *   dedicated sender thread, because a socket write on the main thread is a
 *   `NetworkOnMainThreadException`. If the queue is full the oldest datagram is dropped, which for
 *   live audio is the right loss. [inbound] is backed by one receiver thread.
 *
 * Framework sockets cannot be unit-tested in this repo; verified only on a device.
 *
 * @param codec group audio-key packet codec.
 * @param mode the hub's current mode; read on every packet.
 * @param bindAddress local P2P interface address, or null to bind the wildcard (tests only).
 * @param ownSender header sender for Hello: the client's slot, or 255 while unassigned.
 */
class RadioUdpTransport(
    codec: RadioPacketCodec,
    private val mode: () -> RadioMode,
    private val bindAddress: InetAddress? = null,
    private val port: Int = DEFAULT_PORT,
    ownSender: () -> Int = { UNASSIGNED_SENDER },
    clock: () -> Long = System::currentTimeMillis,
) {
    /**
     * Fresh random stream id per instance (ruling R15): a new transport starts a new header-seq
     * space, so (session, seq) AES-GCM nonces never repeat across instances. Not injectable.
     * Read-only for diagnostics; it is also what peers see as `Hello.streamId`.
     */
    val session: Int = SecureRandom().nextInt()

    private val socket = DatagramSocket(null).let { s ->
        try {
            s.reuseAddress = true
            s.bind(InetSocketAddress(bindAddress, port))
            s
        } catch (e: Exception) {
            s.close()
            throw e
        }
    }
    private val closed = AtomicBoolean(false)
    private val inboundChannel = Channel<RadioDatagram>(INBOUND_CAPACITY, BufferOverflow.DROP_OLDEST)
    private val outboundChannel = Channel<Pair<RadioAddress, ByteArray>>(OUTBOUND_CAPACITY, BufferOverflow.DROP_OLDEST)

    // Codec state. Encoders are touched by the caller of send(), decoders by the receiver thread.
    private val encoders = HashMap<String, RadioFrameEncoder>()
    private val decoders = HashMap<Pair<String, Int>, RadioFrameDecoder>()
    private var encoderMode: RadioMode? = null
    private var decoderMode: RadioMode? = null

    private val mapper = RadioWireMapper(
        codec = codec,
        session = session,
        ownSender = ownSender,
        clock = clock,
        encodeFrame = { peer, pcm -> encoderFor(peer).encode(pcm) },
        decodeFrame = { peer, packetSession, bytes -> decoderFor(peer, packetSession).decode(bytes) },
    )

    /** Datagrams that failed to encode and were dropped. */
    val droppedEncodes: Long get() = mapper.droppedEncodes

    /** Datagrams received that failed authentication, replay or parsing. */
    @Volatile
    var rejectedInbound: Long = 0
        private set

    /** Messages with their sender's address, in arrival order; single collector. */
    val inbound: Flow<RadioDatagram> = inboundChannel.receiveAsFlow()

    private val receiver = Thread({ receiveLoop() }, "radio-udp-rx").apply { isDaemon = true; start() }
    private val sender = Thread({ sendLoop() }, "radio-udp-tx").apply { isDaemon = true; start() }

    /** Never throws and never blocks on the network. Call from one thread at a time (the audio loop). */
    fun send(datagram: RadioDatagram) {
        if (closed.get()) return
        val bytes = synchronized(encoders) { mapper.encode(datagram) } ?: return
        outboundChannel.trySend(datagram.peer to bytes)
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        socket.close() // unblocks receive()
        outboundChannel.close()
        inboundChannel.close()
        receiver.interrupt()
        sender.interrupt()
    }

    private fun encoderFor(peer: RadioAddress): RadioFrameEncoder {
        val m = mode()
        if (m != encoderMode) {
            encoders.clear()
            encoderMode = m
        }
        return encoders.getOrPut(peer.host) { RadioFrameCodecs.encoder(m) }
    }

    private fun decoderFor(peer: RadioAddress, packetSession: Int): RadioFrameDecoder {
        val m = mode()
        if (m != decoderMode) {
            decoders.clear()
            decoderMode = m
        }
        return decoders.getOrPut(peer.host to packetSession) { RadioFrameCodecs.decoder(m) }
    }

    private fun receiveLoop() {
        val buffer = ByteArray(RadioPacketCodec.MAX_DATAGRAM + 1)
        val packet = DatagramPacket(buffer, buffer.size)
        while (!closed.get()) {
            try {
                packet.length = buffer.size
                socket.receive(packet)
                val from = RadioAddress(packet.address.hostAddress ?: continue, packet.port)
                val datagram = mapper.decode(from, packet.data.copyOfRange(packet.offset, packet.offset + packet.length))
                if (datagram == null) rejectedInbound++ else inboundChannel.trySend(datagram)
            } catch (_: Exception) {
                if (closed.get()) return
                // A malformed datagram or a transient socket error must not end reception.
            }
        }
    }

    private fun sendLoop() {
        val out = DatagramPacket(ByteArray(0), 0)
        try {
            while (!closed.get()) {
                val (to, bytes) = outboundChannel.tryReceiveBlocking() ?: return
                try {
                    out.setData(bytes, 0, bytes.size)
                    out.address = InetAddress.getByName(to.host) // literal IPs only: no DNS lookup
                    out.port = to.port
                    socket.send(out)
                } catch (_: Exception) {
                    // peer gone or interface down; UDP, so drop
                }
            }
        } catch (_: InterruptedException) {
            // closing
        }
    }

    companion object {
        const val DEFAULT_PORT = 47_820
        const val UNASSIGNED_SENDER = 255
        private const val INBOUND_CAPACITY = 128
        private const val OUTBOUND_CAPACITY = 128
    }
}

/** Blocks the calling thread until an element arrives; null when the channel is closed. */
private fun <T> Channel<T>.tryReceiveBlocking(): T? =
    kotlinx.coroutines.runBlocking { receiveCatching().getOrNull() }
