package ru.sodovaya.volty.presentation.picker

import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.ControllerType
import ru.sodovaya.volty.domain.repository.DiscoveredDevice

/**
 * What the type-picker sheet lets the user say a discovered device is: one of
 * the two halves a vehicle can be built from. The two are mutually exclusive
 * *as a detection result* on a single device (see
 * [ru.sodovaya.volty.data.bms.BmsTypeDetector.detectController]), but the
 * sheet always offers both as a manual choice — detection is a hint, not a
 * lock, so a user with an unrecognised or misdetected device can still pick
 * either kind.
 */
sealed interface SourceChoice {
    data class Battery(val type: BmsType) : SourceChoice
    data class Controller(val type: ControllerType) : SourceChoice
}

/**
 * The choice the type sheet should pre-highlight for [device], mirroring
 * whichever detector already fired for it — `null` for an unrecognised
 * device, which still renders both sections, just with nothing selected.
 * Pulled out as a pure function (rather than inlined in the sheet) so it is
 * testable without a Compose UI harness.
 */
fun preselectedChoice(device: DiscoveredDevice): SourceChoice? = when {
    device.controllerType != null -> SourceChoice.Controller(device.controllerType)
    device.bmsType != null -> SourceChoice.Battery(device.bmsType)
    else -> null
}

/**
 * Why [type] cannot be connected *yet*, or null when it can — the honest
 * statement of how far the controller half of the app actually reaches today.
 *
 * Only [ControllerType.VESC] has a decode protocol
 * ([ru.sodovaya.volty.data.bms.VescProtocol], the sole `MotionSource`). The
 * other three land in the same failure the user gets from an unreachable
 * device, named so they learn *why* — the sheet deliberately keeps offering
 * them (see [SourceChoice]), and silently hiding a type teaches nothing.
 *
 * The two failure modes this covers are NOT the same underneath, which is
 * exactly why one gate has to cover both:
 *  - FARDRIVER / KELLY: `ProtocolKind.toBmsType()` `error(...)`s for them.
 *    `KableBmsRepository.doConnect` does catch it, but the message it surfaces
 *    is an internal one about "a controller kind"; users get a better one here.
 *  - BEGODE: `ControllerType.BEGODE.protocolKind()` is `ProtocolKind.BEGODE`,
 *    which maps to a REAL `BmsType.BEGODE` and therefore builds a
 *    `BegodeProtocol` — a battery decoder that is not a `MotionSource`. Nothing
 *    throws: the connect would succeed and land the user on a Ride dashboard
 *    that can never show motion. Silently wrong is worse than refused.
 *
 * Exhaustive with NO `else`, matching
 * `KableBmsRepository.batteryBmsTypeOrNull`: a new [ControllerType] must force
 * a decision here at compile time. When Part D/E/H lands a real protocol, the
 * whole change is moving that type to the null branch.
 */
fun unsupportedControllerReason(type: ControllerType): String? = when (type) {
    ControllerType.VESC -> null
    ControllerType.FARDRIVER, ControllerType.KELLY, ControllerType.BEGODE ->
        "${type.label} is not supported yet"
}
