package ru.sodovaya.volty.domain.model

/**
 * Safe (non-throwing) counterparts to the primary-pack shortcuts in
 * `Vehicle.kt` (`primaryPack`, `bmsType`, `bmsAddress`, `cellCount`), which
 * are built on `packs.first()` and throw `NoSuchElementException` for a
 * controller-only vehicle. Use these wherever a vehicle might have zero
 * packs.
 */
val Vehicle.primaryPackOrNull: Pack? get() = packs.firstOrNull()
val Vehicle.bmsTypeOrNull: BmsType? get() = primaryPackOrNull?.bmsType
val Vehicle.bmsAddressOrNull: String? get() = primaryPackOrNull?.bmsAddress
val Vehicle.cellCountOrNull: Int? get() = primaryPackOrNull?.cellCount

/** Every BLE address this vehicle can be recognised by — packs and controllers alike. */
val Vehicle.allAddresses: Set<String>
    get() = (packs.map { it.bmsAddress } + controllers.map { it.address }).toSet()

/** True when this vehicle has no battery source of its own and a controller must derive one. */
val Vehicle.needsDerivedBattery: Boolean get() = packs.isEmpty() && controllers.isNotEmpty()
