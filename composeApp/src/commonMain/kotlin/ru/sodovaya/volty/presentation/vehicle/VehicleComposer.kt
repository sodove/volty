package ru.sodovaya.volty.presentation.vehicle

import ru.sodovaya.volty.data.ble.ProtocolKind
import ru.sodovaya.volty.data.ble.controllerMotionSupported
import ru.sodovaya.volty.data.ble.protocolKind
import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.Controller
import ru.sodovaya.volty.domain.model.ControllerType
import ru.sodovaya.volty.domain.model.MotorConfig
import ru.sodovaya.volty.domain.model.Pack
import ru.sodovaya.volty.domain.model.Vehicle

/**
 * # The composer's model (Part G2 Task 2)
 *
 * A vehicle is **N sources** — controllers and battery packs — and this file is
 * the whole of the editable, testable representation of that: the draft the
 * screen mutates, the rules that decide what a draft is allowed to be, and the
 * projection back onto [Vehicle]. Compose is not unit-testable in this repo (no
 * Robolectric, no `compose-ui-test`, no instrumented source set), so every
 * decision lives here and `VehicleEditScreen` stays a renderer.
 *
 * Three things it must get right, in the order they bite:
 *
 *  1. **A vehicle must keep at least one source.** [Vehicle]'s own `init`
 *     throws otherwise, and the UI must never be able to reach that exception —
 *     so removal is *refused* by [VehicleDraft.removePack] / [removeController]
 *     and advertised as [VehicleDraft.canRemoveSource] for the screen to
 *     disable the control with. Nothing catches anything.
 *  2. **It must never emit a config the connection layer refuses.** See
 *     [ComposerIssue] and [validate]: two blocking cases (both of which
 *     `planLinks` itself throws on) and four advisory ones.
 *  3. **The derived-battery rule and the rider's override** — see
 *     [DerivedBatteryChoice].
 */

// ---------------------------------------------------------------------------
// Derived battery
// ---------------------------------------------------------------------------

/**
 * A controller's "provides a derived battery" setting as the composer holds it:
 * the rule's own answer, or the rider's override of it.
 *
 * **The rule (`G §6`):** `providesDerivedBattery` defaults **true** for a
 * controller iff the vehicle has no other battery source covering it, else
 * false — see [VehicleDraft.derivedBatteryDefault]. So adding a BMS turns a
 * controller's derived battery off and removing that BMS turns it back on,
 * recomputed on every change to the source set.
 *
 * **The collision the spec does not settle.** A recompute and a rider's choice
 * are both authoritative and they disagree. The resolution here:
 *
 *  - [AUTO] follows the rule and is recomputed on every source-set change;
 *  - [ON] / [OFF] are the rider's word and a recompute **never** overwrites
 *    them. Toggling always lands on [ON] or [OFF] — never back on [AUTO] —
 *    because a rider who touched the switch has answered the question, and
 *    "answered" is not a state a later source change may quietly revoke.
 *
 * **How an override survives being persisted.** [Controller.providesDerivedBattery]
 * stays a plain resolved `Boolean` on the stored model — the connection layer
 * (`KableBmsRepository.createProtocol`) reads it directly and must not have to
 * resolve anything, and no storage migration is spent on this. The tri-state is
 * therefore reconstructed on load by [derivedBatteryChoiceFor], which compares
 * the stored value against the rule's answer *for the source set it was stored
 * with*: **disagreement is proof of an override**, agreement is indistinguishable
 * from never having been touched and so re-seeds as [AUTO].
 *
 * That inference is exact except in one case, stated rather than hidden: a rider
 * who explicitly chooses the value the rule already produced, saves, and reopens
 * the form comes back as [AUTO]. Distinguishing that would take a nullable
 * column, and the case is degenerate — the only way to reach it is to toggle a
 * switch off and back on within one session, whose net effect on the stored
 * vehicle is nothing at all. Every override that *changed* something survives.
 *
 * (The nullable "unset means follow the default" convention this codebase
 * already uses — [Vehicle.yieldBmsToHeadUnit] — is the same idea; it is applied
 * here on the composer's side of the boundary instead of on `Controller`,
 * because unlike `yieldBmsToHeadUnit` this field has a live reader in the data
 * layer that would have to learn to resolve null.)
 */
enum class DerivedBatteryChoice { AUTO, ON, OFF }

/**
 * The tri-state to seed a loaded [Controller] with. [default] is
 * [VehicleDraft.derivedBatteryDefault] computed over the vehicle **as loaded**.
 */
fun derivedBatteryChoiceFor(controller: Controller, default: Boolean): DerivedBatteryChoice = when {
    controller.providesDerivedBattery == default -> DerivedBatteryChoice.AUTO
    controller.providesDerivedBattery -> DerivedBatteryChoice.ON
    else -> DerivedBatteryChoice.OFF
}

// ---------------------------------------------------------------------------
// The draft
// ---------------------------------------------------------------------------

/**
 * One battery pack being edited.
 *
 * [key] is identity *within an editing session* — stable across reorder and
 * removal, which `index` is not (indices are renumbered from the draft's order
 * on save, see [VehicleDraft.toPacks]). The screen addresses a row by [key];
 * nothing persists it.
 *
 * [origin] is the stored [Pack] this draft was seeded from, or null for a pack
 * the rider just added. [toPack] `copy()`s it rather than rebuilding, for the
 * reason Task 1 rewrote `onSave`: a field this draft does not model is then
 * **preserved by default** instead of reset by default. Adding a field to
 * [Pack] requires no change here.
 */
data class PackDraft(
    val key: String,
    val label: String,
    val bmsType: BmsType,
    val address: String,
    val cellCount: Int? = null,
    val canId: Int? = null,
    val aliasGroup: String? = null,
    val origin: Pack? = null
) {
    val protocolKind: ProtocolKind get() = bmsType.protocolKind()

    fun toPack(index: Int): Pack =
        (origin ?: Pack(index = index, label = label, bmsType = bmsType, bmsAddress = address)).copy(
            index = index,
            label = label,
            bmsType = bmsType,
            bmsAddress = address,
            cellCount = cellCount,
            canId = canId,
            aliasGroup = aliasGroup
        )
}

/**
 * One controller being edited. See [PackDraft] for what [key] and [origin] are
 * for; [derivedBattery] is documented on [DerivedBatteryChoice].
 */
data class ControllerDraft(
    val key: String,
    val label: String,
    val controllerType: ControllerType,
    val address: String,
    val canId: Int? = null,
    val motor: MotorConfig = MotorConfig(),
    val derivedBattery: DerivedBatteryChoice = DerivedBatteryChoice.AUTO,
    val origin: Controller? = null
) {
    val protocolKind: ProtocolKind get() = controllerType.protocolKind()

    fun toController(index: Int, providesDerivedBattery: Boolean): Controller =
        (origin ?: Controller(
            index = index,
            label = label,
            controllerType = controllerType,
            address = address
        )).copy(
            index = index,
            label = label,
            controllerType = controllerType,
            address = address,
            canId = canId,
            motor = motor,
            providesDerivedBattery = providesDerivedBattery
        )
}

/**
 * The editable source set: N packs and N controllers, in the order the screen
 * shows them.
 *
 * [nextKey] is a monotonic counter so a freshly added source gets a [key] no
 * other row in this session holds — deterministic, unlike a random id, so the
 * whole draft stays comparable in tests.
 */
data class VehicleDraft(
    val packs: List<PackDraft> = emptyList(),
    val controllers: List<ControllerDraft> = emptyList(),
    val nextKey: Int = 0
) {
    val sourceCount: Int get() = packs.size + controllers.size

    /**
     * Whether *any* source may be removed. False at exactly one source, because
     * [Vehicle]'s `init` requires one — the screen disables the control, and
     * [removePack] / [removeController] refuse anyway. Prevention on both
     * sides; no exception is ever thrown and none is ever caught.
     */
    val canRemoveSource: Boolean get() = sourceCount > 1

    /**
     * The `G §6` rule's own answer for every controller in this draft: a
     * controller backs a derived battery iff the vehicle has no battery source
     * of its own.
     *
     * Vehicle-wide rather than per-link, which is what "covering it" means for
     * every archetype in `01-linking §3` (an added BMS makes a wheel's derived
     * battery redundant; a BMS on its own link covers the VESC beside it) — and
     * what the runtime already does: `KableBmsRepository.createProtocol` falls
     * back to `vehicle.packs.isNullOrEmpty()`, and
     * [ru.sodovaya.volty.domain.model.needsDerivedBattery] is this same test.
     */
    val derivedBatteryDefault: Boolean get() = packs.isEmpty()

    /** [controller]'s effective setting: the rider's word if they gave one, else the rule's. */
    fun resolvedDerivedBattery(controller: ControllerDraft): Boolean =
        when (controller.derivedBattery) {
            DerivedBatteryChoice.AUTO -> derivedBatteryDefault
            DerivedBatteryChoice.ON -> true
            DerivedBatteryChoice.OFF -> false
        }

    /** Pack indices are renumbered from the draft's own order — see [toControllers]. */
    fun toPacks(): List<Pack> = packs.mapIndexed { i, p -> p.toPack(i) }

    /**
     * Controller indices are renumbered from the draft's order, 0-based and
     * contiguous.
     *
     * Renumbering is the point of allowing reorder at all, and it is also a
     * repair: `VehicleConnection` matches a source by its index and
     * `List<Pack>.expandedTo` numbers synthesised slots after the highest
     * existing one, so a gap or a duplicate leaves a slot permanently
     * unreachable. A draft can therefore never emit one.
     */
    fun toControllers(): List<Controller> =
        controllers.mapIndexed { i, c -> c.toController(i, resolvedDerivedBattery(c)) }
}

/** The draft [vehicle] loads as. Round-trips exactly: `draftOf(v).toPacks() == v.packs`. */
fun draftOf(vehicle: Vehicle): VehicleDraft {
    val default = vehicle.packs.isEmpty()
    return VehicleDraft(
        packs = vehicle.packs.mapIndexed { i, p ->
            PackDraft(
                key = "p$i",
                label = p.label,
                bmsType = p.bmsType,
                address = p.bmsAddress,
                cellCount = p.cellCount,
                canId = p.canId,
                aliasGroup = p.aliasGroup,
                origin = p
            )
        },
        controllers = vehicle.controllers.mapIndexed { i, c ->
            ControllerDraft(
                key = "c$i",
                label = c.label,
                controllerType = c.controllerType,
                address = c.address,
                canId = c.canId,
                motor = c.motor,
                derivedBattery = derivedBatteryChoiceFor(c, default),
                origin = c
            )
        }
    )
}

// ----- mutations, all pure and all total (an impossible request is a no-op) -----

fun VehicleDraft.addPack(
    bmsType: BmsType,
    address: String,
    label: String = ""
): VehicleDraft = copy(
    packs = packs + PackDraft(
        key = "p-new-$nextKey",
        label = label.ifBlank { "Pack ${packs.size + 1}" },
        bmsType = bmsType,
        address = address
    ),
    nextKey = nextKey + 1
)

fun VehicleDraft.addController(
    controllerType: ControllerType,
    address: String,
    label: String = ""
): VehicleDraft = copy(
    controllers = controllers + ControllerDraft(
        key = "c-new-$nextKey",
        label = label.ifBlank { "Controller ${controllers.size + 1}" },
        controllerType = controllerType,
        address = address
    ),
    nextKey = nextKey + 1
)

/** Refuses to remove the last source — see [VehicleDraft.canRemoveSource]. */
fun VehicleDraft.removePack(key: String): VehicleDraft =
    if (!canRemoveSource || packs.none { it.key == key }) this
    else copy(packs = packs.filterNot { it.key == key })

/** Refuses to remove the last source — see [VehicleDraft.canRemoveSource]. */
fun VehicleDraft.removeController(key: String): VehicleDraft =
    if (!canRemoveSource || controllers.none { it.key == key }) this
    else copy(controllers = controllers.filterNot { it.key == key })

fun VehicleDraft.movePack(from: Int, to: Int): VehicleDraft =
    copy(packs = packs.moved(from, to))

fun VehicleDraft.moveController(from: Int, to: Int): VehicleDraft =
    copy(controllers = controllers.moved(from, to))

/** Out-of-range or same-slot moves are no-ops, so a screen can drag freely. */
private fun <T> List<T>.moved(from: Int, to: Int): List<T> {
    if (from == to || from !in indices || to !in indices) return this
    val out = toMutableList()
    out.add(to, out.removeAt(from))
    return out
}

fun VehicleDraft.updatePack(key: String, edit: (PackDraft) -> PackDraft): VehicleDraft =
    copy(packs = packs.map { if (it.key == key) edit(it) else it })

fun VehicleDraft.updateController(key: String, edit: (ControllerDraft) -> ControllerDraft): VehicleDraft =
    copy(controllers = controllers.map { if (it.key == key) edit(it) else it })

/**
 * Positional counterpart of [updateController], for the one editor that is
 * positional rather than keyed: the legacy Motor card, which has always edited
 * `controllers[0]` (`G1` gave a vehicle exactly one controller). A no-op on a
 * vehicle with no controller, which is what a pack-only vehicle must see.
 */
fun VehicleDraft.updateControllerAt(index: Int, edit: (ControllerDraft) -> ControllerDraft): VehicleDraft =
    if (index !in controllers.indices) this
    else copy(controllers = controllers.mapIndexed { i, c -> if (i == index) edit(c) else c })

// ---------------------------------------------------------------------------
// Validation
// ---------------------------------------------------------------------------

/**
 * Something wrong with a draft, in the terms the connection layer would fail
 * in. The composer's whole job here is that a rider never saves a config that
 * looks fine and then dies at connect time with no explanation.
 *
 * **[blocking] draws a deliberate line**, and it is not "how bad is it":
 *
 *  - **blocking** = the rider's configuration *contradicts itself*. Two things
 *    that cannot both be true of one physical BLE link. `planLinks` throws on
 *    exactly these two, no future version of volty will accept them, and the
 *    save is refused.
 *  - **advisory** = the configuration describes real hardware correctly and
 *    volty cannot read it *yet* (or reads only part of it). Refusing the save
 *    would stop a rider describing their own scooter because a decoder has not
 *    been written; telling them is the right answer. Parts E and H close two of
 *    these by writing the decoder, with no change here.
 *
 * Every case below was found by reading `planLinks`, `LinkSpec.isGatewayLink`,
 * `KableBmsRepository.createProtocol` and `controllerMotionProtocol` — the full
 * enumeration is in this task's report.
 */
sealed interface ComposerIssue {
    /** Whether a save must be refused. See the interface doc for what draws this line. */
    val blocking: Boolean

    /**
     * **`planLinks` throws** (`resolveLinkKind`): one BLE link speaks exactly
     * one wire protocol, so one address may not resolve to two [ProtocolKind]s.
     *
     * Note it is *not* limited to direct sources, whatever `A §4.4` implies —
     * CAN-forwarded sources are grouped by address just the same, so two CAN
     * slaves of different kinds behind one gateway conflict too. The one
     * sanctioned pairing is `{VESC, VESC_BMS}` (`C §6`, a gateway hosting its
     * own battery), which resolves to VESC and is not reported here.
     */
    data class ConflictingKinds(val address: String, val kinds: Set<ProtocolKind>) : ComposerIssue {
        override val blocking: Boolean get() = true
    }

    /**
     * **`planLinks` throws** (its `require`): two sources behind one gateway
     * claim the same CAN id. Two nodes cannot share an id on one bus, and the
     * check pools packs and controllers because they share the bus.
     */
    data class DuplicateCanId(val address: String, val canId: Int) : ComposerIssue {
        override val blocking: Boolean get() = true
    }

    /**
     * A source with no BLE address. `planLinks` accepts it — it is just another
     * grouping key — and then nothing can ever be dialled, so the source is
     * permanently offline. It is also how two unrelated sources get silently
     * merged into one "link" at `""` and start conflicting.
     */
    data class BlankAddress(val sourceKey: String) : ComposerIssue {
        override val blocking: Boolean get() = false
    }

    /**
     * A controller whose type volty has no motion decoder for (FarDriver,
     * Kelly). `controllerMotionProtocol` returns null, `createProtocol` falls
     * through to `ProtocolKind.toBmsType()` and that **throws** — taking down
     * the whole connect, not just this link. Advisory because Parts E and H are
     * the fix, and the picker refuses these types for the same reason
     * ([controllerMotionSupported] is the single authority both read).
     */
    data class NoControllerDecoder(val sourceKey: String, val controllerType: ControllerType) : ComposerIssue {
        override val blocking: Boolean get() = false
    }

    /**
     * A `VESC_BMS` pack that is *not* hosted behind a VESC link, so the link
     * itself resolves to `VESC_BMS` — for which `createProtocol(BmsType)`
     * **throws** "VESC BMS protocol decode is not implemented yet".
     *
     * A VESC-BMS is only readable as a gateway's hosted battery (`C §6`), which
     * means a VESC controller at the same address. That is the head-unit shape,
     * and it is not reported.
     */
    data class HostlessVescBms(val sourceKey: String) : ComposerIssue {
        override val blocking: Boolean get() = false
    }

    /**
     * A link that is **gateway-shaped without a gateway protocol**: CAN ids, or
     * more than one controller on one address, on a link that does not speak
     * VESC.
     *
     * `LinkSpec.isGatewayLink` is true for it, but `VescGatewayProtocol` is the
     * only multiplexer that exists — every other arm of
     * `controllerMotionProtocol` ignores `link` entirely. Nothing throws; the
     * extra sources are simply never addressed and sit permanently offline with
     * nothing logged. `ControllerProtocols.kt` states this limitation and asks
     * whoever first lets a rider add a second controller to refuse the pairing.
     * That is this file.
     */
    data class UnroutableGateway(val address: String, val kind: ProtocolKind) : ComposerIssue {
        override val blocking: Boolean get() = false
    }
}

/**
 * Mirror of `planLinks`' private `resolveLinkKind`: the one protocol a link
 * speaks, or null when its sources conflict.
 *
 * Deliberately a *re-statement* rather than a call into `planLinks` with a
 * `try`, because catching the exception the composer exists to prevent is the
 * failure mode, not the fix. The pair is kept honest by a test that runs the
 * real `planLinks` over every draft this file calls valid and over every draft
 * it calls conflicting, and asserts the two agree.
 */
private fun resolveLinkKindOrNull(kinds: Set<ProtocolKind>): ProtocolKind? = when {
    kinds.size == 1 -> kinds.first()
    kinds == setOf(ProtocolKind.VESC, ProtocolKind.VESC_BMS) -> ProtocolKind.VESC
    else -> null
}

private class AddressGroup {
    val packs = mutableListOf<PackDraft>()
    val controllers = mutableListOf<ControllerDraft>()
    val kinds = linkedSetOf<ProtocolKind>()
    val canIds = mutableListOf<Int>()
}

/**
 * Every problem [draft] has, in a stable order (per-source first in draft
 * order, then per-address in first-appearance order) so a screen can render it
 * without sorting and a test can assert on the list.
 *
 * Empty for a well-formed draft, and `issues.none { it.blocking }` is exactly
 * the condition under which the resulting [Vehicle] is guaranteed to survive
 * `planLinks`.
 */
fun validate(draft: VehicleDraft): List<ComposerIssue> {
    val issues = mutableListOf<ComposerIssue>()

    for (p in draft.packs) if (p.address.isBlank()) issues += ComposerIssue.BlankAddress(p.key)
    for (c in draft.controllers) {
        if (c.address.isBlank()) issues += ComposerIssue.BlankAddress(c.key)
        if (!controllerMotionSupported(c.controllerType)) {
            issues += ComposerIssue.NoControllerDecoder(c.key, c.controllerType)
        }
    }

    val byAddress = LinkedHashMap<String, AddressGroup>()
    for (p in draft.packs) {
        val g = byAddress.getOrPut(p.address) { AddressGroup() }
        g.packs += p
        g.kinds += p.protocolKind
        p.canId?.let { g.canIds += it }
    }
    for (c in draft.controllers) {
        val g = byAddress.getOrPut(c.address) { AddressGroup() }
        g.controllers += c
        g.kinds += c.protocolKind
        c.canId?.let { g.canIds += it }
    }

    for ((address, g) in byAddress) {
        val resolved = resolveLinkKindOrNull(g.kinds)
        if (resolved == null) {
            issues += ComposerIssue.ConflictingKinds(address, g.kinds)
            // Everything below reads `resolved`, and a conflicting link has no
            // single answer to read. It is also already blocking, so nothing is
            // lost by not piling advisories on top of it.
            continue
        }
        g.canIds.groupingBy { it }.eachCount()
            .filterValues { it > 1 }.keys.sorted()
            .forEach { issues += ComposerIssue.DuplicateCanId(address, it) }
        if (resolved == ProtocolKind.VESC_BMS) {
            g.packs.forEach { issues += ComposerIssue.HostlessVescBms(it.key) }
        }
        // The gateway triggers of `LinkSpec.isGatewayLink` that a non-VESC link
        // can actually reach. Its third trigger — a hosted VESC_BMS pack — can
        // only occur on a link that resolved to VESC, so it is not tested here.
        if (resolved != ProtocolKind.VESC && (g.canIds.isNotEmpty() || g.controllers.size > 1)) {
            issues += ComposerIssue.UnroutableGateway(address, resolved)
        }
    }
    return issues
}
