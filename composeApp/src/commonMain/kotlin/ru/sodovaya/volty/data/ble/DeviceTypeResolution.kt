package ru.sodovaya.volty.data.ble

import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.ControllerType
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.repository.DeviceTypeProvenance

internal data class ResolvedDeviceTypes(
    val bmsType: BmsType?,
    val controllerType: ControllerType?,
    val provenance: DeviceTypeProvenance,
)

/**
 * Resolve one advertisement without letting a name-based guess override an
 * address claim already stored in a vehicle. A single source remains
 * mutually exclusive: a remembered controller claim does not manufacture a
 * BMS type, and a remembered pack claim does not manufacture a controller.
 */
internal fun resolveDeviceTypes(
    address: String,
    knownVehicle: Vehicle?,
    detectedBmsType: BmsType?,
    detectedControllerType: ControllerType?,
): ResolvedDeviceTypes {
    val rememberedController = knownVehicle?.controllers
        ?.firstOrNull { it.address == address }
        ?.controllerType
    if (rememberedController != null) {
        return ResolvedDeviceTypes(
            bmsType = null,
            controllerType = rememberedController,
            provenance = DeviceTypeProvenance.REMEMBERED
        )
    }

    val rememberedPack = knownVehicle?.packs
        ?.firstOrNull { it.bmsAddress == address }
        ?.bmsType
    if (rememberedPack != null) {
        return ResolvedDeviceTypes(
            bmsType = rememberedPack,
            controllerType = null,
            provenance = DeviceTypeProvenance.REMEMBERED
        )
    }

    return if (detectedControllerType != null) {
        ResolvedDeviceTypes(
            bmsType = null,
            controllerType = detectedControllerType,
            provenance = DeviceTypeProvenance.DETECTED
        )
    } else {
        ResolvedDeviceTypes(
            bmsType = detectedBmsType,
            controllerType = null,
            provenance = DeviceTypeProvenance.DETECTED
        )
    }
}
