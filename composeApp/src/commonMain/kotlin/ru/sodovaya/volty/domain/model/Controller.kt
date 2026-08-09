package ru.sodovaya.volty.domain.model

enum class ControllerType(val label: String) {
    VESC("VESC"),
    FARDRIVER("FarDriver"),
    KELLY("Kelly KLS"),
    BEGODE("Begode")
}

data class MotorConfig(
    val polePairs: Int = 15,
    val wheelDiameterMm: Int = 0,
    val gearRatio: Float = 1f
)

/** Where the editable motor geometry came from. This is session provenance, not a wire value. */
enum class MotorConfigProvenance { CONTROLLER, INHERITED, RIDER }

data class Controller(
    val index: Int,
    val label: String,
    val controllerType: ControllerType,
    val address: String,
    val canId: Int? = null,
    val motor: MotorConfig = MotorConfig(),
    val providesDerivedBattery: Boolean = false
)
