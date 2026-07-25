package ru.sodovaya.volty.data.bms.vesc

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class VescCanTest {

    @Test fun forward_can_wraps_plain_get_values() {
        val out = VescCan.forwardCan(canId = 5, inner = byteArrayOf(4))
        assertContentEquals(byteArrayOf(34, 5, 4), out)
    }

    @Test fun forward_can_wraps_get_values_setup_preserving_inner_byte_order() {
        val out = VescCan.forwardCan(canId = 12, inner = byteArrayOf(47, 1, 2, 3))
        assertContentEquals(byteArrayOf(34, 12, 47, 1, 2, 3), out)
    }

    @Test fun forward_can_encodes_a_can_id_above_127_as_an_unsigned_byte() {
        val out = VescCan.forwardCan(canId = 200, inner = byteArrayOf(4))
        assertContentEquals(byteArrayOf(34, 200.toByte(), 4), out)
    }

    @Test fun ping_can_with_no_responders_is_an_empty_list() {
        assertEquals(emptyList(), VescCan.parsePingCan(byteArrayOf(62)))
    }

    @Test fun ping_can_completely_empty_payload_is_an_empty_list() {
        assertEquals(emptyList(), VescCan.parsePingCan(byteArrayOf()))
    }

    @Test fun ping_can_single_responder() {
        assertEquals(listOf(5), VescCan.parsePingCan(byteArrayOf(62, 5)))
    }

    /**
     * Byte 0 is the echoed opcode (62) and must be dropped, not counted as a
     * response. Byte 3 is a real responding id that happens to equal the opcode
     * value (62) — it must still surface, in position. A codec that forgets to
     * drop the opcode returns four ids instead of three; one that drops the
     * *last* byte instead of the first also happens to return three ids here,
     * but in the wrong order/values, so this single assertion catches both
     * mistakes.
     */
    @Test fun ping_can_several_ids_stay_in_wire_order_and_opcode_is_consumed_not_returned() {
        val ids = VescCan.parsePingCan(byteArrayOf(62, 5, 12, 62))
        assertEquals(listOf(5, 12, 62), ids)
    }

    @Test fun ping_can_ids_above_127_decode_as_unsigned() {
        val ids = VescCan.parsePingCan(byteArrayOf(62, 200.toByte(), 254.toByte()))
        assertEquals(listOf(200, 254), ids)
    }
}
