package ru.sodovaya.volty.data.ble

import ru.sodovaya.volty.data.bms.BegodeProtocol
import ru.sodovaya.volty.data.bms.BmsProtocol
import ru.sodovaya.volty.data.bms.GatewaySource
import ru.sodovaya.volty.data.bms.KellyProtocol
import ru.sodovaya.volty.data.bms.MotionSource
import ru.sodovaya.volty.data.bms.VescGatewayProtocol
import ru.sodovaya.volty.data.bms.VescProtocol
import ru.sodovaya.volty.domain.model.ControllerType
import ru.sodovaya.volty.domain.model.MotorConfig

/**
 * THE single statement of which controller kinds volty can actually decode —
 * the motion protocol behind [kind], or null when there is none.
 *
 * Two callers, one fact:
 *  - `KableBmsRepository.createProtocol(spec, vehicle)` builds every controller
 *    link from this, so what it returns IS what a connection speaks;
 *  - [controllerMotionSupported] asks it whether a type can be connected at
 *    all, which is what the picker refuses a pick on.
 *
 * That is the point of the indirection. Adding a protocol here flips BOTH at
 * once — there is no second list in the UI to keep in step, and no way to ship
 * a working decoder that the picker still refuses (or the reverse).
 *
 * Battery kinds return null rather than being absent: this function answers
 * "is there a CONTROLLER protocol for this kind", and every kind has an answer.
 *
 * ### [ProtocolKind.BEGODE] — the kind that is both
 * A wheel is a controller AND two batteries over ONE link, so this kind is
 * reachable from a [ru.sodovaya.volty.domain.model.Pack] and from
 * [ControllerType.BEGODE] alike, and `toBmsType()` maps it to a real
 * [ru.sodovaya.volty.domain.model.BmsType] — it is the one controller kind that
 * would not throw on its own if this function got it wrong.
 *
 * Until Part D Task 4 it answered **null**, deliberately: `BegodeProtocol` was
 * a battery decoder, and returning it here would have put a Ride dashboard that
 * can never show motion in front of a rider. That reason expired when Task 2
 * made `BegodeProtocol` a [MotionSource] — the same 20-byte frames carry speed,
 * duty, mileage and two temperatures — so the branch now returns the wheel's
 * own protocol and the picker offers it.
 *
 * The BEGODE arm is answered for EVERY Begode link, including a battery-only
 * one, and that is not a widening: it is the same class the battery fallback
 * would have built one line further down, so a wheel configured as batteries
 * only decodes exactly as it always did. What a link actually *does* with the
 * motion is the link plan's decision — a plan owning no controller drops the
 * sample (`KableBmsRepository.makeLinkOnMotionSample`).
 *
 * [cellCount] is that branch's [motor]: vehicle configuration the decoder
 * cannot read off any frame and needs in order to be honest. The live frame's
 * voltage is on Begode's 67.2 V reference, and without the pack's cell count
 * `BegodeProtocol` publishes `inputVoltageV = 0` **with
 * [ru.sodovaya.volty.domain.model.ControllerData.hasInputVoltage] false**, which
 * `G §9`'s unknown-vs-zero contract renders as a dash on both dashboard styles
 * and which `MotionAggregator` skips rather than averaging into a mixed
 * vehicle's real rail.
 *
 * That contract makes the missing cell count *legible*, not harmless: a rider
 * still sees no power, no rail voltage and no live consumption until they supply
 * one. (Before G2 Task 6 they saw a confident **"0.0 kW"** and "0.0 Wh/km"
 * instead, which was worse — but "honest absence" is not the same as "present".)
 * Passing it here is therefore part of the branch, not a tidy-up. Null (the
 * picker's probe, every non-Begode caller) is honest absence.
 *
 * Exhaustive with NO `else`, like [ProtocolKind.toBmsType] and
 * `KableBmsRepository.batteryBmsTypeOrNull`: a new [ProtocolKind] must force a
 * decision here at compile time.
 *
 * ### Gateway links
 * [link] is the spec of the link being built, when there is one — the picker's
 * coverage probe has no link and passes none. A VESC link that is a
 * [LinkSpec.isGatewayLink] (CAN-forwarded sources, a hosted battery, or several
 * controllers on one address) gets [VescGatewayProtocol] instead of the plain
 * [VescProtocol]. That choice lives HERE, inside the same exhaustive `when`, so
 * there is still exactly one statement of controller coverage: the picker's
 * gate keeps deriving from it (a gateway link's kind is still `VESC`, which is
 * still supported), and no second list can drift out of step.
 *
 * [motorFor] resolves a controller's wheel geometry by its vehicle-global
 * index, because a gateway carries several controllers and each has its own.
 * It defaults to [motor] for the single-controller case, which is every
 * existing caller.
 *
 * **[deriveBattery] is honoured on BOTH VESC branches** — it was not, and the
 * omission deleted a rider's battery the moment they added a CAN controller.
 * [vescGatewayPacks] is where the gateway branch answers it; read that KDoc
 * before changing either branch's arguments.
 */
fun controllerMotionProtocol(
    kind: ProtocolKind,
    deriveBattery: Boolean,
    motor: MotorConfig,
    link: LinkSpec? = null,
    motorFor: (globalControllerIndex: Int) -> MotorConfig = { motor },
    cellCount: Int? = null
): BmsProtocol? = when (kind) {
    ProtocolKind.VESC ->
        if (link != null && link.isGatewayLink) {
            VescGatewayProtocol(
                controllers = link.ownedControllers.map {
                    GatewaySource(it.globalIndex, it.canId, motorFor(it.globalIndex))
                },
                // NOT `link.ownedPacks.map { … }` — see [vescGatewayPacks].
                // Re-listing the multiplexer's pack sources here is precisely
                // how [deriveBattery] came to be answered on one branch and
                // silently dropped on the other.
                packs = vescGatewayPacks(link, deriveBattery)
            )
        } else {
            VescProtocol(deriveBattery = deriveBattery, motor = motor)
        }
    // A wheel: one controller and two packs over one link. See the KDoc.
    //
    // [deriveBattery] and [link] are both ignored here, and both deliberately:
    //  - a wheel does not DERIVE a battery from its rail voltage the way a VESC
    //    does; it decodes two real branches off the same frames
    //    (`packCount = 2`), so the derived-battery machinery has nothing to do.
    //    `pickedControllerVehicle` builds a Begode with a stored pack for
    //    exactly this reason, and its controller carries
    //    `providesDerivedBattery = false`;
    //  - [link] selects VESC's multiplexer, and there is no Begode equivalent.
    //    LIMITATION, stated because nothing else states it: a vehicle
    //    configured with TWO Begode controllers at one address is a
    //    [LinkSpec.isGatewayLink] (`ownedControllers.size > 1`), and this arm
    //    still returns a protocol reporting `controllerCount = 1`. Controller 1
    //    is never sampled and sits permanently offline with nothing logged.
    //    Unreachable today — no screen can create it (the picker builds exactly
    //    one controller, and the edit screen edits only `controllers[0]`) — and
    //    a wheel has one motor on one board, so the shape is physically odd
    //    too. If a later part lets a rider add a second controller, it must
    //    either refuse this pairing in the plan or grow a Begode multiplexer.
    ProtocolKind.BEGODE -> BegodeProtocol(cellCount = cellCount)
    // Kelly's monitor packets carry controller telemetry over the same NUS
    // transport as a VESC, but the ETS wire protocol is its own decoder.
    // It deliberately receives the same controller-only decisions as VESC:
    // the derived pack is optional and wheel geometry is needed for motion.
    ProtocolKind.KELLY -> KellyProtocol(deriveBattery = deriveBattery, motor = motor)
    // No motion decoder yet.
    ProtocolKind.FARDRIVER -> null
    // Battery kinds: not a controller protocol at all.
    ProtocolKind.JK, ProtocolKind.JBD, ProtocolKind.ANT,
    ProtocolKind.DALY, ProtocolKind.VESC_BMS -> null
}

/**
 * The global index a derived slot carries before [KableBmsRepository]'s pack
 * plan has numbered it — see [vescGatewayPacks]. Negative because every real
 * index is a position in the vehicle's own pack list and so is `>= 0`; nothing
 * is ever keyed by it, because the only protocol instance that can hold one is
 * discarded the instant its `packCount` has been read.
 */
internal const val UNNUMBERED_DERIVED_PACK: Int = -1

/**
 * **THE single statement of which pack slots a VESC gateway link decodes**, and
 * the reason it is a named function rather than a `map` inlined at the one call
 * site above.
 *
 * `deriveBattery` used to be answered on the plain-[VescProtocol] branch and
 * simply not mentioned on the gateway branch, which re-listed the multiplexer's
 * arguments from [LinkSpec] alone. That is the third instance on this project of
 * a branch silently losing a field the other branch keeps (`G §8`'s
 * rebuild-on-save was the first two), and this time it cost a rider their
 * battery: adding a CAN-forwarded controller flips a link to `isGatewayLink`,
 * so the act of adding it took `packCount` from 1 to 0 and
 * `KableBmsRepository.planLinkPacks` then allocated no slot at all. Adding one
 * more line to the gateway branch's argument list would fix this instance and
 * leave the shape that produced it, so the pack list is derived from ONE source
 * of truth instead — [link]'s own owned packs, plus the derived slot when this
 * link's controllers back one.
 *
 * ### Which owned slot is the derived one
 *
 * The one carrying neither a CAN id nor a [ProtocolKind] of its own. That is
 * exact rather than a heuristic: `planLinks` tags every source it plans off a
 * real profile pack whose kind differs from the link's, and on a VESC link
 * **every** profile pack's kind differs (no [ru.sodovaya.volty.domain.model.BmsType]
 * maps to [ProtocolKind.VESC]), so an untagged slot can only be one
 * `KableBmsRepository` synthesised.
 *
 * ### A link that already owns a battery does not get a second one
 *
 * The derived slot is appended only when [link] owns **no** pack, and that is a
 * decision rather than an oversight — the one place this function does not
 * simply honour [deriveBattery], so here is why:
 *
 *  - a gateway's own hosted battery is reached by `COMM_BMS_GET_VALUES` to the
 *    node we are connected to, which is *the identical request* a derived slot
 *    would send. A second slot would ask the same node the same question and
 *    publish the same answer twice, as two packs;
 *  - and it would not stay runtime-only. A slot beside a stored pack on the
 *    same address comes from `expandedTo`, not from `planLinkPacks`' derived
 *    pass, so `KableBmsRepository.maybePersistPacks` sees it as **discovered
 *    hardware** and writes it into the profile — after which the next connect
 *    expands again. A ratchet that grows a rider's battery list by one pack per
 *    ride is a far worse outcome than a flag with no effect.
 *
 * Reachable only by a rider explicitly forcing `DerivedBatteryChoice.ON` on a
 * controller of a gateway that already hosts a battery; the composer's own rule
 * (`G §6`, `VehicleDraft.derivedBatteryDefault`) answers false for it. Behaviour
 * for that shape is therefore exactly what it was before `I` Task 5.
 *
 * The narrow cost, stated because nothing else states it: a gateway owning only
 * **CAN-forwarded** batteries — whose requests really do go somewhere else —
 * forgoes a derived slot it could in principle have had. No screen creates that
 * shape today.
 *
 * ### Why this is a fixed point, and why it has to be
 *
 * It is asked TWICE per connection with two different specs, and both answers
 * must agree or the protocol that SIZED the pack list and the protocol the
 * session speaks disagree — the same class of defect as the dropped
 * `OwnedSource.kind` tag (`effectiveLinkSpecs`):
 *
 *  1. `planLinkPacks` asks it of the PLANNED spec, whose owned packs are the
 *     profile's stored ones, purely to read `packCount`. A link that derives a
 *     battery therefore answers `stored + 1`, with the extra slot carrying
 *     [UNNUMBERED_DERIVED_PACK] because nothing has numbered it yet;
 *  2. the session's real protocol is built from the EFFECTIVE spec, by which
 *     time that slot exists, is numbered, and is untagged — so it is recognised
 *     as the derived one and no SECOND slot is appended.
 *
 * A derived slot is asked `COMM_BMS_GET_VALUES` like any other pack; see
 * [GatewaySource.derived] for what fills it when nothing answers.
 */
internal fun vescGatewayPacks(link: LinkSpec, deriveBattery: Boolean): List<GatewaySource> {
    if (link.ownedPacks.isEmpty()) {
        return if (deriveBattery) {
            listOf(GatewaySource(globalIndex = UNNUMBERED_DERIVED_PACK, derived = true))
        } else {
            emptyList()
        }
    }
    return link.ownedPacks.map {
        GatewaySource(
            globalIndex = it.globalIndex,
            canId = it.canId,
            derived = deriveBattery && it.canId == null && it.kind == null
        )
    }
}

/**
 * Whether a vehicle built around [type] can actually be connected today —
 * **derived** from [controllerMotionProtocol], not listed separately.
 *
 * The criterion is `is MotionSource`, not "non-null": motion telemetry is the
 * whole reason a source is modelled as a [ru.sodovaya.volty.domain.model.Controller]
 * rather than a Pack, so a kind that produced a battery-only decoder would
 * still, correctly, be refused.
 *
 * The probe arguments are throwaway — no branch's *type* depends on them, and
 * every protocol constructor is a plain allocation with no I/O.
 *
 * Note what carries the compile-time guarantee now that this is derived rather
 * than enumerated over [ControllerType]: a new controller type has to be
 * mapped in [ControllerType.protocolKind] (exhaustive, in `LinkPlan.kt`) and a
 * new [ProtocolKind] has to be answered in [controllerMotionProtocol]
 * (exhaustive, above). Neither can be skipped, and both live beside the enum
 * they describe rather than in the UI.
 */
fun controllerMotionSupported(type: ControllerType): Boolean =
    controllerMotionProtocol(
        kind = type.protocolKind(),
        deriveBattery = false,
        motor = MotorConfig()
    ) is MotionSource
