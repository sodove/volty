package ru.sodovaya.volty.data.ble

import ru.sodovaya.volty.data.bms.BmsProtocol
import ru.sodovaya.volty.data.bms.MotionSource
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
 * [ProtocolKind.BEGODE] is the one that matters — it is reachable from a
 * [ru.sodovaya.volty.domain.model.Pack] AND from
 * [ControllerType.BEGODE], and `toBmsType()` maps it to a real
 * [ru.sodovaya.volty.domain.model.BmsType], so it is the only unsupported
 * controller kind that would NOT throw on its own. Returning null for it here
 * is what keeps a Begode *controller* refused instead of silently decoded as a
 * battery on a Ride dashboard that can never show motion.
 *
 * Exhaustive with NO `else`, like [ProtocolKind.toBmsType] and
 * `KableBmsRepository.batteryBmsTypeOrNull`: a new [ProtocolKind] must force a
 * decision here at compile time.
 */
fun controllerMotionProtocol(
    kind: ProtocolKind,
    deriveBattery: Boolean,
    motor: MotorConfig
): BmsProtocol? = when (kind) {
    ProtocolKind.VESC -> VescProtocol(deriveBattery = deriveBattery, motor = motor)
    // No motion decoder yet — Parts D (FarDriver) and E (Kelly).
    ProtocolKind.FARDRIVER, ProtocolKind.KELLY -> null
    // Battery kinds: not a controller protocol at all. See the KDoc on BEGODE.
    ProtocolKind.JK, ProtocolKind.JBD, ProtocolKind.ANT,
    ProtocolKind.DALY, ProtocolKind.BEGODE, ProtocolKind.VESC_BMS -> null
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
