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
 *
 * **[toPack] names exactly the fields this composer edits, and nothing else.**
 *
 * ### [cellCount] is a rider field again (Task 3), on [aliasGroup]'s shape
 *
 * This used to be read-only, on the premise that `KableBmsRepository`'s
 * `maybePersistCellCount` auto-fill always supplies it. That premise fails for
 * a wheel with no smart BMS: it sends no cell frames at all, so the auto-fill's
 * candidate count is permanently `0` and it never fires — and that is exactly
 * the wheel that cannot derive a rail voltage any other way. So the field is
 * editable again, on precisely [aliasGroup]'s terms below rather than an
 * unconditional member of the `copy()`: writing [cellCount] unconditionally
 * would hand back a stale load-time value the moment the rider touched any
 * OTHER field on this same pack (see [reanchoredTo]), reverting a count the
 * auto-fill had *already* written in from telemetry while this form was open.
 * [cellCountEdited] is the same "once the rider has touched it" gate
 * [aliasEdited] is for the field below, and for the same reason.
 *
 * This is deliberately **not** a guard against the auto-fill overwriting a
 * typed value *later*, while the connection stays open — the INTENT is that a
 * smart-BMS wheel's own measurement wins over whatever the rider typed (forty
 * measured cells beat a rider's memory). That correction is not guaranteed to
 * happen quickly, though, and stating it honestly matters more than the
 * pattern being tidy: `KableBmsRepository.lastPersistedCellCount` de-dupes by
 * (vehicle, count) pair and is **never reset**, so once the auto-fill has
 * already persisted a given count once, a rider's later conflicting save
 * sticks until the wheel reports a genuinely DIFFERENT count (or the app
 * process restarts) — not merely until the next sample. What
 * [cellCountEdited] protects is narrower and holds regardless of that: THIS
 * save must not silently revert a fresher stored value with a stale one; it
 * is never persisted and never reaches [Pack].
 *
 * ### [aliasGroup] is editable since Task 4, and [aliasEdited] is the same shape
 *
 * Grouping two battery sources as one physical pack (`G §5`, `01-linking §4`) is
 * knowledge only the rider has, so the composer had to become a writer of this
 * field too. Making it an unconditional member of the `copy()` below would have
 * inverted the guarantee above for it: a stale load-time value would be written
 * back over whatever the stored row had grown underneath the form.
 *
 * So the rule is stated the other way round — **the draft writes this field only
 * once the rider has touched it**. Until then it still comes off [origin], i.e.
 * off the freshly-read row, exactly like [cellCount] now works. [aliasEdited] is
 * that "once", per pack rather than per form ([VehicleEditComponent.State.packsEdited]
 * is set by any edit at all, including renaming an unrelated pack, and would
 * have made a rename claim ownership of the grouping).
 */
data class PackDraft(
    val key: String,
    val label: String,
    val bmsType: BmsType,
    val address: String,
    val cellCount: Int? = null,
    /** Whether the RIDER set [cellCount] on this pack — see the class doc. */
    val cellCountEdited: Boolean = false,
    val canId: Int? = null,
    val aliasGroup: String? = null,
    /** Whether the RIDER set [aliasGroup] on this pack — see the class doc. */
    val aliasEdited: Boolean = false,
    val origin: Pack? = null
) {
    val protocolKind: ProtocolKind get() = bmsType.protocolKind()

    fun toPack(index: Int): Pack {
        val base =
            (origin ?: Pack(index = index, label = label, bmsType = bmsType, bmsAddress = address)).copy(
                index = index,
                label = label,
                bmsType = bmsType,
                bmsAddress = address,
                canId = canId
            )
        val withCellCount = if (cellCountEdited) base.copy(cellCount = cellCount) else base
        return if (aliasEdited) withCellCount.copy(aliasGroup = aliasGroup) else withCellCount
    }
}

/**
 * A controller's [MotorConfig] **while it is being typed**: every field
 * nullable, because a text box the rider has cleared is not the number zero.
 *
 * This is the rule the deleted flat Motor card used to carry, rehomed onto the
 * source it edits (G2 Task 3). It had to live somewhere with per-field
 * nullability: the screen's `IntField`/`FloatField` re-sync from the value they
 * are handed, so a draft that resolved a blank to `MotorConfig()`'s default
 * immediately would push that default straight back into the box the rider had
 * just cleared — which is the wart the flat card produced on every structural
 * edit, kept at bay there by a separate copy of the field values in `State`.
 *
 * The defaults are `MotorConfig()`'s own, so a freshly added controller shows
 * the geometry it will actually be saved with rather than three empty boxes;
 * `null` is reserved for "the rider cleared this", and [resolve] is the single
 * statement of what a cleared field means.
 */
data class MotorDraft(
    val polePairs: Int? = MotorConfig().polePairs,
    val wheelDiameterMm: Int? = MotorConfig().wheelDiameterMm,
    val gearRatio: Float? = MotorConfig().gearRatio
) {
    /** A blank field falls back to [MotorConfig]'s own default for that field. */
    fun resolve(): MotorConfig = MotorConfig(
        polePairs = polePairs ?: MotorConfig().polePairs,
        wheelDiameterMm = wheelDiameterMm ?: MotorConfig().wheelDiameterMm,
        gearRatio = gearRatio ?: MotorConfig().gearRatio
    )

    companion object {
        fun of(motor: MotorConfig) = MotorDraft(motor.polePairs, motor.wheelDiameterMm, motor.gearRatio)
    }
}

/**
 * One controller being edited. See [PackDraft] for what [key] and [origin] are
 * for; [derivedBattery] is documented on [DerivedBatteryChoice] and [motor] on
 * [MotorDraft].
 */
data class ControllerDraft(
    val key: String,
    val label: String,
    val controllerType: ControllerType,
    val address: String,
    val canId: Int? = null,
    val motor: MotorDraft = MotorDraft(),
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
            motor = motor.resolve(),
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
     * The distinct BLE links this vehicle already has, **controllers first**.
     *
     * The screen offers these as one-tap choices for a source's address, which
     * is what "hosted" means concretely (`G §4`): a battery or a slave
     * controller reached *through* another source's link is simply one that
     * shares its address. Expressed as a link rather than as a boolean because
     * a vehicle can have more than one link and "hosted by which?" then has no
     * answer — and because it is the same control the two-uBox case needs for a
     * controller, where a boolean would not have applied at all.
     *
     * Blank addresses are excluded: `""` is not a link a rider can mean, it is
     * the absence of one, and [ComposerIssue.BlankAddress] already says so.
     * Controllers lead because a gateway is a controller.
     */
    val linkAddresses: List<String>
        get() = (controllers.map { it.address } + packs.map { it.address })
            .filter { it.isNotBlank() }
            .distinct()

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

    /**
     * Whether [pack] is reached **through a gateway** rather than over its own
     * BLE link: some controller of this vehicle sits at the same address.
     *
     * That is what "hosted" means concretely once you admit a vehicle has more
     * than one link (`01-linking §1`) — the head unit answers
     * `COMM_BMS_GET_VALUES` for a battery it reads elsewhere, and the thing that
     * makes its address a gateway address is the controller(s) on it. A pack on
     * an address no controller shares is a plain direct BMS link.
     *
     * Used by [duplicatePackSuspects] and [handoffAliasGroups], both of which
     * care about the *shape* `01-linking §4` describes: one direct path and one
     * gateway-hosted path to one battery.
     */
    fun isHostedPack(pack: PackDraft): Boolean =
        pack.address.isNotBlank() && controllers.any { it.address == pack.address }

    /**
     * The alias groups that span a **gateway-hosted** and a **direct** battery
     * on two different links — the only shape the `C §5` ride-time handoff can
     * act on, and therefore the only shape the "yield BMS to head unit while
     * riding" toggle is about.
     *
     * A mirror of `planAliasHandoffs`' first three conditions, not a call into
     * it: that function lives in `data/ble`, works on `LinkSpec`s the composer
     * has not built yet, and its fourth condition (the direct link owns nothing
     * else) is about what would be lost as collateral when the link is released
     * rather than about whether the setting applies. Showing the toggle is
     * cheap; showing it where there is no contention at all is what would be
     * wrong. `a grouped alias pair is one the runtime plans a handoff for`
     * spans the two layers on one fixture.
     */
    val handoffAliasGroups: List<String>
        get() = packs.mapNotNull { it.aliasGroup }.distinct().filter { group ->
            val members = packs.filter { it.aliasGroup == group }
            val hosted = members.filter { isHostedPack(it) }
            val direct = members.filter { !isHostedPack(it) }
            hosted.isNotEmpty() && direct.any { d -> hosted.none { it.address == d.address } }
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

/**
 * Re-point every draft's `origin` at the **freshly-read** row, matching by the
 * stored index the draft was seeded from.
 *
 * `draftOf` runs when the form opens; the stored row moves underneath it.
 * `KableBmsRepository.maybePersistCellCount` upserts `withCellCount(n)` the
 * moment the pack's first cell frames arrive — which, since Part D navigates a
 * Controller pick straight to this form, is exactly while the rider is looking
 * at it. Without this, a save that touched the pack list would write back the
 * load-time `cellCount` (usually null), and `lastPersistedCellCount` then
 * suppresses re-persisting for the rest of the process: a spec'd, telemetry-fed
 * value (`G §4`) silently reverted, with nothing to restore it.
 *
 * `State.packsEdited` already protects the *untouched* half wholesale; this
 * protects the composed half **field by field**, which is the only way to cover
 * a value that changed inside a source the rider also edited.
 *
 * A draft whose origin has vanished from the stored row keeps the old one —
 * dropping it would reset every unmodelled field to its default, which is worse
 * than a stale value for a source somebody deleted elsewhere.
 */
fun VehicleDraft.reanchoredTo(vehicle: Vehicle): VehicleDraft = copy(
    packs = packs.map { d ->
        val fresh = d.origin?.let { o -> vehicle.packs.firstOrNull { it.index == o.index } }
        // `origin` ONLY. Refreshing the draft's own display fields as well
        // would be a second mechanism for the same guarantee, and the one that
        // matters is `toPack` not naming what the composer does not edit — so
        // there is exactly one place a future editable field has to be added,
        // not two that can disagree.
        if (fresh == null) d else d.copy(origin = fresh)
    },
    controllers = controllers.map { d ->
        val fresh = d.origin?.let { o -> vehicle.controllers.firstOrNull { it.index == o.index } }
        if (fresh == null) d else d.copy(origin = fresh)
    }
)

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
                motor = MotorDraft.of(c.motor),
                derivedBattery = derivedBatteryChoiceFor(c, default),
                origin = c
            )
        }
    )
}

// ----- mutations, all pure and all total (an impossible request is a no-op) -----

/**
 * [canId] is defaulted to null — a direct source, which is every add a rider
 * makes by hand — and set only by CAN discovery, where it is **mandatory**: on a
 * gateway link a null id claims to be the head unit itself, and a second such
 * pack is [ComposerIssue.AmbiguousGatewaySource]. See `ComposerCanDiscovery.kt`.
 */
fun VehicleDraft.addPack(
    bmsType: BmsType,
    address: String,
    label: String = "",
    canId: Int? = null
): VehicleDraft = copy(
    packs = packs + PackDraft(
        key = "p-new-$nextKey",
        label = label.ifBlank { "Pack ${packs.size + 1}" },
        bmsType = bmsType,
        address = address,
        canId = canId
    ),
    nextKey = nextKey + 1
)

/**
 * See [addPack] for what [canId] is for and why it defaults to null.
 *
 * [motor] defaults to [MotorDraft]'s own defaults — which is `MotorConfig()`'s,
 * i.e. `wheelDiameterMm = 0` — because a controller a rider added by hand is one
 * they are about to fill the geometry in for, on the card the add just opened.
 * Only [addCanController] passes anything else; see its doc for why.
 */
fun VehicleDraft.addController(
    controllerType: ControllerType,
    address: String,
    label: String = "",
    canId: Int? = null,
    motor: MotorDraft = MotorDraft()
): VehicleDraft = copy(
    controllers = controllers + ControllerDraft(
        key = "c-new-$nextKey",
        label = label.ifBlank { "Controller ${controllers.size + 1}" },
        controllerType = controllerType,
        address = address,
        canId = canId,
        motor = motor
    ),
    nextKey = nextKey + 1
)

/**
 * **Which controller on [address] is the gateway** — the one a device discovered
 * on that link's CAN bus should copy its wheel from.
 *
 * The gateway is the source whose requests `VescGatewayProtocol` sends
 * UNWRAPPED, and on a composed link that is exactly the controller with a null
 * [ControllerDraft.canId] (`C §6`; a second one is
 * [ComposerIssue.AmbiguousGatewaySource]). A draft assembled entirely out of
 * discovery may not have one yet — the rider can add slaves before the head unit
 * itself — so the fallback is the lowest CAN id on the link, which is stable
 * under add order and under a re-scan that returns the same bus in a different
 * sequence.
 *
 * Returns `MotorDraft()` when the link carries no controller at all. **Inventing
 * a wheel diameter would be worse than admitting there is not one**: a made-up
 * diameter turns eRPM into a confident wrong speed, whereas the zero default
 * leaves [ru.sodovaya.volty.data.bms.vesc.VescValues.derivedSpeedKmh] null and
 * the gauges reading `—` (`G §9`).
 */
fun VehicleDraft.gatewayMotorAt(address: String): MotorDraft {
    val onLink = controllers.filter { it.address == address }
    val gateway = onLink.firstOrNull { it.canId == null }
        ?: onLink.mapNotNull { c -> c.canId?.let { it to c } }.minByOrNull { it.first }?.second
    return gateway?.motor ?: MotorDraft()
}

/**
 * Add a controller **found by a CAN scan of [address]**, inheriting that link's
 * gateway motor geometry ([gatewayMotorAt]).
 *
 * The defect this exists for: a CAN-discovered controller used to be created
 * with a bare `MotorConfig()`, whose `wheelDiameterMm` is 0, so
 * [ru.sodovaya.volty.data.bms.vesc.VescValues.derivedSpeedKmh] refused to derive
 * a speed for it forever. After Task 4 that is no longer the only road — a
 * controller answering `COMM_GET_VALUES_SETUP` reports ground speed directly —
 * but it is still the only road for one that answers `GET_VALUES` and not SETUP,
 * and a rider who has already measured the wheel once, on the gateway, has told
 * us the number: slaves on one vehicle almost always turn the same size wheel.
 *
 * **A snapshot, never a link.** The geometry is copied at add time and is
 * independently editable afterwards, so correcting the gateway's wheel later
 * does not silently move a second controller under the rider — and a slave with
 * a genuinely different wheel is one edit away rather than unrepresentable.
 * Inheritance is a default, not a lock.
 */
fun VehicleDraft.addCanController(
    controllerType: ControllerType,
    address: String,
    label: String = "",
    canId: Int?
): VehicleDraft = addController(controllerType, address, label, canId, gatewayMotorAt(address))

/**
 * `G §3` flow 3: **one** add producing a controller and a battery on **one
 * link** — an electric unicycle, which is a motherboard and its own pack behind
 * a single BLE address.
 *
 * Composed from [addController] and [addPack] rather than written out, so there
 * is still exactly one definition of what each half of the add is (labels,
 * key allocation, the `nextKey` bump) and a wheel cannot drift away from two
 * separate adds in any respect except the one that matters.
 *
 * **The shared [address] IS the deliverable.** Task 3's review corrected the
 * reasoning for this add: the value is not the tap it saves — two separate adds
 * produce the same two rows — it is that a rider who taps it has *told volty the
 * two sources are one device*, which the app cannot infer from anything on the
 * wire. `planLinks` groups by address, so both land on one [LinkSpec], one
 * session, one connection: exactly what `wheelVehicle` builds for a picked
 * Begode, reachable now from a vehicle that already has other sources.
 *
 * The controller's derived battery is left on [DerivedBatteryChoice.AUTO] and
 * not forced off: the rule resolves it to false the moment this add's pack
 * exists (`G §6`), which is the same answer `wheelVehicle`'s hard-coded `false`
 * gives — and unlike that constant, it comes back on if the rider later removes
 * the pack.
 */
fun VehicleDraft.addWheel(
    controllerType: ControllerType,
    bmsType: BmsType,
    address: String,
    label: String = ""
): VehicleDraft = addController(controllerType, address, label).addPack(bmsType, address, label)

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

// ----- alias grouping (Task 4): two battery sources, one physical pack -----

/**
 * Mark the packs [keyA] and [keyB] as **the same physical battery reached two
 * ways** (`01-linking §4`, `G §5`).
 *
 * This is the deliverable of Task 4 and the one the product owner's scooter
 * needs: his ANT is reachable directly over BLE while parked and through the
 * head unit while riding, and nothing on the wire says the two are one battery
 * — only he knows. Without the shared group `PackAggregator` counts the pack
 * twice (doubled capacity, doubled energy) and `planAliasHandoffs` has no way
 * to tell which two links contend for it.
 *
 * **Merging, not pairing.** If either side already belongs to a group, that
 * group's id wins and every member of *both* groups is moved onto it, so
 * "A with B" then "B with C" leaves one group of three rather than two
 * overlapping claims — the field holds a single id, so overlapping groups are
 * not representable and pretending otherwise would silently drop a member.
 * [keyA]'s id is preferred purely so the operation is deterministic.
 *
 * A fresh id comes from [VehicleDraft.nextKey], the same monotonic counter row
 * keys come from: deterministic, so the whole draft stays comparable in tests,
 * and unique within the vehicle, which is all `aliasGroup` has to be
 * (`PackAggregator.collapseAliases` groups by it inside one vehicle).
 *
 * Grouping a pack with itself, or with a key that is not in the draft, is a
 * no-op — like every other mutation here, an impossible request changes nothing.
 */
fun VehicleDraft.groupPacks(keyA: String, keyB: String): VehicleDraft {
    if (keyA == keyB) return this
    val a = packs.firstOrNull { it.key == keyA } ?: return this
    val b = packs.firstOrNull { it.key == keyB } ?: return this
    val group = a.aliasGroup ?: b.aliasGroup ?: "alias-$nextKey"
    val absorbed = setOfNotNull(a.aliasGroup, b.aliasGroup)
    return copy(
        packs = packs.map { p ->
            if (p.key == keyA || p.key == keyB || (p.aliasGroup != null && p.aliasGroup in absorbed)) {
                p.copy(aliasGroup = group, aliasEdited = true)
            } else {
                p
            }
        },
        nextKey = if (a.aliasGroup == null && b.aliasGroup == null) nextKey + 1 else nextKey
    )
}

/**
 * Take [key] out of its alias group.
 *
 * **A group of one is not a group**, so a member left alone is cleared with it.
 * That is not tidiness: a lone `aliasGroup` claims this battery has another path
 * that the vehicle no longer describes, and it is what the rider would have to
 * undo by hand on the *other* card to get back to "these are two batteries".
 * `PackAggregator` collapsing a one-member group is a no-op, so nothing observable
 * depends on the leftover — which is exactly why it would sit there unnoticed.
 */
fun VehicleDraft.ungroupPack(key: String): VehicleDraft {
    val group = packs.firstOrNull { it.key == key }?.aliasGroup ?: return this
    val cleared = packs.map { if (it.key == key) it.copy(aliasGroup = null, aliasEdited = true) else it }
    val left = cleared.filter { it.aliasGroup == group }
    return copy(
        packs = if (left.size > 1) cleared
        else cleared.map { if (it.aliasGroup == group) it.copy(aliasGroup = null, aliasEdited = true) else it }
    )
}

fun VehicleDraft.updatePack(key: String, edit: (PackDraft) -> PackDraft): VehicleDraft =
    copy(packs = packs.map { if (it.key == key) edit(it) else it })

fun VehicleDraft.updateController(key: String, edit: (ControllerDraft) -> ControllerDraft): VehicleDraft =
    copy(controllers = controllers.map { if (it.key == key) edit(it) else it })

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
 *    that cannot both be true of one physical BLE link. No future version of
 *    volty will accept them, and the save is refused. `planLinks` throws on two
 *    of the three; the third ([AmbiguousGatewaySource]) it cannot see, because
 *    its duplicate-CAN check `mapNotNull`s the nulls away — pre-Part-C *every*
 *    source was null, so "null" could not have meant "the gateway itself" yet.
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
     * **Two sources on one gateway link both claiming to be the gateway
     * itself** — more than one owned controller, or more than one owned pack,
     * with `canId == null`.
     *
     * On a gateway link `canId == null` does not mean "direct", it means "the
     * endpoint answers this itself": `VescGatewayProtocol.frameFor` sends the
     * request **unwrapped** when the id is null, rather than inside a
     * `FORWARD_CAN`. There is exactly one such endpoint, so two sources cannot
     * both be it — and the resulting requests are **byte-identical**, so the
     * gateway answers the same frame twice and `applyValues` files one decode
     * under two different `globalIndex`es. `MotionAggregator` then **sums**
     * `motorCurrentA`, `batteryCurrentA` and `powerW` across controllers, so
     * the dashboard reads about double. Nothing throws, nothing is logged: it
     * saves clean, connects, and lies.
     *
     * `planLinks` does not catch it — its duplicate-CAN `require` runs over
     * `mapNotNull { it.canId }` (`LinkPlan.kt:189`), deliberately, because
     * before Part C every source's id was null. This is the same criterion as
     * [UnroutableGateway] one level down: a gateway link must be able to tell
     * its sources apart, and `canId` is the only thing that tells them apart.
     *
     * Blocking, not advisory: it is a contradiction (two "the head unit
     * itself"s), not a capability gap.
     */
    data class AmbiguousGatewaySource(val address: String, val sourceKeys: List<String>) : ComposerIssue {
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

    /**
     * Two battery sources that look like **the same physical pack reached two
     * ways** (`G §5`, `01-linking §4`), offered as a grouping rather than
     * counted twice.
     *
     * The rule, the window, the band and — most of all — why it refuses to fire
     * on two genuinely similar packs are all in `ComposerDuplicates.kt`. It is
     * the one issue that carries an ACTION rather than a correction: the card
     * offers [VehicleDraft.groupPacks] on the pair.
     *
     * Advisory, and it could not be anything else. Two packs that track each
     * other are a perfectly valid vehicle — that is what parallel wiring is —
     * so this is a question about the rider's hardware, not a contradiction in
     * their configuration.
     */
    data class DuplicatePack(val keyA: String, val keyB: String) : ComposerIssue {
        override val blocking: Boolean get() = false
    }

    /**
     * **A controller row that is probably a display, not an ESC** (Task 4, added
     * after Task 5 found it).
     *
     * Picking a head unit creates a controller row for it with `canId == null`,
     * because at pick time nothing knows whether the endpoint drives a motor. On
     * the product owner's scooter it does not: the head unit is a VESC Express
     * dashboard that reaches the two uBoxes **only by CAN-forwarding**
     * (`01-linking §0`), and its own `GET_VALUES` describes no motor. That row
     * then polls a controller that does not exist, and nothing ever prompts the
     * rider to remove it.
     *
     * It is not merely a wasted poll — it damages the two things this app is for:
     *
     *  - **the number on the dashboard.** `MotionAggregator` used to fold
     *    `inputVoltageV` with a plain `average()`, so a head unit answering with
     *    a 0 V rail made a three-controller vehicle report **two thirds** of its
     *    real pack voltage. G2 Task 6 fixed the fold (`G §9.3`): it now averages
     *    only over the controllers that report
     *    [ru.sodovaya.volty.domain.model.ControllerData.hasInputVoltage], and
     *    the number never reached the alert engine in the first place — no
     *    `MotionAlertKind` thresholds against a rail voltage.
     *
     *    **This bullet is still live for a VESC head unit**, which is the one
     *    this advisory is actually about: only `BegodeProtocol` sets that flag
     *    false today, so a VESC-protocol row answering `vIn = 0` still takes the
     *    `true` default and is still averaged in. Closing it means teaching
     *    `VescValues` the same distinction — see `G §8`'s follow-up list;
     *  - **the staleness watchdog.** A head unit answering *nothing* is excluded
     *    from the fold correctly, but `MotionResult.partial` is then permanently
     *    true and the row is a permanently-silent extra source on the gateway
     *    link — the precondition a timed-out CAN scan needs to trip the 5 s
     *    stale-sample watchdog. The row manufactures the hazard.
     *
     * **Advisory, never blocking**, and worded as a question rather than an
     * assertion: a head unit that genuinely *is* an ESC is a legitimate build
     * (a uBox with a BLE module is exactly that — `01-linking §0`'s
     * controller-gateway), and we have not measured what this rider's hardware
     * answers.
     *
     * Raised only when the link has **proved it works** — some other source at
     * the same address has reported — so "this one is silent" is a fact about
     * the source and not about a link that never came up. See
     * `ComposerDuplicates.kt`'s `reportsMotion` for why an all-zero answer
     * counts as silence.
     */
    data class PhantomGatewayController(val sourceKey: String) : ComposerIssue {
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
 * order, then per-address in first-appearance order, then the pairwise ones) so
 * a screen can render it without sorting and a test can assert on the list.
 *
 * Empty for a well-formed draft, and `issues.none { it.blocking }` is exactly
 * the condition under which the resulting [Vehicle] is guaranteed to survive
 * `planLinks`.
 *
 * [telemetry] is what this sitting has **observed** of the vehicle, and the two
 * issues that read it ([ComposerIssue.PhantomGatewayController] and
 * [ComposerIssue.DuplicatePack]) are both advisories about the rider's hardware
 * rather than about their configuration — neither can be decided from a draft
 * alone, and both say nothing at all when nothing has been observed. It
 * defaults to empty so a caller that only wants the structural rules (and every
 * test that predates Task 4) needs no change.
 */
fun validate(draft: VehicleDraft, telemetry: ComposerTelemetry = ComposerTelemetry()): List<ComposerIssue> {
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
        // `LinkSpec.isGatewayLink`, re-stated: any CAN id, a hosted VESC_BMS
        // pack (only possible once the link resolved to VESC), or more than one
        // controller on one address.
        val isGateway = g.canIds.isNotEmpty() ||
            g.controllers.size > 1 ||
            (resolved == ProtocolKind.VESC && g.packs.any { it.protocolKind == ProtocolKind.VESC_BMS })
        if (isGateway) {
            // On a gateway link `canId == null` means "the endpoint itself",
            // and there is one endpoint. Two such sources produce identical
            // requests and one decode filed twice — see [AmbiguousGatewaySource].
            // Packs and controllers are counted separately: they ask different
            // questions (BMS_GET_VALUES vs GET_VALUES), so one of each is fine.
            val directControllers = g.controllers.filter { it.canId == null }
            if (directControllers.size > 1) {
                issues += ComposerIssue.AmbiguousGatewaySource(address, directControllers.map { it.key })
            }
            val directPacks = g.packs.filter { it.canId == null }
            if (directPacks.size > 1) {
                issues += ComposerIssue.AmbiguousGatewaySource(address, directPacks.map { it.key })
            }
        }
        // VESC is the only kind with a multiplexer; every other arm of
        // `controllerMotionProtocol` ignores the link spec entirely.
        if (isGateway && resolved != ProtocolKind.VESC) {
            issues += ComposerIssue.UnroutableGateway(address, resolved)
        }
        // The phantom head-unit controller — see [ComposerIssue.PhantomGatewayController].
        //
        // `g.canIds.isNotEmpty()` and not `isGateway`: the criterion is a link
        // that carries CAN-ADDRESSED sources, which is the only shape in which
        // a null id means "the endpoint itself" AND there is something else
        // behind it that the endpoint exists to reach. `isGateway` is broader
        // (two controllers on one address, a hosted VESC_BMS) and would make a
        // plain dual-VESC-on-one-link vehicle — where neither controller is a
        // gateway for the other — answer to a rule about gateways.
        if (g.canIds.isNotEmpty()) {
            // The link has PROVED it works: something else at this address has
            // reported. Without this, a head unit that was simply never
            // connected would be accused of being a display.
            val linkWorks = (g.controllers.map { it.key } + g.packs.map { it.key })
                .any { it in telemetry.sourcesSeen }
            if (linkWorks) {
                g.controllers
                    .filter { it.canId == null && it.key !in telemetry.controllersWithMotion }
                    .forEach { issues += ComposerIssue.PhantomGatewayController(it.key) }
            }
        }
    }

    // Pairwise, so last: it is about two rows rather than one row or one link.
    issues += duplicatePackSuspects(draft, telemetry)
    return issues
}

// ---------------------------------------------------------------------------
// Issues → the rows that must show them
// ---------------------------------------------------------------------------

private fun VehicleDraft.keysAt(address: String): List<String> =
    controllers.filter { it.address == address }.map { it.key } +
        packs.filter { it.address == address }.map { it.key }

private fun VehicleDraft.keysAt(address: String, canId: Int): List<String> =
    controllers.filter { it.address == address && it.canId == canId }.map { it.key } +
        packs.filter { it.address == address && it.canId == canId }.map { it.key }

/**
 * The draft rows this issue is about — the source card(s) the screen prints it
 * on.
 *
 * It lives here rather than in the `@Composable` because it is the answer to
 * "**which** source is wrong", which is the whole of what a rider needs from
 * [validate] and the only part of it a renderer cannot work out for itself: the
 * three per-address issues ([ComposerIssue.ConflictingKinds],
 * [ComposerIssue.DuplicateCanId], [ComposerIssue.UnroutableGateway]) name a BLE
 * address, and turning that back into rows means re-walking the draft the same
 * way [validate] grouped it. A card that printed only the issues carrying its
 * own key would leave the two blocking address issues — the ones that refuse
 * the save — with nowhere to appear except a banner naming a MAC.
 *
 * [ComposerIssue.DuplicateCanId] names only the rows actually holding the
 * duplicated id, not every row on the link: the other sources behind that
 * gateway are innocent, and blaming them is how a rider ends up editing the
 * wrong one.
 */
fun ComposerIssue.affectedKeys(draft: VehicleDraft): List<String> = when (this) {
    is ComposerIssue.BlankAddress -> listOf(sourceKey)
    is ComposerIssue.NoControllerDecoder -> listOf(sourceKey)
    is ComposerIssue.HostlessVescBms -> listOf(sourceKey)
    is ComposerIssue.PhantomGatewayController -> listOf(sourceKey)
    is ComposerIssue.AmbiguousGatewaySource -> sourceKeys
    // Both cards, because the offer is symmetric: either one can be tapped to
    // group the pair, and a rider who only ever opens the second pack's card
    // would otherwise never be told.
    is ComposerIssue.DuplicatePack -> listOf(keyA, keyB)
    is ComposerIssue.ConflictingKinds -> draft.keysAt(address)
    is ComposerIssue.UnroutableGateway -> draft.keysAt(address)
    is ComposerIssue.DuplicateCanId -> draft.keysAt(address, canId)
}

/**
 * [issues] indexed by the source key each one concerns, keeping [validate]'s
 * order within a row. A source with nothing wrong is absent rather than mapped
 * to an empty list, so a screen can ask `!= null` and a test can assert on the
 * key set.
 */
fun issuesBySource(draft: VehicleDraft, issues: List<ComposerIssue>): Map<String, List<ComposerIssue>> {
    val out = LinkedHashMap<String, MutableList<ComposerIssue>>()
    for (issue in issues) {
        for (key in issue.affectedKeys(draft)) out.getOrPut(key) { mutableListOf() } += issue
    }
    return out
}
