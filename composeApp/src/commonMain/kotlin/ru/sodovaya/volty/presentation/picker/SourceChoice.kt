package ru.sodovaya.volty.presentation.picker

import ru.sodovaya.volty.data.ble.controllerMotionSupported
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
 * How to *phrase* the refusal when [type] cannot be connected yet, or null when
 * it can. Wording only — the decision itself belongs to the data layer and is
 * read straight from it.
 *
 * [controllerMotionSupported] (`data/ble/ControllerProtocols.kt`) is the
 * authority: it derives the answer from the same factory
 * `KableBmsRepository.createProtocol` builds every controller link with, so the
 * sheet cannot go on refusing a type whose protocol has quietly started
 * working. That split is deliberate — this file has no business knowing which
 * protocols exist, and the data layer has no business knowing how a refusal
 * reads.
 *
 * The sheet deliberately keeps offering every type (see [SourceChoice]): a user
 * who sees "FarDriver is not supported yet" learns more than one who sees
 * nothing.
 */
fun unsupportedControllerReason(type: ControllerType): String? =
    if (controllerMotionSupported(type)) null else "${type.label} is not supported yet"
