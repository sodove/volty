package ru.sodovaya.volty.presentation.vehicle.info

import org.jetbrains.compose.resources.StringResource
import ru.sodovaya.volty.domain.model.DeviceFactKey
import ru.sodovaya.volty.util.UnitSystem
import volty.composeapp.generated.resources.Res
import volty.composeapp.generated.resources.*

/*
 * The words of the vehicle-info screen. Every enum the model emits maps to a string resource through
 * an exhaustive `when` with no `else`, so a new value fails to compile until it has an English and a
 * Russian string. Pure on purpose: the @Composable layer only calls stringResource() on the result.
 */
/** Exhaustive on purpose: a new InfoSectionKind value must not compile until it has words. */
fun InfoSectionKind.stringRes(): StringResource = when (this) {
        InfoSectionKind.VEHICLE -> Res.string.info_section_vehicle
        InfoSectionKind.CONNECTIONS -> Res.string.info_section_connections
        InfoSectionKind.BATTERIES -> Res.string.info_section_batteries
        InfoSectionKind.CONTROLLERS -> Res.string.info_section_controllers
        InfoSectionKind.ENERGY -> Res.string.info_section_energy
        InfoSectionKind.RANGE -> Res.string.info_section_range
        InfoSectionKind.ALERTS -> Res.string.info_section_alerts
        InfoSectionKind.DEVICE_FACTS -> Res.string.info_section_device_facts
}

/** Exhaustive on purpose: a new InfoLabel value must not compile until it has words. */
fun InfoLabel.stringRes(): StringResource = when (this) {
        InfoLabel.SOURCES -> Res.string.info_label_sources
        InfoLabel.TOPOLOGY -> Res.string.info_label_topology
        InfoLabel.CHEMISTRY -> Res.string.info_label_chemistry
        InfoLabel.CELL_BOUNDS -> Res.string.info_label_cell_bounds
        InfoLabel.SERIES_CELLS -> Res.string.info_label_series_cells
        InfoLabel.ADDRESS -> Res.string.info_label_address
        InfoLabel.PROTOCOL -> Res.string.info_label_protocol
        InfoLabel.DEVICE_NAME -> Res.string.info_label_device_name
        InfoLabel.MODEL -> Res.string.info_label_model
        InfoLabel.FIRMWARE -> Res.string.info_label_firmware
        InfoLabel.HARDWARE_CODE -> Res.string.info_label_hardware_code
        InfoLabel.FRAMES_PER_SECOND -> Res.string.info_label_frames_per_second
        InfoLabel.RECONNECTS -> Res.string.info_label_reconnects
        InfoLabel.VIA_HEAD_UNIT -> Res.string.info_label_via_head_unit
        InfoLabel.LINK_STATE -> Res.string.info_label_link_state
        InfoLabel.BMS_TYPE -> Res.string.info_label_bms_type
        InfoLabel.PACK_CELL_COUNT -> Res.string.info_label_pack_cell_count
        InfoLabel.VOLTAGE -> Res.string.info_label_voltage
        InfoLabel.CURRENT -> Res.string.info_label_current
        InfoLabel.TEMPERATURES -> Res.string.info_label_temperatures
        InfoLabel.CELL_MIN -> Res.string.info_label_cell_min
        InfoLabel.CELL_MAX -> Res.string.info_label_cell_max
        InfoLabel.CELL_DELTA -> Res.string.info_label_cell_delta
        InfoLabel.SOC -> Res.string.info_label_soc
        InfoLabel.CAPACITY -> Res.string.info_label_capacity
        InfoLabel.CYCLES -> Res.string.info_label_cycles
        InfoLabel.CYCLE_CAPACITY -> Res.string.info_label_cycle_capacity
        InfoLabel.CHARGE_SWITCH -> Res.string.info_label_charge_switch
        InfoLabel.DISCHARGE_SWITCH -> Res.string.info_label_discharge_switch
        InfoLabel.BMS_FAULTS -> Res.string.info_label_bms_faults
        InfoLabel.CONTROLLER_TYPE -> Res.string.info_label_controller_type
        InfoLabel.SPEED -> Res.string.info_label_speed
        InfoLabel.DUTY -> Res.string.info_label_duty
        InfoLabel.PHASE_CURRENT -> Res.string.info_label_phase_current
        InfoLabel.BATTERY_CURRENT -> Res.string.info_label_battery_current
        InfoLabel.INPUT_VOLTAGE -> Res.string.info_label_input_voltage
        InfoLabel.POWER -> Res.string.info_label_power
        InfoLabel.ERPM -> Res.string.info_label_erpm
        InfoLabel.MOTOR_TEMP -> Res.string.info_label_motor_temp
        InfoLabel.ESC_TEMP -> Res.string.info_label_esc_temp
        InfoLabel.CONSUMED_AH -> Res.string.info_label_consumed_ah
        InfoLabel.CONSUMED_WH -> Res.string.info_label_consumed_wh
        InfoLabel.REGEN_AH -> Res.string.info_label_regen_ah
        InfoLabel.REGEN_WH -> Res.string.info_label_regen_wh
        InfoLabel.ODOMETER -> Res.string.info_label_odometer
        InfoLabel.TRIP -> Res.string.info_label_trip
        InfoLabel.CONTROLLER_FAULTS -> Res.string.info_label_controller_faults
        InfoLabel.ENERGY_SOC -> Res.string.info_label_energy_soc
        InfoLabel.USABLE_AH -> Res.string.info_label_usable_ah
        InfoLabel.NOMINAL_PACK_V -> Res.string.info_label_nominal_pack_v
        InfoLabel.REMAINING_WH -> Res.string.info_label_remaining_wh
        InfoLabel.CAPACITY_SOURCE -> Res.string.info_label_capacity_source
        InfoLabel.RESISTANCE -> Res.string.info_label_resistance
        InfoLabel.LEARNED_CAPACITY -> Res.string.info_label_learned_capacity
        InfoLabel.LEARNED_RIDES -> Res.string.info_label_learned_rides
        InfoLabel.STORED_COUNTER -> Res.string.info_label_stored_counter
        InfoLabel.COUNTER_SOC -> Res.string.info_label_counter_soc
        InfoLabel.VOLTAGE_SOC -> Res.string.info_label_voltage_soc
        InfoLabel.PACK_V -> Res.string.info_label_pack_v
        InfoLabel.COMPENSATED_PACK_V -> Res.string.info_label_compensated_pack_v
        InfoLabel.DISCHARGE_A -> Res.string.info_label_discharge_a
        InfoLabel.CURRENT_SOURCE -> Res.string.info_label_current_source
        InfoLabel.INTEGRATED_AH -> Res.string.info_label_integrated_ah
        InfoLabel.INTEGRATED_WH -> Res.string.info_label_integrated_wh
        InfoLabel.LEARN_DROP -> Res.string.info_label_learn_drop
        InfoLabel.USABLE_FRACTION -> Res.string.info_label_usable_fraction
        InfoLabel.AT_REST -> Res.string.info_label_at_rest
        InfoLabel.TRIP_CONSUMPTION -> Res.string.info_label_trip_consumption
        InfoLabel.RECENT_CONSUMPTION -> Res.string.info_label_recent_consumption
        InfoLabel.BLENDED_CONSUMPTION -> Res.string.info_label_blended_consumption
        InfoLabel.RANGE_TRIP_KM -> Res.string.info_label_range_trip_km
        InfoLabel.RANGE_STATUS -> Res.string.info_label_range_status
        InfoLabel.ALERT_DUTY -> Res.string.info_label_alert_duty
        InfoLabel.ALERT_SPEED -> Res.string.info_label_alert_speed
        InfoLabel.ALERT_MOTOR_TEMP -> Res.string.info_label_alert_motor_temp
        InfoLabel.ALERT_ESC_TEMP -> Res.string.info_label_alert_esc_temp
        InfoLabel.DEVICE_FACT -> Res.string.info_label_device_fact
}

/** Exhaustive on purpose: a new InfoAbsence value must not compile until it has words. */
fun InfoAbsence.stringRes(): StringResource = when (this) {
        InfoAbsence.NOT_CONNECTED -> Res.string.info_absence_not_connected
        InfoAbsence.OFFLINE -> Res.string.info_absence_offline
        InfoAbsence.NOT_REPORTED -> Res.string.info_absence_not_reported
        InfoAbsence.PROTOCOL_LACKS -> Res.string.info_absence_protocol_lacks
        InfoAbsence.NO_SENSOR -> Res.string.info_absence_no_sensor
        InfoAbsence.NO_WHEEL_GEOMETRY -> Res.string.info_absence_no_wheel_geometry
        InfoAbsence.MIXED_CAUSES -> Res.string.info_absence_mixed_causes
        InfoAbsence.NOT_YET -> Res.string.info_absence_not_yet
        InfoAbsence.NOT_EARNED -> Res.string.info_absence_not_earned
        InfoAbsence.NO_CONTROLLER -> Res.string.info_absence_no_controller
        InfoAbsence.NO_SPEED_SOURCE -> Res.string.info_absence_no_speed_source
        InfoAbsence.STALE -> Res.string.info_absence_stale
        InfoAbsence.NOT_SET -> Res.string.info_absence_not_set
        InfoAbsence.SOC_UNKNOWN -> Res.string.info_absence_soc_unknown
        InfoAbsence.RANGE_NO_CAPACITY -> Res.string.info_absence_range_no_capacity
        InfoAbsence.RANGE_TOO_SHORT -> Res.string.info_absence_range_too_short
        InfoAbsence.RANGE_TOO_FEW_SAMPLES -> Res.string.info_absence_range_too_few_samples
        InfoAbsence.RANGE_SOC_UNKNOWN -> Res.string.info_absence_range_soc_unknown
        InfoAbsence.RANGE_NO_CONSUMPTION -> Res.string.info_absence_range_no_consumption
}

/** Exhaustive on purpose: a new InfoChoice value must not compile until it has words. */
fun InfoChoice.stringRes(): StringResource = when (this) {
        InfoChoice.SOC_BY_BMS -> Res.string.info_choice_soc_by_bms
        InfoChoice.SOC_BY_MODEL -> Res.string.info_choice_soc_by_model
        InfoChoice.SOC_BY_VOLTAGE -> Res.string.info_choice_soc_by_voltage
        InfoChoice.TOPOLOGY_PARALLEL -> Res.string.info_choice_topology_parallel
        InfoChoice.TOPOLOGY_SERIES -> Res.string.info_choice_topology_series
        InfoChoice.CHEMISTRY_LI_ION_NMC -> Res.string.info_choice_chemistry_li_ion_nmc
        InfoChoice.CHEMISTRY_LIFEPO4 -> Res.string.info_choice_chemistry_lifepo4
        InfoChoice.CHEMISTRY_LEAD_ACID -> Res.string.info_choice_chemistry_lead_acid
        InfoChoice.SERIES_FROM_PACK -> Res.string.info_choice_series_from_pack
        InfoChoice.SERIES_FROM_PROTOCOL -> Res.string.info_choice_series_from_protocol
        InfoChoice.SERIES_FROM_CONTROLLER -> Res.string.info_choice_series_from_controller
        InfoChoice.SERIES_FROM_STORED -> Res.string.info_choice_series_from_stored
        InfoChoice.SPEED_REPORTED -> Res.string.info_choice_speed_reported
        InfoChoice.SPEED_DERIVED -> Res.string.info_choice_speed_derived
        InfoChoice.ON -> Res.string.info_choice_on
        InfoChoice.OFF -> Res.string.info_choice_off
        InfoChoice.YES -> Res.string.info_choice_yes
        InfoChoice.NO -> Res.string.info_choice_no
        InfoChoice.NO_FAULTS -> Res.string.info_choice_no_faults
        InfoChoice.CAPACITY_ENTERED -> Res.string.info_choice_capacity_entered
        InfoChoice.CAPACITY_REPORTED -> Res.string.info_choice_capacity_reported
        InfoChoice.CAPACITY_LEARNED -> Res.string.info_choice_capacity_learned
        InfoChoice.CAPACITY_NONE -> Res.string.info_choice_capacity_none
        InfoChoice.CURRENT_FROM_CONTROLLER -> Res.string.info_choice_current_from_controller
        InfoChoice.CURRENT_FROM_PACKS -> Res.string.info_choice_current_from_packs
        InfoChoice.CURRENT_NONE -> Res.string.info_choice_current_none
        InfoChoice.RANGE_EARNED -> Res.string.info_choice_range_earned
        InfoChoice.LINK_ONLINE -> Res.string.info_choice_link_online
        InfoChoice.LINK_OFFLINE -> Res.string.info_choice_link_offline
        InfoChoice.ALERT_ARMED -> Res.string.info_choice_alert_armed
        InfoChoice.ALERT_OFF -> Res.string.info_choice_alert_off
        InfoChoice.ALERT_ACTIVE -> Res.string.info_choice_alert_active
}

/** Exhaustive on purpose: a new DeviceFactKey value must not compile until it has words. */
fun DeviceFactKey.stringRes(): StringResource = when (this) {
        DeviceFactKey.ODOMETER_KM -> Res.string.info_fact_odometer_km
        DeviceFactKey.POWER_ON_DISTANCE_KM -> Res.string.info_fact_power_on_distance_km
        DeviceFactKey.TILTBACK_SPEED_KMH -> Res.string.info_fact_tiltback_speed_kmh
        DeviceFactKey.WHEEL_ALERTS -> Res.string.info_fact_wheel_alerts
        DeviceFactKey.BOARD_TEMP_C -> Res.string.info_fact_board_temp_c
        DeviceFactKey.LIVE_PWM_RAW -> Res.string.info_fact_live_pwm_raw
        DeviceFactKey.MOTION_DUTY_RAW -> Res.string.info_fact_motion_duty_raw
        DeviceFactKey.SERIES_CELLS -> Res.string.info_fact_series_cells
        DeviceFactKey.SERIES_CELLS_PROOF -> Res.string.info_fact_series_cells_proof
        DeviceFactKey.SMART_BMS_SEEN -> Res.string.info_fact_smart_bms_seen
        DeviceFactKey.BRANCH_VOLTAGE_V -> Res.string.info_fact_branch_voltage_v
        DeviceFactKey.BRANCH_CURRENT_A -> Res.string.info_fact_branch_current_a
        DeviceFactKey.CONTROLLER_ID -> Res.string.info_fact_controller_id
        DeviceFactKey.FIRMWARE -> Res.string.info_fact_firmware
        DeviceFactKey.CAN_IDS -> Res.string.info_fact_can_ids
        DeviceFactKey.FAULT_CODES -> Res.string.info_fact_fault_codes
}

/**
 * The unit word for [this], following the rider's [system] for the three that convert. The numbers
 * were already converted by the model; this only picks the word. Existing localized unit strings are
 * reused where the app already has them.
 */
fun InfoUnit.stringRes(system: UnitSystem): StringResource {
    val imperial = system == UnitSystem.IMPERIAL
    return when (this) {
        InfoUnit.VOLT -> Res.string.history_unit_volt
        InfoUnit.MILLIVOLT -> Res.string.history_unit_millivolt
        InfoUnit.AMP -> Res.string.history_unit_amp
        InfoUnit.WATT -> Res.string.history_unit_watt
        InfoUnit.AMP_HOUR -> Res.string.history_unit_ah
        InfoUnit.WATT_HOUR -> Res.string.history_unit_wh
        InfoUnit.MILLIOHM -> Res.string.info_unit_milliohm
        InfoUnit.CELSIUS -> Res.string.unit_celsius
        InfoUnit.PERCENT -> Res.string.unit_percent
        InfoUnit.HERTZ -> Res.string.info_unit_hertz
        InfoUnit.SPEED -> if (imperial) Res.string.info_unit_mph else Res.string.unit_kmh
        InfoUnit.DISTANCE -> if (imperial) Res.string.range_unit_mi else Res.string.history_unit_km
        InfoUnit.CONSUMPTION -> if (imperial) Res.string.unit_wh_per_mi else Res.string.unit_wh_per_km
        InfoUnit.KILOMETER -> Res.string.history_unit_km
        InfoUnit.KMH -> Res.string.unit_kmh
    }
}