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
