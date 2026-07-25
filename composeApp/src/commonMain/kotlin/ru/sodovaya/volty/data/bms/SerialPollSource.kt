package ru.sodovaya.volty.data.bms

/**
 * Optional capability a [BmsProtocol] MAY also implement — the transport twin
 * of [MotionSource] — when its poll traffic is a **request/reply dialogue**
 * rather than a fire-and-forget burst.
 *
 * [BmsProtocol.pollCommands] describes a fixed list of writes a session pushes
 * out every [BmsProtocol.pollIntervalMs] without ever looking at what came
 * back. That is right for every BMS volty talks to directly: each command has
 * its own opcode, replies are self-identifying, and losing one costs a cycle.
 *
 * It is **wrong for a CAN gateway**. A `COMM_FORWARD_CAN` reply comes back
 * BARE — the gateway relays the remote node's answer untouched, with no
 * wrapper and no source-CAN-id byte — and the gateway holds a *single*
 * reply-target slot (`send_func_can_fwd` / `rx_buffer_last_id`). Two forwards
 * in flight race state on the gateway itself, and two replies to the same
 * inner opcode are byte-identical, so only ARRIVAL ORDER can tell them apart.
 * A protocol in that position must own its own loop: send one request, await
 * its reply, only then send the next. See
 * `docs/superpowers/specs/2026-07-24-vehicle-platform/C-multi-controller.md`
 * §10.1 and [ru.sodovaya.volty.data.bms.vesc.VescCan.forwardCan].
 *
 * A protocol implementing this is driven ONLY through [runPollLoop];
 * `ConnectionSession` never reads its [BmsProtocol.pollCommands]. Existing
 * protocols implement nothing here and keep the fire-and-forget path
 * bit-for-bit.
 */
interface SerialPollSource {

    /**
     * Run this protocol's poll dialogue until the calling coroutine is
     * cancelled. [send] writes ONE fully-framed command to the link and must
     * be called from this coroutine only — the serialisation this interface
     * exists for is the implementation's own sequencing, nothing outside can
     * restore it.
     *
     * Implementations must not let a silent peer wedge the loop: every request
     * needs a timeout after which the source is skipped for this cycle. They
     * must also survive [send] throwing (a link dropping mid-cycle) without
     * spinning — the session's watchdog, not this loop, decides when a quiet
     * link is dead.
     */
    suspend fun runPollLoop(send: suspend (ByteArray) -> Unit)
}
