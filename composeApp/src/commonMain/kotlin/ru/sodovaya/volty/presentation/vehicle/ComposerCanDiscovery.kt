package ru.sodovaya.volty.presentation.vehicle

import ru.sodovaya.volty.data.ble.hasCanBus
import ru.sodovaya.volty.domain.model.ConnectionState
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.repository.CanScanRefusal

/**
 * # CAN discovery, as the composer models it (G2 Task 5, `G §3` flow 4)
 *
 * `PING_CAN` against a connected head unit answers with a bare list of raw CAN
 * ids and **nothing else** — no names, no roles, no count prefix (`C §10.2`).
 * Everything a rider sees is built here, from that list plus the draft they are
 * editing, and every rule is a pure function so it can be tested without a
 * Compose harness.
 *
 * Three things this file exists to get right:
 *
 *  1. **Never auto-add** (`G §3`, said twice). Discovery produces
 *     [CanCandidate]s — offers. Nothing enters the draft until the rider taps
 *     an add. That is the difference between a composer and a guess, and it is
 *     why [CanScanState.Found] carries the raw ids rather than a mutated draft.
 *  2. **Friendly first, raw behind Advanced** (`G §9.1`). A candidate carries
 *     an [CanCandidate.ordinal] the screen renders as "Контроллер 2"; the id it
 *     came from stays on [CanCandidate.canId] for the advanced view.
 *  3. **Every added source carries its real `canId`.** On a gateway link
 *     `canId == null` means "the head unit answers this itself", and there is
 *     exactly one head unit — two such sources of the same half is
 *     [ComposerIssue.AmbiguousGatewaySource], which saves clean, connects, and
 *     doubles the reported current. So a discovered node is added WITH its id,
 *     and the one candidate that legitimately carries null
 *     ([CanCandidateKind.HOSTED_BATTERY]) is offered as already-taken the moment
 *     the link has such a pack.
 */

// ---------------------------------------------------------------------------
// Candidates
// ---------------------------------------------------------------------------

/**
 * What kind of thing a candidate is — which decides both what a rider may add
 * it as and what `canId` the added source carries.
 *
 * [HOSTED_BATTERY] is **not** something `PING_CAN` reported, and that is stated
 * rather than blurred: the gateway's own battery is not on the CAN bus, it is
 * answered by the head unit itself (`C §6`, `BMS_GET_VALUES` unwrapped), so no
 * probe can ever return an id for it. It is offered alongside the scan because
 * `G §3` flow 4 needs it and because a successful scan is exactly the moment we
 * have proven there is a gateway to host it.
 */
enum class CanCandidateKind { NODE, HOSTED_BATTERY }

/**
 * One device the composer offers after a scan.
 *
 * [ordinal] is the friendly number: the head unit itself is "1", so the first
 * node found is "2". It is derived from the *sorted id list*, not from the
 * draft, so it does not renumber as the rider adds them one at a time — a row
 * that changed its name between two taps is how somebody adds the same uBox
 * twice. Null for [CanCandidateKind.HOSTED_BATTERY], which is not a numbered
 * node at all.
 *
 * [alreadyAdded] is advisory in the strong sense: the screen shows the row (so a
 * rider can see the scan found what they expected) with its add disabled.
 * Hiding it would make a second scan look like it had lost devices.
 */
data class CanCandidate(
    val canId: Int?,
    val kind: CanCandidateKind,
    val ordinal: Int?,
    val alreadyAdded: Boolean
)

/**
 * Turn one `PING_CAN` answer into the offers for [address].
 *
 * Ids are sorted and de-duplicated: the wire order is the firmware's ascending
 * probe order anyway (`C §10.2`), and a duplicated id would offer the same node
 * twice and then collide as [ComposerIssue.DuplicateCanId].
 *
 * The hosted-battery offer is appended last, always — including when the link
 * already has one, in which case it comes back [CanCandidate.alreadyAdded] so
 * the rider is told the slot is taken instead of being handed the one add that
 * produces [ComposerIssue.AmbiguousGatewaySource].
 */
fun canCandidates(draft: VehicleDraft, address: String, ids: List<Int>): List<CanCandidate> {
    val nodes = ids.distinct().sorted().mapIndexed { i, id ->
        CanCandidate(
            canId = id,
            kind = CanCandidateKind.NODE,
            // The head unit is 1.
            ordinal = i + 2,
            alreadyAdded = draft.controllers.any { it.address == address && it.canId == id } ||
                draft.packs.any { it.address == address && it.canId == id }
        )
    }
    val hosted = CanCandidate(
        canId = null,
        kind = CanCandidateKind.HOSTED_BATTERY,
        ordinal = null,
        alreadyAdded = draft.packs.any { it.address == address && it.canId == null }
    )
    return nodes + hosted
}

/**
 * The default label an added source gets.
 *
 * English, matching `VehicleDraft.addController` / `addPack`'s own "Controller
 * N" / "Pack N" defaults — the label is a stored field on the vehicle, not a UI
 * string, and translating what gets written into the database would make a
 * rider's saved vehicle change name with their phone's locale. What the rider
 * *reads* on the candidate row is a `stringResource` in the sheet; this is what
 * lands in the draft when they tap.
 */
fun canCandidateLabel(candidate: CanCandidate, asBattery: Boolean): String = when {
    candidate.kind == CanCandidateKind.HOSTED_BATTERY -> "Hosted battery"
    asBattery -> "Battery CAN ${candidate.canId}"
    else -> "Controller ${candidate.ordinal}"
}

// ---------------------------------------------------------------------------
// When a scan is possible at all
// ---------------------------------------------------------------------------

/**
 * The addresses of links that are **up right now**.
 *
 * Only [ConnectionState.Connected] counts. `Reconnecting` deliberately does not:
 * a scan needs a live link, and offering the button to a rider whose head unit
 * is mid-reconnect produces a failure they can do nothing about. Every address
 * of the active vehicle is returned rather than only its primary, because a
 * multi-link vehicle can have its head unit online and its ANT down.
 *
 * A vehicle-level fold cannot say *which* individual link is online — that lives
 * in `KableBmsRepository.links` and is not exported — so this over-reports on a
 * partially-connected vehicle. Deliberate: `discoverCanIds` re-checks the link's
 * own status and fails with a message, so the cost is a failed tap rather than a
 * wrong add. The alternative was widening `BmsRepository` with a per-link status
 * that only this screen would read.
 */
fun liveLinkAddresses(vehicle: Vehicle?, state: ConnectionState): Set<String> {
    if (state !is ConnectionState.Connected || vehicle == null) return emptySet()
    return (vehicle.controllers.map { it.address } + vehicle.packs.map { it.bmsAddress })
        .filter { it.isNotBlank() }
        .toSet()
}

/**
 * The link a CAN scan would run against, or null when there is none.
 *
 * Two conditions, both necessary: a **controller whose protocol has a CAN bus**
 * ([hasCanBus] — `PING_CAN` is a VESC command and nothing else answers it) at an
 * address the connection currently holds. Null is what the screen turns into
 * "connect to the head unit first" rather than a button that fails.
 *
 * ### It must agree with `discoverCanIds`, and now does so by construction
 *
 * The first version also accepted a live **hosted `VESC_BMS` pack** on the
 * reasoning that a hosted battery implies a gateway. It does not: `planLinks`'
 * `resolveLinkKind` resolves a `{VESC_BMS}`-only address to
 * `ProtocolKind.VESC_BMS`, which `KableBmsRepository.discoverCanIds` refuses —
 * so the button appeared and the tap could only ever fail. (A hosted battery
 * that really is behind a gateway shares its address with a VESC **controller**,
 * and that controller is what the line below finds.)
 *
 * Both layers now read [hasCanBus], so the contradiction cannot be restated.
 * `a scan target's link is always one the repository will scan` asserts the
 * implication across both layers over an exhaustive source set — because each
 * layer had a test pinning its own half, and no fixture spanned the two.
 *
 * Controllers only, and draft order within them: a gateway is a controller.
 */
fun canScanTarget(draft: VehicleDraft, liveAddresses: Set<String>): String? =
    draft.controllers.firstOrNull {
        it.protocolKind.hasCanBus && it.address in liveAddresses
    }?.address

// ---------------------------------------------------------------------------
// The scan's own state
// ---------------------------------------------------------------------------

/**
 * Where a CAN scan stands.
 *
 * [Running] is the whole of the two-and-a-half-second window: the component
 * enters it before suspending on the repository and leaves it only when the call
 * returns, so a screen that renders a spinner on it is showing something for the
 * entire firmware block. It is also the refusal — a second [Running] scan is
 * dropped by the component, because the firmware silently discards a second
 * `PING_CAN` and a rider who tapped twice would otherwise wait out a window that
 * can never answer (`C §10.2`).
 *
 * [Found] carries the **raw ids**, not the candidates: `alreadyAdded` changes
 * every time the rider adds one, so the candidate list is derived on read (see
 * `VehicleEditComponent.State.canCandidates`) and can never go stale against the
 * draft.
 *
 * It also carries the **address the scan actually ran against**, and every add
 * made from it uses that rather than re-reading [canScanTarget]. The target is
 * recomputed from the live connection, so a link dropping between the scan and
 * the tap would otherwise turn an add into a silent no-op — or, worse once the
 * draft grows a second VESC link, put the device on the wrong one.
 */
sealed interface CanScanState {
    data object Idle : CanScanState
    data object Running : CanScanState
    data class Found(val address: String, val ids: List<Int>) : CanScanState
    data class Failed(val refusal: CanScanRefusal) : CanScanState
}
