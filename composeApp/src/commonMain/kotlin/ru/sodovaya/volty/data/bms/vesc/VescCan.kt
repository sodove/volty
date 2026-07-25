package ru.sodovaya.volty.data.bms.vesc

/**
 * `COMM_FORWARD_CAN` (34) / `COMM_PING_CAN` (62) payload codec — the transport
 * primitives that let one BLE link (a head-unit acting as a CAN gateway) reach
 * several CAN controllers and a hosted battery. Pure codec only: these
 * build/parse VESC *payloads*; [VescPacket] still owns the outer
 * start/length/CRC16/stop framing, and nothing here talks to a link — that is
 * Task 4's multiplexer. Pinned against
 * `docs/superpowers/specs/2026-07-24-vehicle-platform/C-multi-controller.md`
 * §10.1/§10.2, which were read out of the gateway firmware
 * (`vesc_express/src/commands.c`, `comm_can.c`) rather than guessed.
 */
object VescCan {
    const val OPCODE_FORWARD_CAN: Int = 34
    const val OPCODE_PING_CAN: Int = 62

    /**
     * Builds the `COMM_FORWARD_CAN` payload `[34, canId, inner…]`. [inner] must
     * already be a complete command payload (e.g. `[4]` for plain
     * `COMM_GET_VALUES`), **not** a framed [VescPacket] — the gateway relays
     * these bytes to the CAN node with `send=0` and the node answers as if
     * asked directly (spec §10.1, `commands.c:369-372`).
     *
     * The gateway keeps a **single** reply-target slot
     * (`send_func_can_fwd` / `rx_buffer_last_id`, `comm_can.c:93`) and passes
     * the remote node's reply back through `commands_send_packet_can_last`
     * **untouched** — no `COMM_FORWARD_CAN` re-wrap, no source-CAN-id byte
     * (`commands.c:1091-1099`). This function only builds one request; it
     * cannot enforce the invariant, but **a caller must keep exactly one
     * forward in flight**, awaiting the bare reply (matched by expected
     * opcode, with a timeout) before sending the next — a second forward
     * before the first reply races shared state on the gateway regardless of
     * what the client does (spec §10.1, reasons 1-4).
     */
    fun forwardCan(canId: Int, inner: ByteArray): ByteArray =
        byteArrayOf(OPCODE_FORWARD_CAN.toByte(), canId.toByte()) + inner

    /**
     * Parses a `COMM_PING_CAN` reply payload `[62][id][id]…` into the list of
     * CAN ids that answered the probe, in wire order. There is **no count
     * prefix and no terminator** — the number of ids comes from the frame
     * length alone (`commands.c:151-165`) — so the first byte (the echoed
     * opcode) is consumed and every remaining byte is one raw, unsigned CAN
     * id in 0..254 (255 is never probed by the gateway).
     *
     * Calling this is expensive on the gateway: the scan blocks it for
     * roughly 2.55 s (10 ms per candidate id × 255 ids, `comm_can.c:1106-1124`),
     * and a `PING_CAN` that arrives while one is already running is
     * **silently dropped with no error reply** (`commands.c:1064-1075`) — a
     * caller that retries on timeout gets silence, not a second answer.
     */
    fun parsePingCan(payload: ByteArray): List<Int> {
        if (payload.isEmpty()) return emptyList()
        return (1 until payload.size).map { payload[it].toInt() and 0xFF }
    }
}
