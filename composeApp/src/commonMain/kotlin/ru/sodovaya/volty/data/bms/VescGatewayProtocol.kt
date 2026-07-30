package ru.sodovaya.volty.data.bms

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import ru.sodovaya.volty.data.bms.vesc.VescBmsValues
import ru.sodovaya.volty.data.bms.vesc.VescCan
import ru.sodovaya.volty.data.bms.vesc.VescFrameAccumulator
import ru.sodovaya.volty.data.bms.vesc.VescPacket
import ru.sodovaya.volty.data.bms.vesc.VescValues
import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.MotorConfig
import ru.sodovaya.volty.domain.model.SpeedSource

/**
 * One source behind a gateway link, as the multiplexer needs to address it.
 *
 * [globalIndex] is the VEHICLE-global index the decode belongs to, taken off
 * the link's `LinkSpec` — never inferred from arrival order. [canId] is the CAN
 * id the request must be forwarded to, or **null for a source the gateway
 * answers itself** (its hosted battery, or its own controller half).
 *
 * Deliberately a plain `data/bms` type rather than `data/ble`'s `OwnedSource`:
 * `data/ble` already depends on `data/bms` (it builds every protocol), so
 * importing `LinkSpec` here would close that loop. The translation lives in
 * `ControllerProtocols.kt`, next to the decision that picks this protocol.
 */
data class GatewaySource(
    val globalIndex: Int,
    val canId: Int? = null,
    /** Wheel geometry for [VescValues.decodeValues]'s derived speed. Controllers only. */
    val motor: MotorConfig = MotorConfig()
)

/**
 * The multiplexer: **one BLE link, several sources**. A VESC Express head unit
 * acting as a CAN gateway carries the uBox controllers on its CAN bus plus the
 * battery it hosts itself, and this decodes all of them over that single link.
 *
 * It supersedes [VescProtocol] for exactly the links `LinkSpec.isGatewayLink`
 * identifies; the choice is made once, in `controllerMotionProtocol`.
 *
 * ## The loop is strictly serial — this is the whole design
 *
 * `COMM_FORWARD_CAN` replies come back **bare**: the gateway relays the remote
 * node's answer untouched, with no wrapper and no source-CAN-id byte
 * (`commands.c:1091-1099`). It keeps ONE `send_func_can_fwd` and ONE
 * `rx_buffer_last_id`, so a second forward sent before the first reply lands
 * races state **on the gateway**, whatever the client does — and two replies to
 * the same inner opcode are byte-identical, so only ARRIVAL ORDER can tell them
 * apart. Spec §10.1, reasons 1-4.
 *
 * Hence [runPollLoop]: target → framed request → **await the bare reply,
 * matched by expected opcode with a timeout** → only then the next. There is no
 * pipelining and no queue; [pending] holds at most one expectation at a time
 * and it is set, awaited and cleared by one coroutine in one straight line.
 * That single-coroutine sequencing IS the guarantee — see
 * `VescGatewayProtocolTest.at_most_one_request_is_outstanding_at_any_instant`,
 * which fails the moment anyone "optimises" this into parallel requests.
 *
 * ## What one cycle asks for (spec §3 + §9.3)
 *
 * 1. `GET_VALUES_SETUP` per owned controller — the only frame carrying a
 *    controller-computed ground speed, odometer and battery level. **Per
 *    controller, not to a nominated "primary"** — see below.
 * 2. `GET_VALUES` per owned controller — per-unit duty, currents, rpm, temps,
 *    fault.
 * 3. `BMS_GET_VALUES` per owned battery, **unwrapped** when it is hosted (the
 *    gateway answers it itself), forwarded when it sits on the CAN bus.
 *
 * ## Why SETUP is asked of EVERY controller (field report 2026-07-30)
 *
 * This used to send exactly one SETUP, to `controllers.firstOrNull()`, on the
 * argument that every extra forward costs a full serialised round-trip. On the
 * rider's scooter controller 0 is the **head unit itself** — VESC Express on an
 * ESP32-C6, a CAN bridge with no motor — and its firmware does not handle
 * opcode 47 at all: the switch in `commands.c` falls through to
 * `default: break;` and no reply frame is ever built. So the one frame carrying
 * speed, trip, odometer and battery level was addressed to the only node on the
 * vehicle that cannot answer it, and all four read 0 however well the real uBox
 * answered everything else.
 *
 * Re-electing a better primary was considered and rejected: choosing one needs a
 * liveness signal this protocol does not have (silence is indistinguishable from
 * a slow node, and `GET_VALUES` answering says nothing about opcode 47), and any
 * election leaves the same class of bug for the next topology. Asking everybody
 * has no such failure mode — whichever nodes can answer, do, and the ones that
 * cannot simply cost their timeout.
 *
 * The traffic this adds is one request per controller per cycle: the plan grows
 * from `1 + controllers + packs` to `2 x controllers + packs`. That growth is
 * bounded by [checkSilenceBudget], which is computed from the plan size and
 * therefore already accounts for it — a plan that no longer fits is refused at
 * construction rather than discovered on the road.
 *
 * Each answering controller now carries its own overlay, which is correct at the
 * fold: `MotionAggregator` takes `maxOf` for speed/odometer/trip and averages
 * the battery level over the controllers that report one, so N controllers
 * reporting the same setup-wide scalars aggregate to that scalar rather than to
 * N times it. (Only the per-unit numbers are summed, and those still come from
 * `GET_VALUES` alone — see the OVERLAY section below.)
 *
 * A source that does not answer within [replyTimeoutMs] is skipped for this
 * cycle and the loop moves on — it can never wedge the cycle, which is how a
 * sleeping uBox would otherwise take the whole dashboard down with it. No
 * reply means no sample, which the existing per-controller staleness sweep in
 * `VehicleConnection.submitMotion` turns into "offline" while the hosted
 * battery keeps reporting; a uBox that wakes lands back in its slot (or
 * materialises its latent one) through the machinery already there. Nothing
 * here duplicates either.
 *
 * ## Why the SETUP frame is an OVERLAY and not a sample of its own
 *
 * `COMM_GET_VALUES_SETUP` reports the **setup**, not the unit: VESC's
 * `mc_interface_get_setup_values()` sums current, power and amp-hours across
 * every CAN node it has heard from, and divides the tachometer by the number of
 * VESCs. Publishing that decode as the answering controller's `ControllerData`
 * would hand `MotionAggregator` — which SUMS currents across controllers — a
 * figure that already includes the other uBox, and the dashboard would read
 * roughly double the real current. So only the vehicle-level scalars it is
 * asked for are taken from it (speed, odometer, trip, battery level); every
 * per-unit number stays the one `GET_VALUES` decoded for that unit. Asking
 * every controller for SETUP makes that separation MORE load-bearing, not
 * less: two controllers each publishing a CAN-wide current sum would be
 * summed a second time by the fold.
 *
 * ## `ah_cnt` stays unmapped, deliberately
 *
 * The battery frame's amp-hour counter means **remaining** Ah on the user's head
 * unit (`ant_bms.c:663` overwrites it with the pack's remaining capacity) and
 * **cumulative** Ah on stock VESC — the two destinations being
 * `BmsData.charge` and `BmsData.cycleCapacityAh` respectively. Nothing on the
 * wire says which firmware answered: an ANT-backed gateway zeroes every tail
 * field that might have identified it (`data_version`, `status`, the totals
 * block — spec §10.5), so "it looks like nyxdash" is not evidence. Guessing
 * either way produces a number that is plausible and wrong — a lifetime total
 * rendered as remaining range, or a remaining charge rendered as pack wear — so
 * [ru.sodovaya.volty.data.bms.vesc.VescBmsFrame.toBmsData] maps it to neither
 * and this protocol publishes that projection unchanged. The raw value stays on
 * the frame for a caller that knows its gateway; teaching it which one it is
 * belongs to the composer (a per-vehicle setting), not to a decoder guessing.
 * The same applies to the per-cell balancing flags: decoded, but with no
 * `BmsData` field to carry them, left unmapped rather than approximated.
 */
class VescGatewayProtocol(
    /** Owned controllers, in the link's own order — index i IS local index i. */
    private val controllers: List<GatewaySource>,
    /** Owned batteries, in the link's own order — index i IS local index i. */
    private val packs: List<GatewaySource> = emptyList(),
    /**
     * Gap between cycles. Shorter than [VescProtocol]'s 150 ms on purpose: a
     * gateway cycle already costs one serialised round-trip per source, so the
     * pacing comes from the dialogue itself and this is only the headroom left
     * for the link's other traffic.
     */
    override val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
    /**
     * How long one request waits for its bare reply. A dead uBox must cost
     * cycle time, never the link — the bound that makes that true is stated
     * and enforced by [checkSilenceBudget], which this and
     * [lateReplyGuardMs] are inputs to.
     */
    private val replyTimeoutMs: Long = DEFAULT_REPLY_TIMEOUT_MS,
    /**
     * Quiet window after a timeout before the next request is armed.
     *
     * The wire has no transaction id, so a reply that arrives just after we
     * gave up is indistinguishable from the NEXT request's reply when both
     * carry the same inner opcode — and `GET_VALUES` to two uBoxes is exactly
     * that case. Nothing is armed during this window, so a late reply is
     * dropped instead of being credited to the wrong controller. A mitigation,
     * not a cure: a reply later than this is still unattributable, which is a
     * property of the protocol (§10.1, reason 2) and not of this code.
     */
    private val lateReplyGuardMs: Long = DEFAULT_LATE_REPLY_GUARD_MS
) : BmsProtocol(), MotionSource, SerialPollSource, CanBusScanner {

    companion object {
        const val DEFAULT_POLL_INTERVAL_MS: Long = 50L
        const val DEFAULT_REPLY_TIMEOUT_MS: Long = 400L
        const val DEFAULT_LATE_REPLY_GUARD_MS: Long = 100L

        /**
         * The budget one link may spend on a run of silent sources before the
         * session's stale-sample watchdog tears it down — a restatement of
         * `BleConfig.staleSampleMs`, NOT an import: `data/ble` depends on
         * `data/bms` (it builds every protocol), so reaching the other way
         * would close the loop (see [GatewaySource]'s KDoc). Kept as a named
         * constant so [checkSilenceBudget] can name what it is protecting.
         */
        const val WATCHDOG_SILENCE_BUDGET_MS: Long = 5_000L

        /**
         * How long [scanCanBus] waits for the poll loop to pick its parked
         * request up **and** finish it.
         *
         * Two costs, not one: the loop only looks at the request between
         * cycles, so the caller first waits out whatever is left of the current
         * cycle, and only then the reply window
         * ([CanBusScanner.REPLY_TIMEOUT_MS]). A cycle whose sources all answer
         * is tens of milliseconds; a cycle where they are all silent is capped
         * by [checkSilenceBudget] below [WATCHDOG_SILENCE_BUDGET_MS]. So the
         * sum is bounded by that budget plus the reply window, which is exactly
         * this.
         *
         * It exists at all because a link torn down mid-scan never services the
         * request: without an outer bound the composer's spinner would run
         * forever on a connection that has gone away.
         */
        const val SCAN_WAIT_MS: Long = WATCHDOG_SILENCE_BUDGET_MS + CanBusScanner.REPLY_TIMEOUT_MS

        /**
         * The real timeout-budget invariant of this loop, checked rather than
         * assumed — the task-4 report stated it wrongly ("a whole cycle of
         * silent sources must fit inside the watchdog"), and a whole cycle of
         * silence SHOULD trip the watchdog: nothing behind the gateway is
         * answering.
         *
         * What must fit is the longest run of silence that still leaves
         * somebody reporting, because [ConnectionSession]'s watchdog measures
         * the gap between two SAMPLES, not the length of a cycle. The worst
         * such run is every REQUEST but one, each costing a full
         * [replyTimeoutMs] + [lateReplyGuardMs], plus the inter-cycle
         * [pollIntervalMs] when the run straddles the cycle boundary.
         *
         * [planSize] is the request count, `2 × controllers + packs` — two per
         * controller since SETUP is asked of each of them (class KDoc). The
         * rider's scooter (head unit + one uBox + the hosted battery) is 5
         * requests: 4 × 500 + 50 = 2050 ms against a 5 s budget. It grows
         * LINEARLY with the plan, so a vehicle with enough sources behind one
         * head unit would silently reconnect forever while most of its CAN bus
         * was healthy. Failing loudly at construction is the honest outcome: a
         * plan that cannot meet this bound does not work, and the message says
         * which knob to turn.
         */
        internal fun checkSilenceBudget(
            planSize: Int,
            replyTimeoutMs: Long,
            lateReplyGuardMs: Long,
            pollIntervalMs: Long
        ) {
            if (planSize < 2) return
            val worstSilentRunMs =
                (planSize - 1) * (replyTimeoutMs + lateReplyGuardMs) + pollIntervalMs
            require(worstSilentRunMs < WATCHDOG_SILENCE_BUDGET_MS) {
                "A gateway plan of $planSize requests can go $worstSilentRunMs ms between samples " +
                    "when all but one are silent, which exceeds the ${WATCHDOG_SILENCE_BUDGET_MS} ms " +
                    "stale-sample budget — the link would be torn down while most of the CAN bus is " +
                    "healthy. Lower replyTimeoutMs/lateReplyGuardMs, split the sources across links, " +
                    "or raise BleConfig.staleSampleMs (and this constant with it)."
            }
        }
    }

    override val uuids = BmsUuids(
        serviceUuid = VescProtocol.NUS_SERVICE,
        notifyCharUuid = VescProtocol.NUS_NOTIFY,
        writeCharUuid = VescProtocol.NUS_WRITE
    )

    private val accumulator = VescFrameAccumulator()

    // ---- decode state -------------------------------------------------
    // Keyed by GLOBAL index throughout: a reply is routed by the target the
    // request was built for, never by its position in the cycle.
    //
    // Replaced wholesale (copy-on-write) rather than mutated, for two reasons:
    // @Volatile then publishes them safely to the session's observe coroutine,
    // and MotionSampleGate/PackSampleGate discriminate on instance IDENTITY —
    // an untouched source keeps its instance and correctly reads as "no new
    // decode", while the one that just answered gets a fresh one.

    /** Last per-unit `GET_VALUES` decode, dropped as soon as its unit goes quiet. */
    @Volatile private var perUnit: Map<Int, ControllerData> = emptyMap()

    /** What is published to [latestMotion] — per-unit data plus that unit's [overlays] entry. */
    @Volatile private var motion: Map<Int, ControllerData> = emptyMap()

    @Volatile private var packData: Map<Int, BmsData> = emptyMap()

    /** The vehicle-level scalars one controller's SETUP frame contributes. */
    private data class SetupOverlay(
        val speedKmh: Float,
        val speedSource: SpeedSource,
        val odometerKm: Float,
        val tripKm: Float,
        val batteryLevelFraction: Float?
    )

    /**
     * Per controller, keyed by GLOBAL index like every other map here: the
     * scalars that controller's own SETUP reply carried, or no entry at all
     * when it did not answer one. Was a single vehicle-wide field back when a
     * single nominated controller was asked; a head unit that answers nothing
     * is exactly the case that made "one controller speaks for the vehicle"
     * wrong (see the class KDoc).
     */
    @Volatile private var overlays: Map<Int, SetupOverlay> = emptyMap()

    /**
     * Odometer at this connection's first SETUP frame **from that controller**,
     * so `tripKm` is distance since the link came up rather than the ESC's
     * since-boot counter — the same session-baseline rule (and the same
     * "absolute counter only" reason) as [VescProtocol.tripBaselineKm].
     * Cleared by [reset].
     *
     * Per controller, not vehicle-wide: two nodes' tachometers are two
     * counters, and subtracting one's baseline from the other's reading is
     * arithmetic on unrelated numbers. It reads as a plausible trip, which is
     * the worst kind of wrong.
     */
    @Volatile private var tripBaselineKm: Map<Int, Float> = emptyMap()

    // ---- the request plan, built once ---------------------------------

    /**
     * One request and everything needed to attribute its reply. The routing
     * target is baked in at BUILD time, from the [GatewaySource] the request
     * was made for — so "which source does this reply belong to" is answered by
     * what we asked for, which is the only thing the wire leaves us (§10.1).
     */
    private class Request(
        val frame: ByteArray,
        val expectedOpcode: Int,
        /** Apply a matching reply payload. */
        val consume: (ByteArray) -> Unit,
        /** Forget whatever this request last produced, because it went unanswered. */
        val onSilence: () -> Unit
    )

    /**
     * Every SETUP first, then every `GET_VALUES`, then the batteries.
     *
     * The grouping is not cosmetic. Two things depend on it:
     *
     * 1. **A controller's SETUP always precedes its own `GET_VALUES` within the
     *    cycle**, which is what lets [setupRequest]'s `onSilence` drop the
     *    overlay entry and leave republishing to the `GET_VALUES` that follows.
     *    Reverse the two and a controller whose SETUP has just gone quiet keeps
     *    publishing last cycle's reported speed for a whole further cycle.
     * 2. **The two `GET_VALUES` requests stay adjacent.** Interleaving SETUP
     *    between them would leave `a reply that arrives after its timeout is
     *    not credited to the next controller` — the regression test that pins
     *    [lateReplyGuardMs] — passing by opcode mismatch rather than by the
     *    guard, i.e. green under the very mutant it exists to kill.
     *
     * Note what the grouping COSTS, since it is not free: the two SETUP
     * requests are now adjacent too, and two SETUP replies are as
     * byte-identical on the wire as two `GET_VALUES` replies are. So this plan
     * has **two** unattributable-late-reply pairs where the brief's interleaved
     * order would have had none — the guard covers both, and both are pinned
     * (`… is not credited to the next controller` for `GET_VALUES`,
     * `a late SETUP reply is not credited to the next controller` for SETUP).
     * The trade is deliberate: one guard mechanism covering two known pairs
     * beats an ordering whose safety comes from opcodes happening not to
     * collide, which no test can hold in place.
     */
    private val plan: List<Request> = buildList {
        controllers.forEach { add(setupRequest(it)) }
        controllers.forEach { add(valuesRequest(it)) }
        packs.forEach { add(bmsRequest(it)) }
    }

    init {
        checkSilenceBudget(plan.size, replyTimeoutMs, lateReplyGuardMs, pollIntervalMs)
    }

    private fun frameFor(canId: Int?, inner: ByteArray): ByteArray =
        VescPacket.frame(if (canId == null) inner else VescCan.forwardCan(canId, inner))

    private fun setupRequest(c: GatewaySource) = Request(
        frame = frameFor(c.canId, byteArrayOf(VescValues.OPCODE_GET_VALUES_SETUP.toByte())),
        expectedOpcode = VescValues.OPCODE_GET_VALUES_SETUP,
        consume = { payload ->
            VescValues.decodeSetupValues(payload)?.let { applySetup(c.globalIndex, it) }
        },
        // A speed this controller is no longer reporting must not stay on the
        // dashboard. Only ITS entry goes: a head unit that never answers
        // opcode 47 must not take the uBox's speed down with it, which is the
        // whole point of asking per controller.
        //
        // No republish here — the same controller's `GET_VALUES` comes later in
        // this very cycle (see [plan]) and republishes without the overlay, or
        // is itself silent and drops the sample outright. Pinned by
        // `a silent SETUP drops the reported speed instead of freezing it`.
        onSilence = { overlays = overlays - c.globalIndex }
    )

    private fun valuesRequest(c: GatewaySource) = Request(
        frame = frameFor(c.canId, byteArrayOf(VescValues.OPCODE_GET_VALUES.toByte())),
        expectedOpcode = VescValues.OPCODE_GET_VALUES,
        consume = { payload ->
            VescValues.decodeValues(payload, c.motor)?.let { applyValues(c.globalIndex, it) }
        },
        // Drop the cached per-unit decode AND what was published from it: with
        // both gone nothing can republish this unit's frozen duty/current next
        // to a live speed, so a quiet uBox produces NO sample and ages into
        // offline instead of lying.
        //
        // `motion` has to go too, not just `perUnit`. `perUnit` is the decode
        // cache the next publish reads; `motion` is what [latestMotion] hands
        // out. Dropping only the first leaves the accessor answering with the
        // quiet controller's last sample forever — harmless to the routing
        // helpers (they gate on instance identity, so an unchanged instance
        // yields no new sample) but false to every other reader, and this file
        // claims "no sample at all". Cheaper to make the claim true than to
        // leave an accessor that lies to the next caller.
        onSilence = {
            perUnit = perUnit - c.globalIndex
            motion = motion - c.globalIndex
        }
    )

    private fun bmsRequest(p: GatewaySource) = Request(
        frame = frameFor(p.canId, byteArrayOf(VescBmsValues.OPCODE_BMS_GET_VALUES.toByte())),
        expectedOpcode = VescBmsValues.OPCODE_BMS_GET_VALUES,
        consume = { payload ->
            val frame = VescBmsValues.decode(payload)
            // `can_id == 0xFF` is the firmware's "no BMS data yet" sentinel and
            // the whole frame is then the zeroed default, not a reading — the
            // decoder flags it precisely so a consumer can drop it. We are that
            // consumer: publishing it would put 0 V / 0 % on the Battery screen.
            if (frame != null && !frame.noBmsDataYet) {
                packData = packData + (p.globalIndex to frame.toBmsData())
            }
        },
        // Nothing to forget: the battery's last sample stays cached and the
        // orchestrator's staleness sweep greys it out on its own schedule,
        // exactly as for a directly-connected BMS that goes quiet.
        onSilence = {}
    )

    // ---- the serial loop ----------------------------------------------

    /** One outstanding request. There is never a second — see the class KDoc. */
    private class Pending(
        val expectedOpcode: Int,
        val consume: (ByteArray) -> Unit,
        val waiter: CompletableDeferred<Unit>
    )

    @Volatile private var pending: Pending? = null

    /**
     * A `PING_CAN` the composer has asked for, parked until the loop reaches
     * the top of a cycle. Null means no scan is outstanding.
     *
     * Completed with the id list, or with **null for silence** — the composer
     * needs to tell "the bus is empty" (an empty list) from "the head unit did
     * not answer" apart, and only the loop knows which happened.
     */
    @Volatile private var canScanRequest: CompletableDeferred<List<Int>?>? = null

    /**
     * Strictly serial round-robin, with the composer's CAN scan taking the
     * first slot of a cycle when one is parked.
     *
     * Every iteration of the inner loop performs a complete exchange before the
     * next begins; nothing here can start a second request while one is
     * outstanding, because [exchange] does not return until its own is settled.
     */
    override suspend fun runPollLoop(send: suspend (ByteArray) -> Unit) {
        // A link that owns nothing has nothing to ask for. Returning (rather
        // than looping over an empty plan) keeps this from becoming a delay-only
        // spin for a misconfigured spec.
        //
        // A scan parked against such a link is therefore never serviced, and
        // [scanCanBus]'s own [SCAN_WAIT_MS] is what stops that from hanging the
        // caller. `planLinks` cannot produce an empty gateway plan — a gateway
        // link is gateway-shaped BECAUSE it owns sources — so this is the
        // misconfiguration path, not a real one.
        if (plan.isEmpty()) return
        while (currentCoroutineContext().isActive) {
            serviceCanScan(send)
            for (request in plan) exchange(request, send)
            delay(pollIntervalMs)
        }
    }

    /**
     * The parked scan, if any, run through the ordinary [exchange] — so it is
     * one more strictly-serial request rather than a second writer.
     *
     * That is the whole reason [scanCanBus] does not simply write: on a gateway
     * link `C §10.1` allows exactly one request in flight, and this coroutine is
     * the only one that ever holds it. A `PING_CAN` written from the composer's
     * coroutine would land beside an outstanding `FORWARD_CAN`, whose reply the
     * gateway routes through a **single** `send_func_can_fwd` slot.
     *
     * The frame is unwrapped: `PING_CAN` asks the endpoint we are connected to,
     * which is the only node that knows what else is on the bus.
     */
    private suspend fun serviceCanScan(send: suspend (ByteArray) -> Unit) {
        val waiter = canScanRequest ?: return
        var ids: List<Int>? = null
        exchange(
            Request(
                frame = VescPacket.frame(byteArrayOf(VescCan.OPCODE_PING_CAN.toByte())),
                expectedOpcode = VescCan.OPCODE_PING_CAN,
                consume = { payload -> ids = VescCan.parsePingCan(payload) },
                // Nothing cached, nothing to forget — the silence IS the result,
                // and it is reported below as a null completion.
                onSilence = {}
            ),
            send,
            // The firmware blocks ~2.55 s on this one command (`C §10.2`); the
            // plan's own 400 ms would give up before it could possibly answer.
            timeoutMs = CanBusScanner.REPLY_TIMEOUT_MS
        )
        // Cleared BEFORE completing, and the order is load-bearing: completing
        // first resumes the caller — inline, if it is waiting on an unconfined
        // dispatcher, which is what a resumption inside `complete` means — and a
        // second scan parked by that caller would then be **wiped by the clear
        // that followed**, never serviced, and left hanging for the whole
        // [SCAN_WAIT_MS] on a link that is answering perfectly.
        //
        // (It also stops this cycle servicing the same scan twice, which is the
        // lesser of the two.) Pinned by `a scan parked from inside the first
        // one's completion is still serviced`.
        canScanRequest = null
        waiter.complete(ids)
    }

    /**
     * Park a `PING_CAN` for [runPollLoop] and wait for its answer.
     *
     * [send] is **deliberately ignored** — see [serviceCanScan]. The parameter
     * stays because [CanBusScanner] is implemented by [VescProtocol] too, where
     * a plain burst-polled link has no loop to hand the request to and writing
     * from the caller is the correct thing to do.
     *
     * Single-flight: a second call while one is outstanding joins the SAME
     * request rather than putting a second `PING_CAN` on the wire, which the
     * firmware would silently discard (`C §10.2`). The composer refuses the
     * second tap as well; this is the half that holds even if some other caller
     * appears.
     */
    override suspend fun scanCanBus(send: suspend (ByteArray) -> Unit): List<Int>? {
        val existing = canScanRequest
        if (existing != null) return withTimeoutOrNull(SCAN_WAIT_MS) { existing.await() }
        val waiter = CompletableDeferred<List<Int>?>()
        canScanRequest = waiter
        return try {
            withTimeoutOrNull(SCAN_WAIT_MS) { waiter.await() }
        } finally {
            // Clears an abandoned request so the NEXT scan parks a fresh one
            // instead of joining a dead deferred and waiting the window out
            // again — the same rule [VescProtocol] needs, and the reason both
            // disarms exist at all.
            //
            // Only if it is still OURS: [serviceCanScan] nulls the field the
            // moment it takes the request, and a later caller may already have
            // parked a new one there.
            if (canScanRequest === waiter) canScanRequest = null
        }
    }

    /**
     * Send one request and wait for its bare reply, or give up.
     *
     * Returns only once the request is settled — answered, timed out, or the
     * write failed — which is what makes "one in flight" structural rather than
     * a rule someone has to remember.
     */
    private suspend fun exchange(
        request: Request,
        send: suspend (ByteArray) -> Unit,
        /**
         * How long to wait for this one reply. Defaults to the loop's own
         * budget; the CAN scan overrides it because the firmware blocks ~2.55 s
         * on `PING_CAN` alone (`C §10.2`) and would never answer inside 400 ms.
         */
        timeoutMs: Long = replyTimeoutMs
    ) {
        val waiter = CompletableDeferred<Unit>()
        pending = Pending(request.expectedOpcode, request.consume, waiter)
        val answered = try {
            withTimeoutOrNull(timeoutMs) {
                send(request.frame)
                waiter.await()
            }
        } catch (e: CancellationException) {
            pending = null
            throw e
        } catch (_: Exception) {
            // The write failed — a link dropping mid-cycle, not a silent node.
            // DISARM FIRST, exactly as the timeout path below does. The request
            // never made it onto the wire, so nothing legitimate can answer it;
            // anything that lands during the pace is a late reply to an EARLIER
            // request, and an armed expectation would consume it and credit it
            // to a request that was never sent. (This is the same reasoning as
            // the quiet window below, applied to the path that used to skip it.)
            pending = null
            // Pace the loop with the budget the reply would have cost so a dead
            // link cannot spin the CPU while the session's watchdog decides.
            delay(replyTimeoutMs)
            null
        }
        if (answered != null) return
        // Timed out (or the write failed). Disarm FIRST, so a late reply lands
        // in the quiet window below and is dropped rather than credited to
        // whatever we ask next.
        pending = null
        request.onSilence()
        delay(lateReplyGuardMs)
    }

    override fun handshakeCommands(): List<ByteArray> = emptyList()

    /**
     * Empty: this protocol drives itself through [runPollLoop]. `ConnectionSession`
     * never reads this for a [SerialPollSource], and an empty list is the honest
     * answer for "there is no fixed burst to fire" if anything else ever does.
     */
    override fun pollCommands(): List<ByteArray> = emptyList()

    override val controllerCount: Int get() = controllers.size
    override val packCount: Int get() = packs.size

    /**
     * Feed a notification. A reassembled payload is consumed only when it
     * matches the ONE outstanding expectation's opcode; anything else — an
     * unsolicited frame, a reply we already gave up on, a wrong opcode — is
     * dropped. The decode happens here, synchronously, so `latestMotion` /
     * `latestData` are already updated when the session reads them on this
     * same notification.
     */
    override fun onNotification(data: ByteArray) {
        for (payload in accumulator.append(data)) {
            val p = pending ?: continue
            if (payload.isEmpty() || (payload[0].toInt() and 0xFF) != p.expectedOpcode) continue
            pending = null
            p.consume(payload)
            p.waiter.complete(Unit)
        }
    }

    private fun applyValues(global: Int, decoded: ControllerData) {
        perUnit = perUnit + (global to decoded)
        publishController(global)
    }

    private fun applySetup(global: Int, decoded: ControllerData) {
        val odometerKm = decoded.odometerKm
        val baseline = tripBaselineKm[global]
            ?: odometerKm.also { tripBaselineKm = tripBaselineKm + (global to it) }
        val overlay = SetupOverlay(
            speedKmh = decoded.speedKmh,
            speedSource = decoded.speedSource,
            odometerKm = odometerKm,
            tripKm = (odometerKm - baseline).coerceAtLeast(0f),
            batteryLevelFraction = decoded.batteryLevelFraction
        )
        overlays = overlays + (global to overlay)
        // Deliberately NOT publishing here, and this is not a micro-optimisation.
        //
        // It used to publish, back when the SETUP frame was the primary's and
        // arrived immediately before that same primary's `GET_VALUES`. Under
        // this plan every SETUP is answered before any `GET_VALUES` is even
        // sent, so `perUnit[global]` at this instant is LAST cycle's decode —
        // and [ConnectionSession] drains the routing helpers on **every
        // notification**, so a publish here is not an invisible intermediate
        // value. It is a real sample, restamped `Clock.System.now()` on the way
        // out, carrying a whole cycle's-old duty and current beside this
        // cycle's speed.
        //
        // That is the exact lie [valuesRequest]'s `onSilence` refuses to tell,
        // reached by another road: a controller whose `GET_VALUES` has died but
        // whose SETUP still answers would keep submitting samples — refreshing
        // its `lastSeen` and staying "online" with frozen per-unit numbers
        // forever. Pinned by `a SETUP reply does not resubmit last cycle's
        // per-unit numbers`, which drains per notification the way the session
        // does; every test that drains only between whole cycles is blind to it.
        //
        // The overlay reaches [motion] one round-trip later, when this
        // controller's own `GET_VALUES` lands and [publishController] folds the
        // two together — which is the only moment both halves are current.
    }

    /**
     * Publish [global]'s per-unit decode, with that controller's own setup
     * overlay folded in when it answered one. Publishes NOTHING while there is
     * no per-unit decode: the overlay describes the whole setup, so on its own
     * it is not a sample of any single controller.
     */
    private fun publishController(global: Int) {
        val base = perUnit[global] ?: return
        val o = overlays[global]
        val out = if (o != null) {
            base.copy(
                speedKmh = o.speedKmh,
                speedSource = o.speedSource,
                odometerKm = o.odometerKm,
                tripKm = o.tripKm,
                batteryLevelFraction = o.batteryLevelFraction
            )
        } else {
            base
        }
        motion = motion + (global to out)
    }

    override fun latestMotion(controllerIndex: Int): ControllerData? =
        controllers.getOrNull(controllerIndex)?.let { motion[it.globalIndex] }

    override fun latestData(packIndex: Int): BmsData? =
        packs.getOrNull(packIndex)?.let { packData[it.globalIndex] }

    override fun reset() {
        accumulator.reset()
        pending = null
        // A scan parked on a session being torn down will never be serviced.
        // Answered with null — silence — rather than dropped: the composer is
        // suspended on this deferred and [SCAN_WAIT_MS] is a long time to make
        // a rider watch a spinner for an answer that can no longer arrive.
        // Completing with an empty list instead would claim the bus is empty.
        canScanRequest?.complete(null)
        canScanRequest = null
        perUnit = emptyMap()
        motion = emptyMap()
        packData = emptyMap()
        overlays = emptyMap()
        tripBaselineKm = emptyMap()
    }
}
