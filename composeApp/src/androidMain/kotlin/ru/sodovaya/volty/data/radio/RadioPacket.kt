package ru.sodovaya.volty.data.radio

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

enum class RadioPacketType(val wire: Int) {
    HELLO(1), AUDIO(2), ROSTER(3), BYE(4), FULL(5);

    companion object {
        fun fromWire(value: Int): RadioPacketType? = values().firstOrNull { it.wire == value }
    }
}

/** sender: 0 = hub, 1..9 = client slot, 255 = unassigned. session: random per process start. */
class RadioPacket(
    val type: RadioPacketType,
    val sender: Int,
    val session: Int,
    val seq: Long,
    val payload: ByteArray,
)

/**
 * Header `'V','R', version, type, sender, session(4), seq(8)` = 17 bytes, sent in the clear and bound
 * as AES-GCM AAD; nonce = session(4) || seq(8); ciphertext+tag follows. [decode] never throws.
 */
class RadioPacketCodec(audioKey: ByteArray) {
    private val keySpec = SecretKeySpec(audioKey.copyOf(), "AES")

    fun encode(p: RadioPacket): ByteArray {
        val header = ByteBuffer.allocate(HEADER)
            .put('V'.code.toByte()).put('R'.code.toByte()).put(VERSION.toByte())
            .put(p.type.wire.toByte()).put(p.sender.toByte())
            .putInt(p.session).putLong(p.seq).array()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(TAG_BITS, nonce(header)))
        cipher.updateAAD(header)
        return header + cipher.doFinal(p.payload)
    }

    fun decode(bytes: ByteArray): RadioPacket? {
        if (bytes.size < HEADER + TAG_BYTES || bytes.size > MAX_DATAGRAM) return null
        if (bytes[0] != 'V'.code.toByte() || bytes[1] != 'R'.code.toByte() || bytes[2].toInt() != VERSION) return null
        val type = RadioPacketType.fromWire(bytes[3].toInt() and 0xFF) ?: return null
        val header = bytes.copyOfRange(0, HEADER)
        val buf = ByteBuffer.wrap(header)
        val sender = buf.get(4).toInt() and 0xFF
        val session = buf.getInt(5)
        val seq = buf.getLong(9)
        val payload = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(TAG_BITS, nonce(header)))
            cipher.updateAAD(header)
            cipher.doFinal(bytes, HEADER, bytes.size - HEADER)
        } catch (_: Exception) {
            return null
        }
        return RadioPacket(type, sender, session, seq, payload)
    }

    private fun nonce(header: ByteArray): ByteArray = header.copyOfRange(5, HEADER) // session(4) || seq(8)

    companion object {
        const val HEADER = 17
        const val VERSION = 1
        const val MAX_DATAGRAM = 1200
        private const val TAG_BITS = 128
        private const val TAG_BYTES = 16
    }
}

/** Per `(sender, session)` sliding window of 64 sequence numbers. */
class RadioReplayGuard {
    private class Window(var highest: Long, var mask: Long)

    private val windows = HashMap<Long, Window>()

    @Synchronized
    fun accept(sender: Int, session: Int, seq: Long): Boolean {
        val key = (sender.toLong() shl 32) or (session.toLong() and 0xFFFF_FFFFL)
        val w = windows[key]
        if (w == null) {
            windows[key] = Window(seq, 1L)
            return true
        }
        if (seq > w.highest) {
            val shift = seq - w.highest
            w.mask = if (shift >= 64) 1L else (w.mask shl shift.toInt()) or 1L
            w.highest = seq
            return true
        }
        val back = w.highest - seq
        if (back >= 64) return false
        val bit = 1L shl back.toInt()
        if (w.mask and bit != 0L) return false
        w.mask = w.mask or bit
        return true
    }
}

private const val MAX_NAME = 80

private fun DataOutputStream.writeText(s: String) {
    val bytes = s.take(MAX_NAME).toByteArray(Charsets.UTF_8)
    writeShort(bytes.size)
    write(bytes)
}

private fun DataInputStream.readText(): String? {
    val len = readUnsignedShort()
    if (len > MAX_NAME * 4 || len > available()) return null
    val bytes = ByteArray(len)
    readFully(bytes)
    val s = String(bytes, Charsets.UTF_8)
    return if (s.length > MAX_NAME) null else s
}

private fun DataOutputStream.writeEcho(echo: Long?) {
    writeBoolean(echo != null)
    if (echo != null) writeLong(echo)
}

private fun DataInputStream.readEcho(): Long? = if (readBoolean()) readLong() else null

private inline fun <T> parse(bytes: ByteArray, block: DataInputStream.() -> T?): T? = try {
    val input = DataInputStream(ByteArrayInputStream(bytes))
    val value = input.block()
    if (value != null && input.available() == 0) value else null
} catch (_: Exception) {
    null
}

private inline fun build(block: DataOutputStream.() -> Unit): ByteArray {
    val out = ByteArrayOutputStream()
    DataOutputStream(out).block()
    return out.toByteArray()
}

data class RadioHello(val userId: String, val displayName: String, val sentAtMillis: Long, val echoMillis: Long?) {
    fun encode(): ByteArray = build {
        writeText(userId); writeText(displayName); writeLong(sentAtMillis); writeEcho(echoMillis)
    }

    companion object {
        fun decode(bytes: ByteArray): RadioHello? = parse(bytes) {
            RadioHello(readText() ?: return@parse null, readText() ?: return@parse null, readLong(), readEcho())
        }
    }
}

data class RadioRosterEntry(val slot: Int, val userId: String, val displayName: String)

data class RadioRoster(
    val entries: List<RadioRosterEntry>,
    val mode: Int,
    val sentAtMillis: Long,
    val echoMillis: Long?,
) {
    fun encode(): ByteArray = build {
        writeByte(mode)
        writeLong(sentAtMillis)
        writeEcho(echoMillis)
        writeByte(entries.size)
        for (e in entries) {
            writeByte(e.slot); writeText(e.userId); writeText(e.displayName)
        }
    }

    companion object {
        fun decode(bytes: ByteArray): RadioRoster? = parse(bytes) {
            val mode = readUnsignedByte()
            val sent = readLong()
            val echo = readEcho()
            val count = readUnsignedByte()
            val entries = ArrayList<RadioRosterEntry>(count)
            repeat(count) {
                val slot = readUnsignedByte()
                entries += RadioRosterEntry(
                    slot,
                    readText() ?: return@parse null,
                    readText() ?: return@parse null,
                )
            }
            RadioRoster(entries, mode, sent, echo)
        }
    }
}
