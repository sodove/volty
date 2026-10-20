package ru.sodovaya.volty.data.radio

import io.github.jaredmdobson.concentus.OpusApplication
import io.github.jaredmdobson.concentus.OpusDecoder
import io.github.jaredmdobson.concentus.OpusEncoder
import ru.sodovaya.volty.domain.radio.RADIO_FRAME_SAMPLES
import ru.sodovaya.volty.domain.radio.RadioMode

private const val RADIO_SAMPLE_RATE = 16_000
private const val OPUS_COMPLEXITY = 5
private const val OPUS_EXPECTED_LOSS_PERCENT = 20
private const val OPUS_MAX_PACKET_BYTES = 400

/** Stateful: one instance per stream, not thread-safe. */
interface RadioFrameEncoder {
    /** Encodes one 320-sample frame into a wire payload. */
    fun encode(pcm: ShortArray): ByteArray
}

/** Stateful: one instance per stream, not thread-safe. */
interface RadioFrameDecoder {
    /** [payload] null means the frame was lost: conceal (PLC) instead of throwing. */
    fun decode(payload: ByteArray?): ShortArray
}

object RadioFrameCodecs {
    fun encoder(mode: RadioMode): RadioFrameEncoder {
        val bitrate = mode.opusBitrate ?: return PcmEncoder
        return OpusFrameEncoder(bitrate, mode.fec)
    }

    fun decoder(mode: RadioMode): RadioFrameDecoder =
        if (mode.opusBitrate == null) PcmDecoder else OpusFrameDecoder()
}

private object PcmEncoder : RadioFrameEncoder {
    override fun encode(pcm: ShortArray): ByteArray {
        require(pcm.size == RADIO_FRAME_SAMPLES) { "frame must be $RADIO_FRAME_SAMPLES samples" }
        val out = ByteArray(pcm.size * 2)
        for (i in pcm.indices) {
            val v = pcm[i].toInt()
            out[2 * i] = v.toByte()
            out[2 * i + 1] = (v shr 8).toByte()
        }
        return out
    }
}

private object PcmDecoder : RadioFrameDecoder {
    override fun decode(payload: ByteArray?): ShortArray {
        val out = ShortArray(RADIO_FRAME_SAMPLES)
        // lost frame, or a wrong-length datagram treated as lost: silence
        if (payload == null || payload.size != RADIO_FRAME_SAMPLES * 2) return out
        for (i in 0 until RADIO_FRAME_SAMPLES) {
            out[i] = ((payload[2 * i].toInt() and 0xFF) or (payload[2 * i + 1].toInt() shl 8)).toShort()
        }
        return out
    }
}

private class OpusFrameEncoder(bitrate: Int, fec: Boolean) : RadioFrameEncoder {
    private val enc = OpusEncoder(RADIO_SAMPLE_RATE, 1, OpusApplication.OPUS_APPLICATION_VOIP).apply {
        setBitrate(bitrate)
        setComplexity(OPUS_COMPLEXITY)
        setUseInbandFEC(fec)
        setPacketLossPercent(if (fec) OPUS_EXPECTED_LOSS_PERCENT else 0)
    }
    private val buf = ByteArray(OPUS_MAX_PACKET_BYTES)

    override fun encode(pcm: ShortArray): ByteArray {
        require(pcm.size == RADIO_FRAME_SAMPLES) { "frame must be $RADIO_FRAME_SAMPLES samples" }
        val n = enc.encode(pcm, 0, RADIO_FRAME_SAMPLES, buf, 0, buf.size)
        return buf.copyOf(n)
    }
}

private class OpusFrameDecoder : RadioFrameDecoder {
    private val dec = OpusDecoder(RADIO_SAMPLE_RATE, 1)

    // PLC only: a null payload asks Opus to extrapolate one frame. FEC recovery
    // (decodeFEC on the next packet) needs lookahead this API does not expose.
    override fun decode(payload: ByteArray?): ShortArray {
        val out = ShortArray(RADIO_FRAME_SAMPLES)
        if (payload != null) {
            try {
                dec.decode(payload, 0, payload.size, out, 0, RADIO_FRAME_SAMPLES, false)
                return out
            } catch (e: Exception) {
                // malformed datagram: treat as lost, never kill the audio thread
            }
        }
        out.fill(0)
        dec.decode(null, 0, 0, out, 0, RADIO_FRAME_SAMPLES, false)
        return out
    }
}
