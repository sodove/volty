package ru.sodovaya.volty.presentation.vehicle

import ru.sodovaya.volty.domain.model.VehicleData
import kotlin.math.abs

/**
 * # What the composer has *observed*, and the duplicate-pack heuristic it feeds
 * (G2 Task 4, `G §5`, `G §9.2`, `01-linking §4`)
 *
 * Two battery sources of one vehicle can be the same physical pack reached two
 * ways. Counting it twice doubles the vehicle's capacity and energy on the
 * dashboard, so the composer offers to mark the pair as one logical battery
 * (a shared `aliasGroup`) instead of double-counting.
 *
 * Everything here is a **pure function of a draft and a bounded observation
 * window**, for the reason `VehicleComposer.kt` states: Compose is not
 * unit-testable in this repo, so a rule a rider will act on cannot live in a
 * `@Composable`. It is also why the window is a plain list of snapshots rather
 * than a live flow — a heuristic over "several samples" is exactly the shape
 * that wedges `runTest` if it is written as a delayed loop.
 *
 * ## The false positive is the failure mode, not the false negative
 *
 * `G §9.2` names the risk directly: **two genuinely similar packs must not be
 * nagged about.** A rider with two identical 20S packs in parallel has an
 * ordinary build, not a mistake, and a prompt they must dismiss on every visit
 * teaches them to dismiss prompts — which is the failure Part F spent a whole
 * task removing from the alarm engine. A missed duplicate, by contrast, costs a
 * wrong capacity figure on a configuration the rider can still fix by hand,
 * because [VehicleDraft.groupPacks] is offered unconditionally on every pack
 * card. So every gate below is chosen to make a false positive hard, in the
 * knowledge that it also makes some real duplicates invisible.
 *
 * ## The four gates, and why each one is there
 *
 * A pair (A, B) is flagged only when **all** hold:
 *
 *  1. **Both sources have a link at all.** A blank address is not a path, so a
 *     pack that has one cannot be the *alternate* path to anything —
 *     [ComposerIssue.BlankAddress] is what that source needs to hear.
 *  2. **The two sources are two PATHS, not two devices** ([twoPaths]). This is
 *     the gate that does the real work, and it has two arms because
 *     `01-linking §4` describes two shapes:
 *
 *      - **across links** — exactly one side is reached through a gateway
 *        ([VehicleDraft.isHostedPack]), the other over its own BLE link. The
 *        product owner's ANT: direct while parked, hosted by the head unit while
 *        riding;
 *      - **on one gateway link** — exactly one side carries `canId == null`,
 *        i.e. it is the endpoint's *own* answer, and the other is a node behind
 *        it. That is `01-linking §4`'s named hardware conflict verbatim:
 *        "attaching a real VESC-BMS on a CAN id the head unit already emulates".
 *        One battery; one path is the gateway's emulation, the other the real
 *        node on its bus.
 *
 *     What it keeps out is the §9.2 case: two packs that are **two devices**
 *     wired in parallel. Their terminals are the same electrical node, so at
 *     rest their voltages agree to within the two BMS' calibration offsets, both
 *     currents are zero, and neither path reports a serial number. **No
 *     telemetry the app can read at compose time separates that from one pack
 *     seen twice** — see the band section below, which is why this gate and not
 *     a tighter band is what carries the case. Two direct BMS links are two
 *     devices; so are two distinct CAN nodes behind one gateway.
 *
 *     It is also what keeps **two branches of one Begode wheel** out
 *     (`01-linking §3` archetype 3: two real packs over a single BLE address).
 *     They share an address and **both carry `canId == null`**, so neither arm
 *     admits them. Note the coupling this creates: before the second arm
 *     existed, [VehicleDraft.isHostedPack] read only the address, so a
 *     same-address pair could never reach the comparison at all and a separate
 *     same-address gate was provably dead code (the mutation sweep found it).
 *     That is no longer true — the exclusion now rests on `canId`, and `two
 *     branches of one wheel are not a duplicate` is what pins it.
 *  3. **Same series-cell count, both known.** A 20S and a 24S pack at the same
 *     voltage are not the same battery. Unknown on either side is not a match —
 *     the composer never guesses at a fact it has not been told.
 *  4. **Voltage agreement over [DUPLICATE_SAMPLES] paired samples**, within
 *     [DUPLICATE_BAND_V_PER_CELL] per series cell on **every** one of them.
 *
 * ## The window and the band, and the reasoning that picked them
 *
 * **[DUPLICATE_SAMPLES] = 5, and they must be *paired*** — both packs online in
 * the same [VehicleData] emission, so index i of the comparison is one instant,
 * not two readings taken minutes apart while the pack discharged between them.
 * Five because one or two samples of agreement happen for free whenever two
 * discharging packs' voltage curves cross; five consecutive means they are
 * *tracking*, not crossing. At the poll cadence this is a few seconds — soon
 * enough to appear while the rider is still composing, long enough that a
 * crossing cannot produce it. Ten would have been safer against a slow crossing
 * and is rejected because the offer would then usually arrive after the rider
 * has saved and left.
 *
 * **[DUPLICATE_BAND_V_PER_CELL] = 0.01 V, i.e. 0.20 V on a 20S 72 V pack** —
 * about 0.28 % of pack voltage. Per cell rather than absolute so it means the
 * same thing on a 24 V wheel as on a 72 V scooter, which an absolute band cannot
 * (`a 10S pair is judged on a 10S band` pins the scaling, not just the value).
 * The floor is set by what two readings of ONE pack legitimately differ by: the
 * two paths quantise differently (an ANT reports pack voltage in hundredths of a
 * volt, a hosted VESC-BMS re-serves it in thousandths) and are polled by
 * independent loops, so a sub-second skew during a current step shows up as a
 * few tens of millivolts.
 *
 * **The ceiling is where the honesty is.** Two *different* packs that are not
 * hard-paralleled differ by tenths of a volt and up — but two that ARE
 * paralleled differ only by their BMS' calibration offsets, roughly ±0.07 V to
 * ±0.36 V on a 72 V pack, and that range **straddles this band**. So there is no
 * value of this constant that separates "one pack seen twice" from "two real
 * packs in parallel": in part of the range they are genuinely indistinguishable
 * to us. Gate 2 is what carries that case, and where gate 2 admits a pair we
 * cannot separate, the OFFER carries it — `composer_issue_duplicate_pack` states
 * what accepting costs if the rider is looking at two real packs, because
 * `PackAggregator.collapseAliases` drops one member from the capacity sum and a
 * wrong accept therefore **halves reported capacity**, the exact mirror of the
 * double-count this feature exists to prevent. An informed accept is worth more
 * than a better constant, because a better constant does not exist.
 *
 * Both edges are pinned by tests that fail if the band moves by a factor of two
 * in either direction (`a parallel pair inside the BMS calibration spread is
 * left alone` at 0.25 V on 20S, and `a real duplicate is caught` at 0.01 V) —
 * the first sweep pinned only `0.1f` and `0.0001f`, two orders apart, which
 * proved the band was somewhere between them rather than at the value argued
 * for here.
 *
 * ## Identical reported serial (`G §5`) is not implemented, deliberately
 *
 * The spec offers it as an alternative to voltage tracking, and it would be the
 * strongest signal there is. **No decoder in this app reads one**: neither
 * [ru.sodovaya.volty.domain.model.BmsData] nor the hosted-BMS path
 * (`01-linking §2`: "battery only — v/i/cells/temps/soc") carries a serial or a
 * device id, so there is nothing to compare. Inventing a proxy — the BLE address,
 * say — would compare the two things that are *by definition* different on the
 * two paths. It is left out rather than approximated; see this task's report for
 * what reading one would take.
 */

/** Paired samples that must agree before a duplicate is even suggested. */
const val DUPLICATE_SAMPLES: Int = 5

/** Per-series-cell voltage band the two paths must stay inside on every sample. */
const val DUPLICATE_BAND_V_PER_CELL: Float = 0.01f

/**
 * What the composer has observed of the vehicle it is editing, keyed by draft
 * row ([PackDraft.key] / [ControllerDraft.key]).
 *
 * Bounded by construction: [packVoltagePairs] never holds more than
 * [DUPLICATE_SAMPLES] snapshots and the sets only ever grow to the number of
 * sources. Nothing here is persisted — it describes this sitting at the form,
 * and a rule that needed more history than one sitting would be a rule the
 * rider could not see the evidence for.
 *
 * A source the rider has just added has no key in any of these: it has no
 * stored row to have been observed as. That is correct rather than a gap —
 * nothing has been learned about it yet, and both rules below say nothing when
 * they have learned nothing.
 */
data class ComposerTelemetry(
    /**
     * The series-cell count each pack has actually reported (the length of its
     * cell-voltage list), which is what gate 3 compares. Separate from
     * [PackDraft.cellCount]: that one is the stored, auto-filled value and can
     * be null for a pack whose frames have never been seen.
     */
    val packSeriesCells: Map<String, Int> = emptyMap(),
    /**
     * The last [DUPLICATE_SAMPLES] snapshots in which **at least two** packs
     * were online at once, oldest first — each mapping pack key to the pack
     * voltage read in that emission. Snapshots with fewer than two packs are
     * not recorded: they cannot inform any pair, and letting them into the
     * window would flush out the paired history a pair had built up.
     */
    val packVoltagePairs: List<Map<String, Float>> = emptyList(),
    /** Sources that have been online at least once — packs and controllers alike. */
    val sourcesSeen: Set<String> = emptySet(),
    /**
     * Controllers that have reported something a motor controller reports: a
     * live DC rail, rotation, or motor current. See [PHANTOM] on
     * [ComposerIssue.PhantomGatewayController] for why a controller that
     * answers with all zeroes deliberately does **not** count as one.
     */
    val controllersWithMotion: Set<String> = emptySet()
)

/**
 * Fold one [VehicleData] emission into the window.
 *
 * Pure and total, and the only place a live sample becomes something a rule can
 * read. Rows are matched to draft keys through [PackDraft.origin] /
 * [ControllerDraft.origin] — by the **stored index**, which is what
 * `VehicleConnection` files samples under — so a draft row the rider added in
 * this session, or one whose stored twin has gone, simply collects nothing.
 */
fun ComposerTelemetry.observing(draft: VehicleDraft, data: VehicleData): ComposerTelemetry {
    val packKeyByIndex = draft.packs.mapNotNull { d -> d.origin?.let { it.index to d.key } }.toMap()
    val controllerKeyByIndex =
        draft.controllers.mapNotNull { d -> d.origin?.let { it.index to d.key } }.toMap()

    val onlinePacks = data.packs.filter { it.isOnline }
    val snapshot = onlinePacks.mapNotNull { ps ->
        packKeyByIndex[ps.pack.index]?.let { key -> key to ps.data.voltage }
    }.toMap()

    val cells = packSeriesCells.toMutableMap()
    for (ps in onlinePacks) {
        val key = packKeyByIndex[ps.pack.index] ?: continue
        if (ps.data.cellVoltages.isNotEmpty()) cells[key] = ps.data.cellVoltages.size
    }

    val seen = sourcesSeen.toMutableSet()
    onlinePacks.forEach { ps -> packKeyByIndex[ps.pack.index]?.let { seen += it } }
    data.controllers.filter { it.isOnline }
        .forEach { cs -> controllerKeyByIndex[cs.controller.index]?.let { seen += it } }

    val moving = controllersWithMotion.toMutableSet()
    for (cs in data.controllers) {
        if (!cs.isOnline || !cs.data.reportsMotion()) continue
        controllerKeyByIndex[cs.controller.index]?.let { moving += it }
    }

    return ComposerTelemetry(
        packSeriesCells = cells,
        packVoltagePairs =
            if (snapshot.size < 2) packVoltagePairs
            else (packVoltagePairs + listOf(snapshot)).takeLast(DUPLICATE_SAMPLES),
        sourcesSeen = seen,
        controllersWithMotion = moving
    )
}

/**
 * Whether this sample looks like it came from something driving a motor.
 *
 * A live DC rail alone is enough — a parked ESC still reports its pack voltage,
 * and demanding rotation would call every stationary scooter a display. What it
 * refuses is the **all-zero answer**, which is the case `G §9.3` is about: a
 * head unit that answers `GET_VALUES` with a 0 V rail is not reporting a
 * measurement, and `MotionAggregator` folding that zero into an `average()`
 * takes a three-controller vehicle's dashboard voltage to two thirds of the real
 * one — and feeds that number to the alert engine.
 */
private fun ru.sodovaya.volty.domain.model.ControllerData.reportsMotion(): Boolean =
    inputVoltageV > 0f || eRpm != 0f || motorCurrentA != 0f || speedKmh != 0f

/**
 * Every pair of battery sources that looks like one physical pack, in draft
 * order (lower-indexed pack first within a pair, pairs by first member).
 *
 * See the file doc for the four gates and for the numbers. Pairs that already
 * share an `aliasGroup` are not reported: the rider has answered, and repeating
 * the question is the nag `G §9.2` forbids.
 */
/**
 * Gate 2 — whether [a] and [b] could be two **paths** to one battery rather than
 * two batteries. See the file doc for both arms and for what each keeps out.
 *
 * On one address the question is answered by `canId` alone, and specifically by
 * *exactly one* of the two being null: `canId == null` on a gateway link means
 * "the endpoint answers this itself" (`VescGatewayProtocol.frameFor` sends the
 * request unwrapped), so a null/non-null pair is the gateway's own emulation
 * beside the real node behind it. **Two distinct non-null ids are two distinct
 * CAN nodes** — two devices, which cannot be one BMS — and two nulls are two
 * branches of one wheel. Reading this as "different `canId`" instead would admit
 * the two-CAN-node case, and two VESC-BMS in parallel on one bus is an ordinary
 * build whose readings track for the same reason two direct packs do.
 */
private fun VehicleDraft.twoPaths(a: PackDraft, b: PackDraft): Boolean =
    if (a.address == b.address) (a.canId == null) != (b.canId == null)
    else isHostedPack(a) != isHostedPack(b)

fun duplicatePackSuspects(
    draft: VehicleDraft,
    telemetry: ComposerTelemetry
): List<ComposerIssue.DuplicatePack> {
    val out = mutableListOf<ComposerIssue.DuplicatePack>()
    for (i in draft.packs.indices) {
        for (j in i + 1 until draft.packs.size) {
            val a = draft.packs[i]
            val b = draft.packs[j]
            if (a.address.isBlank() || b.address.isBlank()) continue
            if (a.aliasGroup != null && a.aliasGroup == b.aliasGroup) continue
            if (!draft.twoPaths(a, b)) continue
            val cellsA = telemetry.packSeriesCells[a.key] ?: continue
            val cellsB = telemetry.packSeriesCells[b.key] ?: continue
            if (cellsA != cellsB) continue
            val paired = telemetry.packVoltagePairs.mapNotNull { snap ->
                val va = snap[a.key] ?: return@mapNotNull null
                val vb = snap[b.key] ?: return@mapNotNull null
                va to vb
            }
            if (paired.size < DUPLICATE_SAMPLES) continue
            val band = DUPLICATE_BAND_V_PER_CELL * cellsA
            if (paired.all { (va, vb) -> abs(va - vb) <= band }) {
                out += ComposerIssue.DuplicatePack(a.key, b.key)
            }
        }
    }
    return out
}
