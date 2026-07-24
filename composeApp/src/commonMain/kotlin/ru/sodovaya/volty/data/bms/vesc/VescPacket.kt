package ru.sodovaya.volty.data.bms.vesc

/**
 * VESC packet framing:
 *   [start][length][payload...][crc16 BE][stop=0x03]
 * start 0x02 → 1-byte length (payload <= 255); 0x03 → 2-byte big-endian length.
 * (Firmware also defines 0x04 / 3-byte length for very large payloads; we never
 * SEND one, and the accumulator skips a start byte it cannot parse rather than
 * pretending to understand it.)
 */
object VescPacket {
    const val STOP: Int = 0x03

    fun frame(payload: ByteArray): ByteArray {
        val header = if (payload.size <= 255) {
            byteArrayOf(0x02, payload.size.toByte())
        } else {
            byteArrayOf(0x03, ((payload.size shr 8) and 0xFF).toByte(), (payload.size and 0xFF).toByte())
        }
        val crc = VescCrc.crc16(payload)
        return header + payload +
            byteArrayOf(((crc shr 8) and 0xFF).toByte(), (crc and 0xFF).toByte(), STOP.toByte())
    }
}

/**
 * Reassembles VESC frames from BLE notification chunks (a payload routinely
 * spans several 20-byte MTU writes) and hands back only complete, CRC-verified
 * payloads. Mirrors the role [ru.sodovaya.volty.data.bms.ByteArrayAccumulator]
 * plays for the byte-oriented BMS protocols; not thread-safe by design — it
 * lives inside one session's single observe coroutine.
 */
class VescFrameAccumulator(private val maxBuffer: Int = 4096) {

    private var buf = ByteArray(0)

    fun reset() { buf = ByteArray(0) }

    fun append(chunk: ByteArray): List<ByteArray> {
        buf = if (buf.isEmpty()) chunk.copyOf() else buf + chunk
        if (buf.size > maxBuffer) buf = buf.copyOfRange(buf.size - maxBuffer, buf.size)

        val out = mutableListOf<ByteArray>()
        while (true) {
            if (buf.isEmpty()) break
            val start = buf[0].toInt() and 0xFF
            val headerLen = when (start) { 0x02 -> 2; 0x03 -> 3; else -> 0 }
            if (headerLen == 0) { buf = buf.copyOfRange(1, buf.size); continue } // resync
            if (buf.size < headerLen) break                                       // need more
            val payloadLen = if (start == 0x02) buf[1].toInt() and 0xFF
                             else ((buf[1].toInt() and 0xFF) shl 8) or (buf[2].toInt() and 0xFF)
            val total = headerLen + payloadLen + 3                                // +crc(2)+stop(1)
            if (payloadLen == 0 || total > maxBuffer) { buf = buf.copyOfRange(1, buf.size); continue }
            if (buf.size < total) {
                // Not enough bytes yet to check this candidate's CRC/stop. This is
                // normal when a genuine frame is still arriving over more BLE
                // chunks — but [start]/[payloadLen] can just as easily be noise
                // picked up mid-resync (e.g. a stray 0x02 inside a corrupted CRC)
                // that happens to claim an implausible length, which would
                // otherwise wedge us here forever "waiting" for bytes that will
                // never come while a real, already-fully-buffered frame sits
                // right after it. Only actually wait if nothing later in the
                // buffer we already have is a complete, verified frame; otherwise
                // this candidate was a false positive, so drop it and resync.
                if (!existsVerifiedFrameAfter(1)) break
                buf = buf.copyOfRange(1, buf.size)
                continue
            }
            val payload = buf.copyOfRange(headerLen, headerLen + payloadLen)
            val crcGot = ((buf[headerLen + payloadLen].toInt() and 0xFF) shl 8) or
                          (buf[headerLen + payloadLen + 1].toInt() and 0xFF)
            val stopOk = (buf[total - 1].toInt() and 0xFF) == VescPacket.STOP
            if (stopOk && crcGot == VescCrc.crc16(payload)) {
                out += payload
                buf = buf.copyOfRange(total, buf.size)
            } else {
                buf = buf.copyOfRange(1, buf.size)                                // false start; resync
            }
        }
        return out
    }

    /** True if a complete, CRC- and stop-verified frame starts at or after [from] within the current buffer. */
    private fun existsVerifiedFrameAfter(from: Int): Boolean {
        var pos = from
        while (pos < buf.size) {
            val start = buf[pos].toInt() and 0xFF
            val headerLen = when (start) { 0x02 -> 2; 0x03 -> 3; else -> 0 }
            if (headerLen == 0 || buf.size - pos < headerLen) { pos++; continue }
            val payloadLen = if (start == 0x02) buf[pos + 1].toInt() and 0xFF
                             else ((buf[pos + 1].toInt() and 0xFF) shl 8) or (buf[pos + 2].toInt() and 0xFF)
            val total = headerLen + payloadLen + 3
            if (payloadLen == 0 || total > maxBuffer || buf.size - pos < total) { pos++; continue }
            val payloadStart = pos + headerLen
            val payload = buf.copyOfRange(payloadStart, payloadStart + payloadLen)
            val crcGot = ((buf[payloadStart + payloadLen].toInt() and 0xFF) shl 8) or
                          (buf[payloadStart + payloadLen + 1].toInt() and 0xFF)
            val stopOk = (buf[pos + total - 1].toInt() and 0xFF) == VescPacket.STOP
            if (stopOk && crcGot == VescCrc.crc16(payload)) return true
            pos++
        }
        return false
    }
}
