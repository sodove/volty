package ru.sodovaya.volty.domain.repository

/**
 * Enumerate the CAN nodes behind a **live** gateway link (`G §3` flow 4,
 * `C §10.2`).
 *
 * ## Why this is its own interface and not another [BmsRepository] member
 *
 * Every other address-keyed operation on [BmsRepository] applies to any link of
 * any kind — [BmsRepository.disconnectLink] means the same thing for a JK BMS
 * as for a head unit. This one does not: `COMM_PING_CAN` exists only on VESC's
 * wire, and a repository that never speaks VESC has no honest implementation to
 * give. Declaring it separately lets the composer state that it needs this
 * capability, and leaves the eleven `BmsRepository` fakes in `commonTest` — none
 * of which has a CAN bus — implementing nothing they cannot mean.
 *
 * `KableBmsRepository` implements both, and `appModule` binds it to both.
 *
 * ## The firmware behaviour this interface exists to respect
 *
 * A `PING_CAN` probes ids 0..254 at 10 ms each with **no early exit**, so it
 * blocks the gateway for **~2.55 s in every case**, and a second `PING_CAN`
 * arriving inside that window is **silently discarded with no error reply**
 * (`C §10.2`, read out of `commands.c:1064-1075` / `comm_can.c:1106-1124`). A
 * caller that retries on timeout gets silence, not a second answer.
 *
 * Two obligations follow, and neither can be met here:
 *
 *  - **the UI must not let a rider fire it twice** — enforced in
 *    `DefaultVehicleEditComponent`, which refuses a second call while one is in
 *    flight, because that is the layer that owns the button;
 *  - **something must be shown for the whole window** — the same component
 *    holds a `Running` state for exactly as long as this call is suspended.
 */
interface CanDiscovery {
    /**
     * Run one `COMM_PING_CAN` against the live link at [address] and return the
     * CAN ids that answered, in wire order.
     *
     * An **empty list is a real answer**: the gateway replied and nothing is on
     * its bus. Failure means the question could not be asked (or was not
     * answered) — no live link at [address], a link that does not speak VESC,
     * or silence within the reply window. The distinction matters to the
     * screen: "nothing found" and "we could not look" are different sentences.
     */
    suspend fun discoverCanIds(address: String): Result<List<Int>>
}
