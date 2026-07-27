package ru.sodovaya.volty.data.ble

import ru.sodovaya.volty.data.bms.BegodeProtocol
import ru.sodovaya.volty.data.bms.BmsProtocol
import ru.sodovaya.volty.data.bms.GatewaySource
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
 * `BegodeProtocol` publishes `inputVoltageV = 0` — which the Ride dashboard
 * renders as a confident **"0.0 kW"** and "0.0 Wh/km", not as a blank. Passing
 * it here is therefore part of the branch, not a tidy-up. Null (the picker's
 * probe, every non-Begode caller) is honest absence.
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
                packs = link.ownedPacks.map { GatewaySource(it.globalIndex, it.canId) }
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
    // No motion decoder yet — Parts E (FarDriver) and H (Kelly).
    ProtocolKind.FARDRIVER, ProtocolKind.KELLY -> null
    // Battery kinds: not a controller protocol at all.
    ProtocolKind.JK, ProtocolKind.JBD, ProtocolKind.ANT,
    ProtocolKind.DALY, ProtocolKind.VESC_BMS -> null
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
