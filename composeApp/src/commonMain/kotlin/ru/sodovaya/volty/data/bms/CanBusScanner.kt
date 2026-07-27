package ru.sodovaya.volty.data.bms

/**
 * A protocol that can enumerate the CAN nodes behind its own link — the wire
 * half of [ru.sodovaya.volty.domain.repository.CanDiscovery].
 *
 * Implemented by the two VESC protocols, and by nothing else: `COMM_PING_CAN`
 * is a VESC command, and a JK BMS has no bus to scan. `ConnectionSession` asks
 * `protocol as? CanBusScanner`, so a protocol that does not implement this is
 * simply not scannable rather than being enumerated anywhere.
 */
interface CanBusScanner {

    /**
     * Put **one** `COMM_PING_CAN` on the wire and return the ids that answered,
     * or null when none arrived inside [REPLY_TIMEOUT_MS].
     *
     * An empty list is a real answer (the gateway replied, its bus is empty);
     * null is silence. `PING_CAN` is never wrapped in `FORWARD_CAN` — it asks
     * the endpoint we are connected to, which is the only node that knows the
     * bus.
     *
     * [send] is the link's write path. `VescGatewayProtocol` deliberately
     * declines to use it — see its override for why the only coroutine allowed
     * to write on a gateway link is its own poll loop.
     */
    suspend fun scanCanBus(send: suspend (ByteArray) -> Unit): List<Int>?

    companion object {
        /**
         * How long to wait for the `[62][id]…` reply.
         *
         * The firmware scan is **~2.55 s in essentially every case** — 255
         * candidate ids × a 10 ms PONG wait, with no early exit (`C §10.2`) —
         * so anything under about 3 s would time out on a perfectly healthy
         * bus. VESC Tool itself allows 5 s.
         *
         * We stop short of VESC Tool's 5 s because of a constraint it does not
         * have: `BleConfig.staleSampleMs` tears a link down after **5 s**
         * without a sample, and a scan holds the poll loop for its whole
         * duration. 3.5 s leaves the successful case (~2.6 s) a second of
         * headroom and keeps a *failed* scan — 3.5 s plus the loop's own
         * late-reply guard — inside the watchdog's budget on a link whose other
         * sources are answering.
         *
         * **Stated rather than hidden:** on a link where the scan times out AND
         * two or more other sources are simultaneously silent, the sum can still
         * exceed 5 s and the link reconnects. That costs the scan result and a
         * reconnect, not data; making it impossible would mean either a scan
         * window too short for the firmware or a watchdog too slack to catch a
         * dead head unit.
         */
        const val REPLY_TIMEOUT_MS: Long = 3_500L
    }
}
