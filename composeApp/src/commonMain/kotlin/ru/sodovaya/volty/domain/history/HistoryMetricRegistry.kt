package ru.sodovaya.volty.domain.history

enum class HistoryUnit { PERCENT, VOLT, AMP, WATT, CELSIUS, MILLIVOLT, KMH, ERPM, KM, WH, AH }
enum class HistoryMetricGroup { MOVEMENT, CONTROLLER, BATTERY, CELL }
data class HistoryMetricDescriptor(
    val id: String, val unit: HistoryUnit, val group: HistoryMetricGroup,
    val sourceKinds: Set<HistorySourceKind>, val displayMultiplier: Double = 1.0,
    val consumptionMultiplier: Double? = null, val indexedByCell: Boolean = false,
)
object HistoryMetricRegistry {
    private val batterySources = setOf(HistorySourceKind.BATTERY, HistorySourceKind.VEHICLE_BATTERY)
    private val motionSources = setOf(HistorySourceKind.CONTROLLER, HistorySourceKind.VEHICLE_MOTION)
    private val individualBattery = setOf(HistorySourceKind.BATTERY)

    // Keys and canonical units are storage contracts, independent of translated UI labels.
    val descriptors: List<HistoryMetricDescriptor> = listOf(
        HistoryMetricDescriptor("soc_percent", HistoryUnit.PERCENT, HistoryMetricGroup.BATTERY, batterySources),
        HistoryMetricDescriptor("power_w", HistoryUnit.WATT, HistoryMetricGroup.BATTERY, batterySources,
            displayMultiplier = -1.0, consumptionMultiplier = -1.0),
        HistoryMetricDescriptor("current_a", HistoryUnit.AMP, HistoryMetricGroup.BATTERY, batterySources, displayMultiplier = -1.0),
        HistoryMetricDescriptor("voltage_v", HistoryUnit.VOLT, HistoryMetricGroup.BATTERY, batterySources),
        HistoryMetricDescriptor("battery_temperature_c", HistoryUnit.CELSIUS, HistoryMetricGroup.BATTERY, batterySources),
        HistoryMetricDescriptor("cell_min_v", HistoryUnit.VOLT, HistoryMetricGroup.CELL, batterySources),
        HistoryMetricDescriptor("cell_max_v", HistoryUnit.VOLT, HistoryMetricGroup.CELL, batterySources),
        HistoryMetricDescriptor("cell_delta_mv", HistoryUnit.MILLIVOLT, HistoryMetricGroup.CELL, batterySources),
        HistoryMetricDescriptor("cell_v", HistoryUnit.VOLT, HistoryMetricGroup.CELL, individualBattery, indexedByCell = true),
        HistoryMetricDescriptor("speed_kmh", HistoryUnit.KMH, HistoryMetricGroup.MOVEMENT, motionSources),
        HistoryMetricDescriptor("duty_percent", HistoryUnit.PERCENT, HistoryMetricGroup.CONTROLLER, motionSources),
        HistoryMetricDescriptor("motor_current_a", HistoryUnit.AMP, HistoryMetricGroup.CONTROLLER, motionSources),
        HistoryMetricDescriptor("battery_current_a", HistoryUnit.AMP, HistoryMetricGroup.CONTROLLER, motionSources),
        HistoryMetricDescriptor("input_voltage_v", HistoryUnit.VOLT, HistoryMetricGroup.CONTROLLER, motionSources),
        HistoryMetricDescriptor("controller_power_w", HistoryUnit.WATT, HistoryMetricGroup.CONTROLLER, motionSources,
            consumptionMultiplier = 1.0),
        HistoryMetricDescriptor("erpm", HistoryUnit.ERPM, HistoryMetricGroup.CONTROLLER, motionSources),
        HistoryMetricDescriptor("esc_temperature_c", HistoryUnit.CELSIUS, HistoryMetricGroup.CONTROLLER, motionSources),
        HistoryMetricDescriptor("motor_temperature_c", HistoryUnit.CELSIUS, HistoryMetricGroup.CONTROLLER, motionSources),
        HistoryMetricDescriptor("odometer_km", HistoryUnit.KM, HistoryMetricGroup.MOVEMENT, motionSources),
        HistoryMetricDescriptor("trip_distance_km", HistoryUnit.KM, HistoryMetricGroup.MOVEMENT, motionSources),
    )
    private val byId = descriptors.associateBy { it.id }
    fun descriptor(id: String): HistoryMetricDescriptor? = byId[id]
    fun forSource(kind: HistorySourceKind): List<HistoryMetricDescriptor> = descriptors.filter { kind in it.sourceKinds }
}
