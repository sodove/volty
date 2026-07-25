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
        while (buf.isNotEmpty()) {
            when (val attempt = tryParseAt(0)) {
                is FrameAttempt.Complete -> {
                    out += attempt.payload
                    buf = buf.copyOfRange(attempt.total, buf.size)
                }
                is FrameAttempt.NotAFrame, is FrameAttempt.Invalid -> {
                    buf = buf.copyOfRange(1, buf.size) // false start; resync one byte
                }
                is FrameAttempt.Incomplete -> {
                    // Not enough bytes yet to check this candidate's CRC/stop. This is
                    // normal when a genuine frame is still arriving over more BLE
                    // chunks — but the header/length here can just as easily be noise
                    // picked up mid-resync (e.g. a stray 0x02 inside a corrupted CRC)
                    // that happens to claim an implausible length, which would
                    // otherwise wedge us here forever "waiting" for bytes that will
                    // never come while a real, already-fully-buffered frame sits
                    // right after it. Only actually wait if nothing later in the
                    // buffer we already have is a complete, verified frame; otherwise
                    // this candidate was a false positive, so jump straight past it.
                    //
                    // findEarliestVerifiedFrame does one forward scan of whatever is
                    // currently buffered and, on a hit, we slice off everything before
                    // the match in a single copyOfRange — not one byte at a time. That
                    // keeps this whole branch amortized O(n) per append() call: each
                    // contiguous "looks-like-a-header-but-isn't" stretch is scanned at
                    // most once, however many false candidates it contains, instead of
                    // re-scanning the remaining buffer on every single byte dropped
                    // (which would be O(n^2) — see task-1-report.md, fix round 1,
                    // finding 2, for the adversarial input that made this matter).
                    val q = findEarliestVerifiedFrame(1)
                    if (q == null) break // genuinely need more data
                    buf = buf.copyOfRange(q, buf.size)
                }
            }
        }
        return out
    }

    /** Position of the earliest complete, CRC- and stop-verified frame at or after [from], or null if none. */
    private fun findEarliestVerifiedFrame(from: Int): Int? {
        var pos = from
        while (pos < buf.size) {
            if (tryParseAt(pos) is FrameAttempt.Complete) return pos
            pos++
        }
        return null
    }

    /**
     * Single source of truth for the framing rules (header/length/CRC/stop),
     * shared by [append] and [findEarliestVerifiedFrame] so the two can never
     * silently disagree about what counts as a valid frame.
     */
    private fun tryParseAt(pos: Int): FrameAttempt {
        val start = buf[pos].toInt() and 0xFF
        val headerLen = when (start) { 0x02 -> 2; 0x03 -> 3; else -> 0 }
        if (headerLen == 0) return FrameAttempt.NotAFrame
        if (buf.size - pos < headerLen) return FrameAttempt.Incomplete
        val payloadLen = if (start == 0x02) buf[pos + 1].toInt() and 0xFF
                         else ((buf[pos + 1].toInt() and 0xFF) shl 8) or (buf[pos + 2].toInt() and 0xFF)
        val total = headerLen + payloadLen + 3 // +crc(2)+stop(1)
        if (payloadLen == 0 || total > maxBuffer) return FrameAttempt.NotAFrame
        if (buf.size - pos < total) return FrameAttempt.Incomplete
        val payloadStart = pos + headerLen
        val payload = buf.copyOfRange(payloadStart, payloadStart + payloadLen)
        val crcGot = ((buf[payloadStart + payloadLen].toInt() and 0xFF) shl 8) or
                      (buf[payloadStart + payloadLen + 1].toInt() and 0xFF)
        val stopOk = (buf[pos + total - 1].toInt() and 0xFF) == VescPacket.STOP
        return if (stopOk && crcGot == VescCrc.crc16(payload)) FrameAttempt.Complete(payload, total)
               else FrameAttempt.Invalid
    }

    private sealed interface FrameAttempt {
        /** Bad start byte, zero length, or a length that would overflow [maxBuffer] — never a valid frame here. */
        data object NotAFrame : FrameAttempt

        /** Header looks plausible but we don't have all the bytes buffered yet to verify it either way. */
        data object Incomplete : FrameAttempt

        /** Fully buffered candidate whose CRC and/or stop byte did not match. */
        data object Invalid : FrameAttempt

        /** Fully buffered, CRC and stop verified. */
        data class Complete(val payload: ByteArray, val total: Int) : FrameAttempt
    }
}
