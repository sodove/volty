package ru.sodovaya.volty.domain.model

enum class ControllerType(val label: String) {
    VESC("VESC"),
    FARDRIVER("FarDriver"),
    KELLY("Kelly KLS"),
    BEGODE("Begode"),
    NINEBOT("Ninebot Z"),
    NINEBOT_LEGACY("Ninebot legacy"),
    KINGSONG("KingSong"),
    INMOTION("InMotion"),
    VETERAN("Veteran / Leaperkim"),
    NOSFET("Nosfet")
}

data class MotorConfig(
    val polePairs: Int = 15,
    val wheelDiameterMm: Int = 0,
    val gearRatio: Float = 1f
)

/** Where the editable motor geometry came from. This is session provenance, not a wire value. */
enum class MotorConfigProvenance { CONTROLLER, INHERITED, RIDER }

/**
 * The series count the rider typed for the battery a controller on [address] provides, or null.
 * One definition for the dashboard (KableBmsRepository) and the history recorder, so the two
 * cannot disagree about the same wheel.
 */
fun Vehicle?.typedDerivedCellCount(address: String): Int? = this?.controllers
    ?.firstOrNull { it.address == address && it.providesDerivedBattery && it.derivedCellCount != null }
    ?.derivedCellCount

data class Controller(
    val index: Int,
    val label: String,
    val controllerType: ControllerType,
    val address: String,
    val canId: Int? = null,
    val motor: MotorConfig = MotorConfig(),
    val providesDerivedBattery: Boolean = false,
    /**
     * The series-cell count the rider typed for the battery this controller provides
     * ([providesDerivedBattery]). Such a battery has no Pack row to hold a `cellCount`, and a
     * wheel with no smart BMS (a Begode T4) shows volts but no charge unless the count is known.
     * `null` = not typed; the wheel's own learned count (if any) is used instead.
     */
    val derivedCellCount: Int? = null
)
