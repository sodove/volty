package ru.sodovaya.volty.data.bms.vesc

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VescPacketTest {

    @Test fun crc16_xmodem_known_vector() {
        // CRC-16/XMODEM("123456789") == 0x31C3
        assertEquals(0x31C3, VescCrc.crc16("123456789".encodeToByteArray()))
    }

    @Test fun short_payload_uses_start_byte_2() {
        val f = VescPacket.frame(byteArrayOf(4))
        assertEquals(0x02, f[0].toInt())
        assertEquals(1, f[1].toInt())          // length
        assertEquals(4, f[2].toInt())          // payload
        assertEquals(0x03, f[f.size - 1].toInt()) // stop
        assertEquals(6, f.size)                // start+len+payload+crc(2)+stop
    }

    @Test fun long_payload_uses_start_byte_3_with_16bit_length() {
        val payload = ByteArray(300) { 7 }
        val f = VescPacket.frame(payload)
        assertEquals(0x03, f[0].toInt())
        assertEquals(300, ((f[1].toInt() and 0xFF) shl 8) or (f[2].toInt() and 0xFF))
        assertEquals(0x03, f[f.size - 1].toInt())
    }

    @Test fun accumulator_returns_payload_of_a_whole_frame() {
        val payload = byteArrayOf(47, 1, 2, 3)
        val out = VescFrameAccumulator().append(VescPacket.frame(payload))
        assertEquals(1, out.size)
        assertContentEquals(payload, out[0])
    }

    @Test fun accumulator_reassembles_a_frame_split_across_ble_chunks() {
        val payload = ByteArray(60) { (it % 251).toByte() }
        val frame = VescPacket.frame(payload)
        val acc = VescFrameAccumulator()
        val collected = mutableListOf<ByteArray>()
        frame.toList().chunked(20).forEach { chunk ->
            collected += acc.append(chunk.toByteArray())
        }
        assertEquals(1, collected.size)
        assertContentEquals(payload, collected[0])
    }

    @Test fun accumulator_yields_two_payloads_from_back_to_back_frames() {
        val a = byteArrayOf(4, 9); val b = byteArrayOf(47, 8, 8)
        val out = VescFrameAccumulator().append(VescPacket.frame(a) + VescPacket.frame(b))
        assertEquals(2, out.size)
        assertContentEquals(a, out[0]); assertContentEquals(b, out[1])
    }

    @Test fun bad_crc_frame_is_dropped_and_stream_resyncs() {
        val good = VescPacket.frame(byteArrayOf(4, 1))
        val bad = VescPacket.frame(byteArrayOf(4, 2)).copyOf()
        bad[bad.size - 2] = (bad[bad.size - 2].toInt() xor 0xFF).toByte() // corrupt CRC
        val out = VescFrameAccumulator().append(bad + good)
        assertEquals(1, out.size)
        assertContentEquals(byteArrayOf(4, 1), out[0])
    }

    @Test fun garbage_before_a_frame_is_skipped() {
        val out = VescFrameAccumulator().append(byteArrayOf(0x55, 0x00, 0x11) + VescPacket.frame(byteArrayOf(4)))
        assertEquals(1, out.size)
        assertTrue(out[0].contentEquals(byteArrayOf(4)))
    }
}
