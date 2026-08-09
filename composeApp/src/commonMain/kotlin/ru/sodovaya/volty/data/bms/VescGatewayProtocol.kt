package ru.sodovaya.volty.data.bms

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import ru.sodovaya.volty.data.bms.vesc.VescBmsValues
import ru.sodovaya.volty.data.bms.vesc.VescCan
import ru.sodovaya.volty.data.bms.vesc.VescFrameAccumulator
import ru.sodovaya.volty.data.bms.vesc.VescPacket
import ru.sodovaya.volty.data.bms.vesc.VescSetupConfig
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
    val motor: MotorConfig = MotorConfig(),
    /**
     * **Packs only.** This slot is the link's DERIVED battery — a pack no BMS
     * of the profile's own backs, which exists because some controller on this
     * link carries `Controller.providesDerivedBattery`
     * (`ru.sodovaya.volty.data.ble.vescGatewayPacks` is the one place it is
     * decided).
     *
     * It changes NOTHING about the request: a derived slot is asked
     * `COMM_BMS_GET_VALUES` exactly like any other pack, because the node we
     * are connected to is a head unit and a head unit may well host a BMS the
     * profile was never told about — the rider's own does, and that ask was the
     * one thing never sent (`I` Task 5). What the flag adds is the FALLBACK:
     * when that ask is not answered, or is answered with the firmware's "no BMS
     * data yet" sentinel, the slot is filled from the link's controllers' own
     * rail telemetry instead of left empty ([VescProtocol]'s derived battery,
     * folded across the controllers this link owns).
     */
    val derived: Boolean = false
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
 * ## The battery a gateway link DERIVES (field report 2026-07-30, `I` Task 5)
 *
 * "Owned battery" above includes the link's derived slot — see
 * [GatewaySource.derived]. Until `I` Task 5 it did not, and the omission is the
 * literal mechanism behind the rider's *"это ничем не помогло"*: their scooter
 * was picked as a controller-only vehicle (zero packs, one controller backing a
 * derived battery), and the act of adding the uBox over CAN forwarding turned
 * the link into a gateway — at which point `controllerMotionProtocol`'s gateway
 * arm, which re-listed the multiplexer's arguments from
 * `LinkSpec.ownedControllers`/`ownedPacks` alone, **dropped `deriveBattery` on
 * the floor**. `packCount` fell to 0, `KableBmsRepository.planLinkPacks` then
 * allocated no slot for it, and the rider's action DELETED the battery it was
 * meant to fix. The same drop is why opcode 96 was never sent at all on that
 * vehicle: [bmsRequest] is built per owned pack and there were none, so the
 * head unit's working, correctly-emulating ANT bridge was never asked anything.
 *
 * Both halves are one fix, because a derived slot IS a pack request here. What
 * fills it is decided per cycle, by **precedence and age** — never by a latch,
 * and never immediately:
 *
 *  1. a real `COMM_BMS_GET_VALUES` reply wins, and goes on winning for
 *     [STALE_READING_MS] ([hostedBmsAtMs]) — a hosted BMS that misses a cycle
 *     must not have its number swapped for a computed one that reads just as
 *     real. Past that age the orchestrator is greying the pack out anyway, so
 *     the substitute serves again rather than leaving a rider whose ANT bridge
 *     died mid-ride with no battery for the rest of the connection;
 *  2. a contentless reply — the `can_id == 0xFF` sentinel, an undecodable
 *     frame, or silence — is a **warm-up for [HOSTED_BMS_WARMUP_MS] before it
 *     is a verdict** ([onContentlessBms]), because the same sentinel is what
 *     the rider's own bridge reports while it is merely reconnecting. Nothing
 *     is published during that window;
 *  3. only then the link's controllers' own rail telemetry
 *     ([publishDerivedBattery]), folded across the contributors whose decodes
 *     are younger than [STALE_READING_MS] — a stock VESC with no BMS answers
 *     opcode 96 with the sentinel forever, and a plain [VescProtocol] link
 *     would have shown a rail-derived battery. Adding a second controller must
 *     not take that away either.
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
 * That statement is about a SINGLE cycle, and it is worth being precise, because
 * the cost of "the ones that cannot simply cost their timeout" is not small: on
 * the rider's vehicle it was roughly 1000 ms of every ~1110 ms cycle.
 * **Repeated** silence, accumulated across cycles, IS a usable signal — one
 * timeout is ambiguous, twenty are not. That is what [suppressedAtMs] does with
 * it, and it **suppresses** rather than elects: see the next section.
 *
 * The traffic this adds is one request per controller per cycle: the plan grows
 * from `1 + controllers + packs` to `2 x controllers + packs`. That growth is
 * bounded by [checkSilenceBudget], which is computed from the plan size and
 * therefore already accounts for it — a plan that no longer fits is refused at
 * construction rather than discovered on the road.
 *
 * ## A node that has never answered is not asked twice a second (`I` Task 11)
 *
 * After a run of consecutive non-answers, one (controller, opcode) pair stops
 * being sent at all — [suppressedAtMs] — and is probed once more only on
 * reconnect ([reset]) or after [REPROBE_INTERVAL_MS]. On the rider's scooter
 * that takes the cycle from five requests costing ~1110 ms to three costing
 * ~110 ms: the head unit's two dead `GET_VALUES` opcodes were nine tenths of
 * the ride's telemetry latency.
 *
 * **How long a run has to be depends on what the pair has already proved**, and
 * the three thresholds are all in [rememberSilence]:
 *
 *  - a pair that has answered at least once this connection —
 *    [everAnswered] — gets [SUPPRESS_PROVEN_AFTER_SILENT_CYCLES];
 *  - one that has never answered gets [SUPPRESS_AFTER_SILENT_CYCLES], **and**
 *    must additionally have been silent for [UNPROVEN_SILENCE_WARMUP_MS] on
 *    the clock, so that a node still booting when the link came up is not
 *    given up on for being slow. The count bounds the cost of proof; the
 *    warm-up bounds the boot window, and a cycle is not a duration.
 *
 * [everAnswered] is never pruned within a connection, which has one named
 * consequence: a node that answers a single re-probe and then dies again is
 * proven from that instant, so it pays the full
 * [SUPPRESS_PROVEN_AFTER_SILENT_CYCLES] run before being suppressed a second
 * time, and again after every subsequent probe it happens to answer. Contrived
 * — it needs a node that answers exactly one request per re-probe interval —
 * bounded (one long run per 30 s at worst), and cheaper than the alternative,
 * which is deciding how much answering un-proves a node.
 *
 * Three things about it are load-bearing rather than incidental:
 *
 *  - **it is per (controller, OPCODE), never per node.** The rider's head unit
 *    answers `COMM_BMS_GET_VALUES` — it is the only thing on the vehicle that
 *    answers for the ANT pack — while refusing both `GET_VALUES` opcodes.
 *    Suppressing the node would silence the one thing it is good at;
 *  - **pack requests do not participate at all.** [bmsRequest]'s silence is
 *    already claimed by [HOSTED_BMS_WARMUP_MS]: a contentless outcome, silence
 *    included, is a warm-up for six seconds before it is a verdict, because the
 *    same outcome is what an ANT bridge reports while it is merely
 *    reconnecting. A pack suppressed after three silent cycles (~0.3 s on a
 *    suppressed plan) would stop being asked long before that warm-up expired,
 *    and — because [onContentlessBms] is driven BY the request — the derived
 *    battery would then never be published at all. The bridge that is slow to
 *    come up is exactly the case the warm-up exists to survive, so suppression
 *    stays away from it. Pinned by `a battery request is never suppressed,
 *    however long the gateway stays silent`, and, from the other side, by
 *    `a derived slot falls back to the rail when the gateway answers nothing at
 *    all` and `a hosted BMS lost mid-ride hands back to the substitute once its
 *    reading is stale`, both of which go dark the moment a pack can be
 *    suppressed;
 *  - **at most ONE suppressed request is re-probed per cycle.** Re-probes that
 *    all fire on the same timer would periodically cost the full worst case
 *    again — a loop that stalls on a schedule instead of being permanently
 *    slow, which is strictly harder to diagnose. Pinned by `at most one
 *    suppressed request is re-probed per cycle`.
 *
 *    **The guard binds at ANY link latency, and it is worth being exact about
 *    why**, because a first revision of this paragraph claimed it was a
 *    slow-link corner case and that is wrong. Two pairs suppressed in the same
 *    pre-suppression cycle carry stamps separated by however long the walk
 *    between them took; the loop's own walk between their two *checks* takes
 *    that same time, since it is the same requests in the same order. So the
 *    two elapsed times are **equal, exactly** — if the re-probe cycle starts at
 *    `C` and the pairs were stamped at `S` and `S + d`, the first is checked at
 *    `C` and the second at `C + d`, both `C - S` past their own stamps. The
 *    second is therefore due in the same cycle as the first however fast the
 *    link is (30 090 ms past a 30 000 ms window on the test fixture — the
 *    stamp lands at `replyTimeoutMs + lateReplyGuardMs`, because `exchange`
 *    waits out the guard before returning and the loop only then calls
 *    [rememberSilence]; a round-2 revision dropped that 100 ms and put 30 080
 *    here and in the test, and this is the correction), and
 *    without the guard both fire together. It goes slack only when a request
 *    *between* the two is itself suppressed and skipped, which shortens the
 *    walk without shortening the gap.
 *
 *    After the first wave the pairs stagger themselves: a re-probe that goes
 *    unanswered restarts its own clock, so the next one due gets the following
 *    cycle.
 *
 * A **failed write is not a node's silence** and does not count towards
 * suppression: the request never reached the wire, so the node was never asked.
 * Counting it would let one dropped link suppress a CAN bus that is perfectly
 * healthy — the same confident-wrong-conclusion class this part exists to
 * close. Pinned by `a write that never reached the wire is not the node's
 * silence`.
 *
 * A right-opcode-but-undecodable reply IS an answer, here as everywhere else in
 * this file: the node handles the opcode, and what came back is a decode
 * problem rather than an unsupported request. Suppression is about what the
 * node will not answer, not about what we cannot read.
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
 * every CAN node it has heard from (`mc_interface.c:1651-1688`).
 *
 * It does **not** touch the tachometer, and an earlier revision of this
 * paragraph claiming it "divides the tachometer by the number of VESCs" was the
 * firmware inverted — no such division exists. The frame's two distance fields
 * are `mc_interface_get_distance()` / `_abs()` (`commands.c:853,856`), read off
 * this node's own tachometer and its own wheel config. That does not weaken the
 * overlay: speed, odometer and trip are vehicle-level because two controllers
 * bolted to one frame cover the same ground, which is a stronger guarantee than
 * a firmware behaviour. `num_vescs` IS on the frame (`commands.c:874`), as its
 * own field, and this decoder does not read it.
 *
 * Publishing that decode as the answering controller's `ControllerData`
 * would hand `MotionAggregator` — which SUMS currents across controllers — a
 * figure that already includes the other uBox, and the dashboard would read
 * roughly double the real current. So only the vehicle-level scalars it is
 * asked for are taken from it (speed, odometer, trip, battery level); every
 * per-unit number stays the one `GET_VALUES` decoded for that unit. Asking
 * every controller for SETUP makes that separation MORE load-bearing, not
 * less: two controllers each publishing a CAN-wide current sum would be
 * summed a second time by the fold.
 *
 * ## `ah_cnt` and the gateway producer
 *
 * An earlier revision deliberately left the amp-hour counter unmapped because
 * stock VESC uses it as a cumulative counter while the rider's `nyxdash`
 * gateway uses it as remaining Ah (`E:\\sodovaya\\nyxdash\\firmware\\components\\vesc_express\\src\\ant_bms.c`,
 * where `remaining_ah` is assigned to `ah_cnt`). That retraction was correct
 * while this protocol also accepted an unclassified direct stock-BMS producer.
 * `VESC_BMS` is now only a hosted pack on this gateway path, and the only
 * producer in the deployed vehicle is the measured `nyxdash` path. The frame
 * projection therefore publishes remaining Ah and recovers design capacity
 * from `remainingAh / soc`; a frame without SoC still leaves capacity unknown.
 * If a direct stock-VESC-BMS link is added later, it must get an explicit
 * producer policy before reusing this projection.
 * The same applies to the per-cell balancing flags: decoded, but with no
 * `BmsData` field to carry them, left unmapped rather than approximated.
 */
@OptIn(ExperimentalTime::class)
class VescGatewayProtocol(
    /** Owned controllers, in the link's own order — index i IS local index i. */
    private val controllers: List<GatewaySource>,
    /**
     * Owned batteries, in the link's own order — index i IS local index i.
     *
     * **No default, deliberately.** It had one, and the gateway arm of
     * `controllerMotionProtocol` re-listed this list from `link.ownedPacks`
     * while quietly not answering `deriveBattery` at all — the third instance
     * on this project of a branch losing a field the other branch keeps. There
     * is now exactly one function that answers "which pack slots does a VESC
     * link decode" (`ru.sodovaya.volty.data.ble.vescGatewayPacks`), and no
     * caller may reach this constructor without having gone through it.
     */
    private val packs: List<GatewaySource>,
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
    private val lateReplyGuardMs: Long = DEFAULT_LATE_REPLY_GUARD_MS,
    /**
     * Wall clock in epoch milliseconds, injectable for the same reason
     * [ru.sodovaya.volty.data.ble.VehicleConnection] takes one: the derived
     * battery's three decisions ([STALE_READING_MS] twice, [HOSTED_BMS_WARMUP_MS]
     * once) are all "how long has it been since", and a test that cannot move
     * this clock can only approximate them from real elapsed time.
     *
     * Deliberately NOT the source of [ControllerData.timestamp] — those are
     * stamped by the decoders off `Clock.System` and stay that way, because the
     * timestamp a sample carries downstream must be the one every other producer
     * in the app uses. This clock decides *ages*; the decoders decide *stamps*.
     * In production they are the same clock, microseconds apart.
     */
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() }
) : BmsProtocol(), MotionSource, ControllerConfigSource, SerialPollSource, CanBusScanner {

    companion object {
        const val DEFAULT_POLL_INTERVAL_MS: Long = 50L
        const val DEFAULT_REPLY_TIMEOUT_MS: Long = 400L
        const val DEFAULT_LATE_REPLY_GUARD_MS: Long = 100L

        /**
         * How old a reading may be before this protocol stops treating it as
         * current — used for both halves of the derived battery's precedence
         * rule ([publishDerivedBattery]).
         *
         * A restatement of `BleConfig.packOfflineAfterMs`, NOT an import, for
         * the same dependency-direction reason as [WATCHDOG_SILENCE_BUDGET_MS].
         * That is the age at which `VehicleConnection`'s own sweep stops
         * showing a source as online, and lining up with it is what makes both
         * halves of the rule defensible rather than tuned:
         *
         *  - a CONTRIBUTOR the orchestrator would already be drawing as offline
         *    must not still be feeding a battery reading. Without this a
         *    controller stuck emitting right-opcode-but-undecodable
         *    `GET_VALUES` — which is ANSWERED, so [valuesRequest]'s `onSilence`
         *    never runs and its cached decode never expires — contributes its
         *    frozen current and power to the sum forever;
         *  - a HOSTED BMS reading this old is one the orchestrator is about to
         *    grey out anyway, so a rail-derived substitute is better than an
         *    empty slot. Below it the real reading wins.
         */
        const val STALE_READING_MS: Long = 12_000L

        /**
         * How long a contentless `COMM_BMS_GET_VALUES` must persist before it is
         * believed — the sentinel's **warm-up**, not its verdict.
         *
         * `can_id == 0xFF` means two different things and the wire cannot tell
         * them apart: "this VESC has no BMS", and "the ANT bridge is not up yet"
         * — `ant_bms.c:900-905` memsets `bms_values` and sets `can_id = -1` on
         * **every** BLE disconnect of the pack, including the ordinary blips it
         * recovers from by itself. Believing the sentinel immediately would put
         * a rail-derived substitute on the Battery screen for the first cycles
         * of every single connect to a head unit that hosts a perfectly good
         * BMS — indistinguishable from the real reading, which is the exact
         * confident-substitute failure this part exists to remove.
         *
         * Derived from that firmware rather than picked: the fast path a bridge
         * takes when a link it had established drops is
         * `ANT_DIRECTED_DELAY_MS` (500 ms settle) + up to `ANT_DIRECTED_TIMEOUT_MS`
         * (3000 ms for the scan-less direct open) + up to
         * `ANT_POLL_INTERVAL_DEFAULT` (2000 ms before the first status frame) =
         * 5500 ms, rounded up. Past that the bridge has fallen back to its
         * backed-off scan (3 s doubling to a 60 s ceiling), which is an outage
         * rather than a warm-up, and a substitute is then the honest thing to
         * show.
         *
         * The price, stated: a gateway that genuinely hosts no BMS shows no
         * battery for the first 6 s of a connection. An absent battery for six
         * seconds is honest; a plausible wrong one is not.
         */
        const val HOSTED_BMS_WARMUP_MS: Long = 6_000L

        /**
         * How many consecutive cycles one (controller, opcode) pair may go
         * unanswered before it stops being sent — the number of timeouts it
         * takes to turn "a slow or booting node" into "a node that does not
         * handle this opcode".
         *
         * **This is the threshold for a pair that has NEVER answered in this
         * connection.** One that has is held to
         * [SUPPRESS_PROVEN_AFTER_SILENT_CYCLES] instead, and that split is what
         * makes three defensible — see below.
         *
         * The two costs are wildly asymmetric and the first revision of this
         * comment argued only from the cheaper one. **The cost of proof** is
         * one more `replyTimeoutMs + lateReplyGuardMs` per dead pair per extra
         * cycle, paid once per connection: on the rider's scooter three cycles
         * is ~3.3 s of the old slow loop before the fast one begins, and a
         * fourth would be 4.4 s. **The cost of being wrong** is
         * [REPROBE_INTERVAL_MS] of a controller that is invisible while
         * perfectly healthy, *plus* a derived pack whose summed current and
         * power silently omit it for the same window (see [REPROBE_INTERVAL_MS]).
         * Half a minute of a confidently-too-low pack current against half a
         * second of politeness is not a close trade, and three consecutive
         * misses on a busy CAN bus behind a BLE hop is not exotic.
         *
         * What resolves it is that this number no longer applies to a node that
         * has ever spoken. The only pairs held to three are ones that have
         * produced **nothing at all** since the link came up — the rider's head
         * unit answers opcode 47 zero times, ever — and for those a false
         * positive costs thirty seconds of something that has shown no sign of
         * existing. Anything that has answered once is worth twenty-five times
         * the benefit of the doubt, and gets it.
         *
         * **This count is not a duration, and it is not on its own sufficient.**
         * A first revision of this constant called three cycles "~3.3 s"; that
         * is three cycles *of the rider's plan*, and the same three cycles are
         * 1.65 s on a one-controller link. What has to be protected at connect
         * is a **window** — how long a node may take to boot — and a window
         * measured in cycles varies with the vehicle, which is the objection
         * this file already makes against a cycle-counted re-probe one section
         * away. So a pair that has never answered must clear
         * [UNPROVEN_SILENCE_WARMUP_MS] on the clock **as well as** this count
         * before it is suppressed. The count bounds the cost of proof; the
         * warm-up bounds the boot window; neither does the other's job.
         *
         * Pinned from both sides by `a controller that never answers stops
         * being asked` (both gates satisfied) and by `a node that is merely
         * slow to boot is not suppressed for being slow` (the count satisfied
         * and the clock not).
         */
        const val SUPPRESS_AFTER_SILENT_CYCLES: Int = 3

        /**
         * How long a pair that has **never answered in this connection** must
         * have been silent, on the clock, before the cycle count is allowed to
         * suppress it. Six seconds — deliberately the same as
         * [HOSTED_BMS_WARMUP_MS], and for the same reason at the same moment.
         *
         * **The parallel with the sentinel's warm-up is exact, not decorative.**
         * That constant exists because believing `can_id == 0xFF` immediately
         * would put a rail-derived substitute on the Battery screen for the
         * first cycles of every connect, indistinguishable from a real reading.
         * Substitute "suppressing a node that is still booting" for "believing
         * the sentinel" and the sentence survives word for word: a confident
         * wrong number at connect, in a place the rider cannot tell from a
         * right one. A suppressed booting controller is not shown as offline
         * and not shown as stale — its slot simply ages out while its
         * contribution quietly leaves the derived pack's summed current
         * (see [REPROBE_INTERVAL_MS]). Not stale, not greyed out, just smaller.
         *
         * Set equal to [HOSTED_BMS_WARMUP_MS] rather than derived
         * independently, and the honesty of that is worth stating: no firmware
         * constant in this repository describes how long a uBox takes to answer
         * CAN after a VESC Express comes up, so an independent derivation would
         * be invention wearing a derivation's clothes. What IS true is that
         * both windows answer the same question — how long may a thing behind
         * this head unit take to come up before silence becomes a verdict — at
         * the same instant of the same connection. Keeping them equal also
         * means a connect is either wholly warm or wholly cold rather than
         * staggered, which is one fewer state to reason about.
         *
         * It is inert for a pair that has answered: passing
         * [SUPPRESS_PROVEN_AFTER_SILENT_CYCLES] costs at least 12 s of silence,
         * which is past this by construction. The inequality is asserted by
         * `a proven pair is only given up on once the app has already drawn it
         * offline`, so this stays a guarantee rather than an observation.
         */
        const val UNPROVEN_SILENCE_WARMUP_MS: Long = 6_000L

        /**
         * The same count for a pair that **has answered at least once in this
         * connection** — a far longer benefit of the doubt, because it has
         * proved the node handles the opcode and all that is in question is
         * whether it is awake.
         *
         * Deliberately not "never suppress once it has answered". A uBox
         * unplugged mid-ride would then time out forever and we would have
         * rebuilt the original defect from the other end: the ~1000 ms of dead
         * requests per cycle that this whole task exists to remove would simply
         * need a slightly different vehicle to reappear on.
         *
         * **What it costs, since the floor below is the side that makes 25 look
         * cheap.** On the rider's vehicle a pre-suppression cycle is ~1110 ms,
         * so 25 cycles is roughly **27 seconds** during which a uBox that dies
         * mid-ride holds the dashboard at ~0.9 Hz — against the ~7.8 s a node
         * that never spoke costs, and the ~3.3 s the first revision of this
         * task charged for both. That is the deliberate trade: a node that has
         * proved it works is worth far more patience than a fast recovery from
         * its death, because its death is rare and its nap is not. If field
         * data says mid-ride deaths are common on this hardware, this is the
         * number that pays for it.
         *
         * **Twenty-five, anchored to the rest of the app rather than picked.** A
         * silent pair costs at least `replyTimeoutMs + lateReplyGuardMs` per
         * cycle, so 25 cycles is at least 12.5 s of unbroken silence at the
         * defaults — past [STALE_READING_MS], which restates
         * `BleConfig.packOfflineAfterMs`, the age at which `VehicleConnection`'s
         * own sweep is *already* drawing that controller offline. So suppression
         * can never be the thing that removes a controller the rider can still
         * see: by the time it engages, the dashboard has said the node is gone
         * for some time on its own authority. The inequality is pinned by `a
         * proven pair is only given up on once the app has already drawn it
         * offline`, so a retune that inverts it breaks the build in the same
         * commit rather than quietly making this paragraph false.
         */
        const val SUPPRESS_PROVEN_AFTER_SILENT_CYCLES: Int = 25

        /**
         * How long a suppressed (controller, opcode) pair stays suppressed
         * before one request is spent probing it again.
         *
         * The trade is one-sided in both directions, so it is set where they
         * cross. A re-probe costs `replyTimeoutMs + lateReplyGuardMs` (500 ms
         * at the defaults) when it fails, which against 30 s is ~1.7 % of the
         * loop per suppressed pair — invisible next to the ~90 % this
         * suppression gives back. Shortening it buys a faster recovery at a
         * cost that climbs linearly; lengthening it saves almost nothing and
         * makes a woken uBox invisible for longer.
         *
         * What the number really buys is the recovery bound: **a controller
         * that was asleep or booting rejoins within half a minute**, without
         * the rider doing anything. That is the same order as the 60 s ceiling
         * `ant_bms.c`'s own reconnect backs off to, so nothing on this vehicle
         * recovers on a slower schedule than the firmware does.
         *
         * ## What this window costs, in full
         *
         * Stated plainly, because it is a real regression against the loop this
         * replaces: before suppression a sleeping controller came back on its
         * FIRST reply, one cycle after it woke. It now comes back up to this
         * long after waking. [SUPPRESS_PROVEN_AFTER_SILENT_CYCLES] makes the
         * window hard to reach — a controller that has ever answered must be
         * silent past [STALE_READING_MS] first — but it is reachable, and there
         * is a **second cost that is easy to miss** because it lands somewhere
         * else entirely:
         *
         * **The derived battery is understated for the same window.**
         * [publishDerivedBattery] sums `batteryCurrentA` and `powerW` across the
         * controllers present in [motion], and a controller whose `GET_VALUES`
         * is suppressed is not in [motion]. So a rider whose second uBox naps
         * and wakes sees a pack current and power that are confidently too low
         * — not stale, not greyed out, just *smaller* — until its probe comes
         * round. It is not caught by the pack request carrying no suppression
         * key: that protects the *request*, and this is the *value*.
         * `I` Task 5's `STALE_READING_MS` contributor filter has the same shape
         * and is deliberate for the same reason; the difference is that this
         * window is opened by suppression rather than by the contributor going
         * stale, so it is named here. Pinned by `a controller suppressed while
         * it sleeps understates the derived battery until its probe`, which
         * asserts the sum across the window and its recovery after.
         *
         * Both halves are why this number is short rather than generous, and
         * they are the first thing to revisit if field data says a nap of more
         * than [STALE_READING_MS] is ordinary on this hardware.
         */
        const val REPROBE_INTERVAL_MS: Long = 30_000L

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
         * controller since SETUP is asked of each of them (class KDoc), and
         * `packs` counts the link's DERIVED slot when it has one, since that is
         * a `COMM_BMS_GET_VALUES` request like any other (`I` Task 5). The
         * rider's scooter (head unit + one uBox + the battery the head unit
         * hosts, derived because their profile never listed it) is 5
         * requests: 4 × 500 + 50 = 2050 ms against a 5 s budget. It grows
         * LINEARLY with the plan, so a vehicle with enough sources behind one
         * head unit would silently reconnect forever while most of its CAN bus
         * was healthy. Failing loudly at construction is the honest outcome: a
         * plan that cannot meet this bound does not work, and the message says
         * which knob to turn.
         *
         * **`I` Task 11's suppression does NOT widen this**, and the decision
         * is deliberate rather than an omission. Suppression makes the
         * STEADY-STATE cycle smaller — the rider's five requests become three —
         * but it is a runtime property that engages only after
         * [SUPPRESS_AFTER_SILENT_CYCLES] cycles have already timed out. A
         * vehicle legal only once suppression had engaged would blow this
         * budget during the first seconds of every connect, and "correct on
         * average, broken exactly when it matters" is the defect class this
         * whole part has been closing. So the ceiling keeps following the WORST
         * case: [planSize] is still the whole plan, and the five-controller
         * limit Task 4 arrived at stands unchanged. The arithmetic stays honest
         * in the other direction too — the set of requests a cycle issues is
         * always a SUBSET of the plan (a suppressed pair is skipped, and at
         * most one re-probe is added back), so no cycle can ever cost more than
         * this bound allows.
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

    /** One-shot setup replies, keyed by the vehicle-global controller index. */
    @Volatile private var controllerConfigs: Map<Int, VescSetupConfig> = emptyMap()

    /** Armed after notifications are subscribed, then consumed once by the serial loop. */
    @Volatile private var configRequestsArmed = false
    @Volatile private var configRequests: List<Request> = emptyList()

    /**
     * When each pack slot last produced a REAL `COMM_BMS_GET_VALUES` reading,
     * on [nowMs]' clock — **precedence, not permanence**.
     *
     * A real reading outranks the derived substitute while it is younger than
     * [STALE_READING_MS]: a hosted BMS that misses a cycle must not have its
     * number quietly swapped for a computed one that reads just as real. Past
     * that age the orchestrator is greying the pack out anyway, and a substitute
     * beats an empty slot — which is what a permanent latch got wrong, leaving a
     * rider whose ANT bridge died mid-ride with no battery at all for the rest
     * of the connection while the substitute sat there available.
     */
    @Volatile private var hostedBmsAtMs: Map<Int, Long> = emptyMap()

    /**
     * When the CURRENT unbroken run of contentless `COMM_BMS_GET_VALUES`
     * outcomes began, per pack slot — a sentinel frame, an undecodable one, or
     * silence. Cleared by [reset] alone, so the warm-up is spent once per
     * connection: a real reading deliberately does NOT restart it, for the
     * reason argued where that decision is taken (see [bmsRequest]'s `consume`).
     * A second bridge outage inside one connection is therefore covered by the
     * [STALE_READING_MS] precedence window rather than by another warm-up.
     *
     * The input to [HOSTED_BMS_WARMUP_MS]: the sentinel has to persist before it
     * is believed, because on the rider's own head unit it is also what a bridge
     * that is merely reconnecting reports. Silence is graced on the same terms —
     * a dropped reply is not a verdict either, and treating the two differently
     * would only mean guessing which one a given head unit uses.
     */
    @Volatile private var contentlessSinceMs: Map<Int, Long> = emptyMap()

    /**
     * When each controller last produced a per-unit decode, on [nowMs]' clock.
     *
     * NOT derivable from [motion]'s own timestamps: those are the decoders'
     * stamps, and this has to answer "how long since this entry was last
     * REFRESHED" — which for a controller answering with an undecodable body is
     * a growing age behind an unchanging stamp.
     */
    @Volatile private var lastDecodeAtMs: Map<Int, Long> = emptyMap()

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
        val onSilence: () -> Unit,
        /**
         * The (controller, opcode) pair this request's silence accumulates
         * under, or **null for a request that is never suppressed** — every
         * pack request, and the composer's `PING_CAN`.
         *
         * Baked in at build time like [frame] is, so "whose silence was that"
         * is answered by what we asked for rather than by anything on the wire.
         */
        val suppression: SilentPair? = null
    )

    /**
     * One (controller, opcode) pair, the unit suppression works in.
     *
     * The opcode is part of the key and that is the whole point: the rider's
     * head unit answers `COMM_BMS_GET_VALUES` while refusing both `GET_VALUES`
     * opcodes, so a key without it would silence the only node on the vehicle
     * that speaks for the ANT pack.
     */
    private data class SilentPair(val globalIndex: Int, val opcode: Int)

    /**
     * How many consecutive cycles each pair has now gone unanswered. A pair
     * reaching its limit is added to [suppressedAtMs] and **stays here too**;
     * any answer at all drops it. Only the poll loop writes it.
     *
     * It staying is what lets [rememberSilence] have one path instead of two:
     * the count only ever grows past the limit, so a suppressed pair's later
     * silences recompute the same verdict and restamp harmlessly. An earlier
     * revision both cleared this on suppression and guarded against the clear
     * with an early return — two spellings of one outcome, and the sweep
     * found the guard indistinguishable from its absence.
     */
    @Volatile private var silentRunCycles: Map<SilentPair, Int> = emptyMap()

    /**
     * When each pair's CURRENT unbroken run of silences began, on [nowMs]'
     * clock — the input to [UNPROVEN_SILENCE_WARMUP_MS], and the half of the
     * rule that is a window rather than a count.
     *
     * Kept beside [silentRunCycles] and dropped with it — by [reset], and by
     * nothing else. Neither map is dropped on suppression; see
     * [silentRunCycles] for why that is what keeps [rememberSilence] single-
     * pathed. **Not** dropped by [rememberAnswer] either, and that omission is
     * load-bearing-by-absence rather than an oversight: an answer makes the
     * pair [everAnswered], and for a proven pair this window is inert by
     * construction (25 cycles of silence cost at least 12 s, past the 6 s this
     * gates on). A line no implementation could falsify is left out on this
     * branch rather than written and annotated.
     */
    @Volatile private var silentSinceMs: Map<SilentPair, Long> = emptyMap()

    /**
     * The suppressed pairs, each stamped with **when it was last put on the
     * wire** — the moment it was suppressed, or the moment its most recent
     * re-probe went unanswered. Not "when it was suppressed": a failed re-probe
     * restarts the clock, which is what keeps two pairs suppressed moments
     * apart from coming due in the same cycle forever after.
     *
     * Cleared wholesale by [reset], which is the "re-probe on reconnect,
     * always" half of the rule: a reconnect is the one moment where a node's
     * previous refusal says nothing at all about this session.
     */
    @Volatile private var suppressedAtMs: Map<SilentPair, Long> = emptyMap()

    /**
     * The pairs that have answered **at least once in this connection**, which
     * is the difference between the two suppression thresholds.
     *
     * The distinction is the whole reason the aggressive threshold is
     * defensible. "You cannot both stop asking and notice immediately" is only
     * true if suppression cannot tell a pair that has NEVER answered from one
     * that answered and then stopped — and those are different claims about
     * different hardware. The rider's motivating case is entirely the former:
     * their head unit answers opcode 47 zero times, ever, so three cycles of
     * proof cost it nothing it was going to use. A uBox that has spoken is held
     * to [SUPPRESS_PROVEN_AFTER_SILENT_CYCLES] instead.
     *
     * Cleared by [reset] with everything else, and that is correct rather than
     * an oversight: a new connection has no evidence, and a healthy node earns
     * the flag back on its first reply. What it costs is named on
     * [SUPPRESS_AFTER_SILENT_CYCLES] — a node still booting when the link comes
     * up is, by this definition, a node that has never answered.
     */
    @Volatile private var everAnswered: Set<SilentPair> = emptySet()

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
     *
     * **There is a THIRD such pair**: two packs on one gateway put two
     * byte-identical `COMM_BMS_GET_VALUES` requests adjacent, and the product's
     * own four-source plan (two controllers, two packs) reaches it. Task 4
     * recorded it as covered-by-construction but pinned by nothing; `I` Task 5
     * pins it (`a late BMS reply is not credited to the next battery`) — and
     * had to, because a link that owns a hosted battery AND derives one now
     * reaches the pair from a shape a single rider action can create.
     * The trade is deliberate: one guard mechanism covering two known pairs
     * beats an ordering whose safety comes from opcodes happening not to
     * collide, which no test can hold in place.
     */
    private val plan: List<Request> = buildList {
        controllers.forEach { add(setupRequest(it)) }
        controllers.forEach { add(valuesRequest(it)) }
        packs.forEach { add(bmsRequest(it)) }
    }

    private fun armControllerConfigRequests() {
        if (configRequestsArmed) return
        configRequestsArmed = true
        configRequests = controllers.map(::setupConfigRequest)
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
        onSilence = { overlays = overlays - c.globalIndex },
        // The rider's head unit's permanent state: `commands.c` falls through
        // to `default: break;` on opcode 47 and never builds a reply.
        suppression = SilentPair(c.globalIndex, VescValues.OPCODE_GET_VALUES_SETUP)
    )

    private fun setupConfigRequest(c: GatewaySource) = Request(
        frame = frameFor(c.canId, byteArrayOf(VescSetupConfig.OPCODE_GET_MCCONF_TEMP.toByte())),
        expectedOpcode = VescSetupConfig.OPCODE_GET_MCCONF_TEMP,
        consume = { payload ->
            VescSetupConfig.decode(payload)?.let {
                controllerConfigs = controllerConfigs + (c.globalIndex to it)
            }
        },
        // This is deliberately a one-shot request. A head unit that does not
        // implement opcode 91 must not be held behind a retrying configuration
        // probe on every telemetry cycle.
        onSilence = {}
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
        // [lastDecodeAtMs] is NOT dropped here, and that is not an omission:
        // it is only ever read in the same expression that reads [motion]
        // (`publishDerivedBattery`), which this line does drop — so a stale age
        // beside a missing decode can never be observed. Clearing it as well
        // would be a line no test could distinguish from its absence, and on
        // this branch that is a reason to leave code out rather than to
        // annotate it. A future reader who wants to consult the age map on its
        // own has to drop it here first.
        onSilence = {
            perUnit = perUnit - c.globalIndex
            motion = motion - c.globalIndex
        },
        suppression = SilentPair(c.globalIndex, VescValues.OPCODE_GET_VALUES)
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
                // This gateway really does host a BMS for this slot, and said
                // so just now. Outranks the substitute for [STALE_READING_MS].
                hostedBmsAtMs = hostedBmsAtMs + (p.globalIndex to nowMs())
                // [contentlessSinceMs] is deliberately NOT cleared here, and
                // the argument is guarded rather than asserted: once a slot has
                // ever produced a real reading, the substitute is gated by
                // [STALE_READING_MS] from that reading, which is strictly
                // LONGER than [HOSTED_BMS_WARMUP_MS] — so restarting the
                // warm-up could never delay anything the precedence window was
                // not already delaying. `the warm-up is shorter than the
                // staleness window` pins the inequality the argument rests on;
                // if a later tuning inverts it, that test fails and this
                // comment stops being true in the same commit.
                packData = packData + (p.globalIndex to frame.toBmsData())
            } else {
                // An answer that carries no reading is, for a DERIVED slot,
                // exactly as informative as no answer: a stock VESC with no BMS
                // replies with the sentinel rather than staying silent, and a
                // rider whose plain VESC link showed a rail-derived battery
                // must not lose it for having added a second controller.
                onContentlessBms(p)
            }
        },
        // For a real battery: nothing to forget — its last sample stays cached
        // and the orchestrator's staleness sweep greys it out on its own
        // schedule, exactly as for a directly-connected BMS that goes quiet.
        //
        // For a DERIVED slot this is where the fallback lives, and the position
        // is the point: the pack requests come last in the cycle (see [plan]),
        // so every controller that is going to answer this cycle already has,
        // and the fold below sees a complete picture. It also makes the derived
        // battery exactly ONE sample per cycle — publishing it from
        // [applyValues] instead would emit one per answering controller, which
        // is the duplicated-sample stutter in another costume.
        onSilence = { onContentlessBms(p) }
        // NO `suppression` key, deliberately — see the class KDoc. This
        // request's silence is already spoken for by [HOSTED_BMS_WARMUP_MS],
        // and the line above is why suppressing it could not merely slow the
        // battery down: `onContentlessBms` is driven BY the request, so a pack
        // that stopped being asked would stop being derived at all.
    )

    /**
     * One cycle in which [slot]'s `COMM_BMS_GET_VALUES` produced no reading —
     * the `can_id == 0xFF` sentinel, an undecodable frame, or silence.
     *
     * The substitute engages only once that has gone on for
     * [HOSTED_BMS_WARMUP_MS]. **Nothing is published during the warm-up** — an
     * absent battery is honest, a plausible wrong one is not, and on the
     * rider's own head unit the first cycles of every connect are exactly when
     * the ANT bridge is still coming up.
     */
    private fun onContentlessBms(p: GatewaySource) {
        if (!p.derived) return
        val now = nowMs()
        val since = contentlessSinceMs[p.globalIndex]
            ?: now.also { contentlessSinceMs = contentlessSinceMs + (p.globalIndex to it) }
        if (now - since < HOSTED_BMS_WARMUP_MS) return
        publishDerivedBattery(p, now)
    }

    /**
     * Fill [slot] from the link's controllers' own rail telemetry — the
     * gateway's [VescProtocol.deriveBattery] equivalent.
     *
     * Silent while a real hosted reading still outranks it ([hostedBmsAtMs]),
     * and silent when no controller on this link has a per-unit decode young
     * enough to trust: a derived battery is arithmetic on controller telemetry,
     * so with no current telemetry there is no battery. Publishing nothing is
     * what lets the orchestrator's ordinary staleness sweep grey the slot out —
     * a fresh `BmsData` every cycle regardless of its inputs would make the
     * derived battery structurally incapable of ever reading as stale.
     *
     * The fold is `copy()` onto the first answering controller rather than a
     * second `BmsData` constructor, so [derivedBatteryFrom] stays the ONE
     * statement of the sign convention and the `socKnown` rule, and only what
     * genuinely differs between one controller and several is written here:
     *
     *  - **voltage: `max`.** Every controller on the link sits on the same
     *    rail, so they should agree; `max` is what survives a contributor that
     *    reports 0 V because it has no measurement, where a mean would be
     *    dragged down by a node that simply does not know.
     *
     *    The reason originally given here — "`hasInputVoltage` is unreachable
     *    for VESC (field report §4), so the flag cannot be consulted" — was
     *    **made false by `I` Task 7**, which taught `VescValues` to clear that
     *    flag on a reply carrying `v_in = 0`. The arithmetic is unaffected: a
     *    phantom contributor still reports `0`, which never wins a `max` over a
     *    real rail, so this fold behaves identically with or without the flag.
     *    A flag-filtered MEAN is now expressible and is NOT the same function
     *    (78.0 and 78.4 mean to 78.2 and max to 78.4) — but changing it is a
     *    behaviour change on the BATTERY path, which `I` Task 7 was forbidden to
     *    make. Left as a pointer for whoever owns that decision, not as an
     *    oversight;
     *  - **current and power: summed**, because they are per-unit draws off one
     *    pack. This is the same arithmetic `MotionAggregator` does across
     *    controllers, applied here because a derived pack has to be one number;
     *  - **battery level: the first controller that reports a real one.** `0f`
     *    is `VescValues`' "no battery configuration", which
     *    [derivedBatteryFrom] would otherwise turn into a confident 0 %;
     *  - **timestamp: the newest**, and this one is load-bearing rather than
     *    cosmetic. Contributors are NOT all same-cycle: a controller answering
     *    `GET_VALUES` with a right-opcode-but-undecodable body has its reply
     *    consumed, its waiter completed and its request settled — so
     *    [valuesRequest]'s `onSilence` never runs and its [motion] entry
     *    survives, frozen, for up to [STALE_READING_MS]. Stamping the derived
     *    battery from the first contributor would date it by that frozen entry
     *    while a live neighbour is what the numbers came from. Pinned by
     *    `the derived battery is stamped by its newest contributor, not its
     *    first`. (This paragraph replaces a claim that the line was an
     *    equivalent mutant. It was not, and the same false premise was hiding
     *    the staleness filter below.)
     */
    private fun publishDerivedBattery(slot: GatewaySource, now: Long) {
        // No `if (!slot.derived) return` here. [onContentlessBms] is the only
        // caller and already refuses a non-derived slot; a second copy of the
        // same test made BOTH copies individually undetectable — each masked
        // the other's removal, so the sweep reported two equivalent mutants
        // where the guard is in fact load-bearing exactly once.
        val hostedAt = hostedBmsAtMs[slot.globalIndex]
        if (hostedAt != null && now - hostedAt <= STALE_READING_MS) return
        // [motion], not [perUnit]: the state of charge a VESC reports is on the
        // SETUP frame, so it reaches a controller only through its overlay, and
        // [perUnit] is the pre-overlay decode. Folding that instead would leave
        // every gateway-derived battery with `socKnown = false` — where a plain
        // VescProtocol link, which decodes SETUP directly, has the real gauge.
        //
        // Filtered by AGE, not merely by presence: an entry left standing by an
        // undecodable reply would otherwise contribute its frozen current and
        // power to the sum for the rest of the ride.
        val units = controllers.mapNotNull { c ->
            val at = lastDecodeAtMs[c.globalIndex] ?: return@mapNotNull null
            if (now - at > STALE_READING_MS) null else motion[c.globalIndex]
        }
        val head = units.firstOrNull() ?: return
        val folded = head.copy(
            inputVoltageV = units.maxOf { it.inputVoltageV },
            batteryCurrentA = units.map { it.batteryCurrentA }.sum(),
            powerW = units.map { it.powerW }.sum(),
            batteryLevelFraction = units.firstNotNullOfOrNull { u ->
                u.batteryLevelFraction?.takeIf { it > 0f }
            },
            timestamp = units.maxOf { it.timestamp }
        )
        packData = packData + (slot.globalIndex to derivedBatteryFrom(folded))
    }

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
            val oneShot = configRequests
            if (oneShot.isNotEmpty()) {
                for (request in oneShot) exchange(request, send)
                configRequests = emptyList()
            }
            serviceCanScan(send)
            // At most ONE suppressed pair is re-probed per cycle. Without this
            // every pair suppressed within the same cycle comes due within the
            // same cycle too, and the loop periodically pays the whole
            // pre-suppression worst case again — a stall on a schedule, which
            // is harder to diagnose than the permanently slow loop it replaced.
            // Per cycle, so it resets here rather than being protocol state.
            var reprobedThisCycle = false
            for (request in plan) {
                val key = request.suppression
                val suppressedAt = if (key == null) null else suppressedAtMs[key]
                if (suppressedAt != null) {
                    if (reprobedThisCycle) continue
                    if (nowMs() - suppressedAt < REPROBE_INTERVAL_MS) continue
                    reprobedThisCycle = true
                }
                // A plan whose every suppressible request is suppressed and
                // whose packs are empty issues nothing at all this cycle and
                // paces on [pollIntervalMs] alone. That is a delay loop, not a
                // spin, and unlike the empty-plan case above it must NOT
                // return: the re-probe above is what lets a bus that wakes back
                // up be found again.
                when (exchange(request, send)) {
                    Outcome.ANSWERED -> if (key != null) rememberAnswer(key)
                    Outcome.SILENT -> if (key != null) rememberSilence(key)
                    // A write that never reached the wire says nothing about
                    // the node — see the class KDoc.
                    //
                    // It also does not restamp a suppressed pair, which has one
                    // bounded consequence worth knowing about rather than
                    // fixing: while every write is failing, the FIRST suppressed
                    // pair in plan order stays due, takes the single re-probe
                    // slot above every cycle, and the pairs behind it starve.
                    // Self-correcting — the moment writes work again the probe
                    // either answers (restored) or is silenced (restamped, and
                    // the queue moves on) — and a link on which every write
                    // throws is one the session's watchdog is already tearing
                    // down. Restamping here would be worse: it would let a
                    // broken link postpone probes of nodes it never asked.
                    Outcome.LINK_FAILED -> Unit
                }
            }
            delay(pollIntervalMs)
        }
    }

    /**
     * This pair answered. Forget both the run of silences it had accumulated
     * and any suppression, so a node that starts answering is **fully**
     * restored to the every-cycle walk rather than left on the re-probe timer.
     *
     * Per pair, and only this pair: an answer to `GET_VALUES` is not evidence
     * about `GET_VALUES_SETUP`, which is the exact confusion the rider's head
     * unit exists to punish.
     */
    private fun rememberAnswer(key: SilentPair) {
        silentRunCycles = silentRunCycles - key
        suppressedAtMs = suppressedAtMs - key
        everAnswered = everAnswered + key
    }

    /**
     * This pair was asked and said nothing.
     *
     * **One path, deliberately, including for a pair that is already
     * suppressed.** An earlier revision had an `if (key in suppressedAtMs)`
     * early return here to restamp a failed re-probe. It was removed once the
     * sweep showed it equivalent, and the reason it is equivalent is worth
     * keeping: a suppressed pair's run count only ever grew past its limit and
     * its first-silence stamp is never cleared, so the ordinary path below
     * reaches the same `else` branch and restamps it anyway. Two ways of
     * spelling one outcome is how a guard ends up indistinguishable from its
     * absence.
     */
    private fun rememberSilence(key: SilentPair) {
        // A node that has proved it handles this opcode is worth far more
        // patience than one that has produced nothing since the link came up.
        val limit =
            if (key in everAnswered) SUPPRESS_PROVEN_AFTER_SILENT_CYCLES else SUPPRESS_AFTER_SILENT_CYCLES
        val now = nowMs()
        val since = silentSinceMs[key]
            ?: now.also { silentSinceMs = silentSinceMs + (key to it) }
        val run = (silentRunCycles[key] ?: 0) + 1
        // TWO gates, and they measure different things. The cycle count bounds
        // the cost of proof; the warm-up bounds the boot window, which is a
        // duration and cannot be counted in cycles because a cycle is 1.65 s on
        // one vehicle and 2.55 s on another. Inert for a proven pair by the
        // inequality asserted in `a proven pair is only given up on once the
        // app has already drawn it offline`.
        val warmedUp = now - since >= UNPROVEN_SILENCE_WARMUP_MS
        if (run < limit || !warmedUp) {
            silentRunCycles = silentRunCycles + (key to run)
        } else {
            // Suppressed, or — for a pair that already was — its failed
            // re-probe restamped. The stamp is "last put on the wire", and
            // restarting it is what staggers the pairs: the next one due gets
            // the next cycle rather than sharing this one.
            //
            // The run's count and clock are deliberately NOT cleared. A
            // suppressed pair leaves this state by exactly two doors, both of
            // which clear them — a re-probe that answers ([rememberAnswer]) and
            // [reset] — so a clear here is a line no implementation could
            // falsify.
            suppressedAtMs = suppressedAtMs + (key to now)
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

    /** How one exchange settled. Only the loop reads it, for suppression. */
    private enum class Outcome {
        /** A reply with the expected opcode landed inside the window. */
        ANSWERED,

        /** The request went out and nothing came back — the node's own silence. */
        SILENT,

        /**
         * The write threw: the request never reached the wire, so this is our
         * link failing and not the node refusing. Deliberately distinct from
         * [SILENT], because suppression must not blame a node for it.
         */
        LINK_FAILED
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
    ): Outcome {
        val waiter = CompletableDeferred<Unit>()
        pending = Pending(request.expectedOpcode, request.consume, waiter)
        var linkFailed = false
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
            linkFailed = true
            // Pace the loop with the budget the reply would have cost so a dead
            // link cannot spin the CPU while the session's watchdog decides.
            delay(replyTimeoutMs)
            null
        }
        if (answered != null) return Outcome.ANSWERED
        // Timed out (or the write failed). Disarm FIRST, so a late reply lands
        // in the quiet window below and is dropped rather than credited to
        // whatever we ask next.
        pending = null
        request.onSilence()
        delay(lateReplyGuardMs)
        return if (linkFailed) Outcome.LINK_FAILED else Outcome.SILENT
    }

    override fun handshakeCommands(): List<ByteArray> = emptyList<ByteArray>().also {
        // A gateway reply is bare and therefore needs the serial loop's
        // pending expectation; it cannot be sent as a fire-and-forget write.
        // The post-subscription handshake is nevertheless the right moment to
        // arm this one-shot, so a controller is asked once per connection.
        armControllerConfigRequests()
    }

    /**
     * Empty: this protocol drives itself through [runPollLoop]. `ConnectionSession`
     * never reads this for a [SerialPollSource], and an empty list is the honest
     * answer for "there is no fixed burst to fire" if anything else ever does.
     */
    override fun pollCommands(): List<ByteArray> = emptyList()

    override val controllerCount: Int get() = controllers.size
    override val packCount: Int get() = packs.size
    override val controllerConfigCount: Int get() = controllers.size

    override fun latestControllerConfig(controllerIndex: Int): VescSetupConfig? =
        controllers.getOrNull(controllerIndex)?.let { controllerConfigs[it.globalIndex] }

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
        lastDecodeAtMs = lastDecodeAtMs + (global to nowMs())
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
        // arrived immediately before that same primary's `GET_VALUES`. That
        // soundness lasted exactly one cycle even then: from cycle 2 onward the
        // pre-task plan had the same staleness, so no plan shape ever made this
        // line correct — the grouping below did not introduce the bug, it made
        // it reachable on the first cycle too. Under this plan every SETUP is
        // answered before any `GET_VALUES` is even sent, so `perUnit[global]`
        // at this instant is LAST cycle's decode —
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
        controllerConfigs = emptyMap()
        configRequests = emptyList()
        configRequestsArmed = false
        // Every "how long since" this protocol holds is about THIS connection.
        // The next one may be a different vehicle: carried over,
        // [hostedBmsAtMs] would hold a substitute off a gateway that hosts
        // nothing, and [contentlessSinceMs] would count a warm-up that already
        // elapsed on somebody else's head unit as served.
        hostedBmsAtMs = emptyMap()
        contentlessSinceMs = emptyMap()
        // [lastDecodeAtMs] is not cleared, for the reason given on
        // [valuesRequest]'s `onSilence`: `motion` above is, and the two are only
        // ever read together.
        overlays = emptyMap()
        tripBaselineKm = emptyMap()
        // "Re-probe on reconnect, always." A node's refusal is evidence about
        // the session it was collected in and nothing more: the next connection
        // may be a different vehicle, or the same one with the uBox now awake,
        // and a rider who power-cycles their scooter after a bad ride is
        // entitled to have that work. Cheap, too — a reconnect already pays a
        // handshake, so the few cycles it costs to re-learn the dead opcodes
        // are lost in it.
        silentRunCycles = emptyMap()
        silentSinceMs = emptyMap()
        suppressedAtMs = emptyMap()
        everAnswered = emptySet()
    }
}
