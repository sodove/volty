package ru.sodovaya.volty.domain.alert

import ru.sodovaya.volty.domain.model.Chemistry
import ru.sodovaya.volty.domain.model.Controller
import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.ControllerType
import ru.sodovaya.volty.domain.model.Pack
import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.SpeedSource
import ru.sodovaya.volty.domain.model.Vehicle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
class MotionAlertAvailabilityTest {

    private fun vehicle(vararg types: ControllerType) = Vehicle(
        id = "v", name = "n", iconKey = "generic",
        packs = emptyList(),
        controllers = types.mapIndexed { i, t -> Controller(i, "ESC$i", t, "AA:$i") },
        chemistry = Chemistry.LI_ION_NMC,
        createdAt = Clock.System.now()
    )

    /** A battery-only vehicle: legal (it has a pack), but no motion source at all. */
    private fun packOnlyVehicle() = Vehicle(
        id = "v", name = "n", iconKey = "generic",
        packs = listOf(Pack(0, "P", BmsType.JK_BMS, "PACK")),
        controllers = emptyList(),
        chemistry = Chemistry.LI_ION_NMC,
        createdAt = Clock.System.now()
    )

    /** A live sample with every sensor present, so any Unavailable in a test is the gate's doing. */
    private fun fullSample() = ControllerData(
        speedKmh = 32f,
        speedSource = SpeedSource.REPORTED,
        dutyPercent = 95f,
        escTempC = 61f,
        motorTempC = 84f,
        hasMotorTemp = true,
        isConnected = true
    )

    // ---------------------------------------------------------------- layer 1

    @Test fun a_vehicle_with_no_controllers_has_every_motion_kind_unavailable() {
        val availability = availabilityFor(packOnlyVehicle(), latestMotion = null)
        assertEquals(MotionAlertKind.entries.toSet(), availability.keys)
        for (kind in MotionAlertKind.entries) {
            assertEquals(
                AlertAvailability.Unavailable(AlertUnavailableReason.NoController),
                availability[kind],
                "$kind on a vehicle with no controller"
            )
        }
    }

    @Test fun no_controller_stays_unavailable_even_if_a_sample_is_somehow_supplied() {
        // Not Unknown, and not rescued by data: with no controller there is no
        // hardware that could ever supply motion, so no sample can change it.
        val availability = availabilityFor(packOnlyVehicle(), fullSample())
        for (kind in MotionAlertKind.entries) {
            assertEquals(
                AlertAvailability.Unavailable(AlertUnavailableReason.NoController),
                availability[kind],
                "$kind"
            )
        }
    }

    @Test fun kelly_never_has_duty_available_even_with_a_live_sample_showing_duty() {
        // The sample says duty is 95 %. It is not evidence: KLS reports no duty
        // (H §7), so whatever arrives in that field is not a duty measurement.
        val availability = availabilityFor(vehicle(ControllerType.KELLY), fullSample())
        assertEquals(
            AlertAvailability.Unavailable(
                AlertUnavailableReason.ControllerReportsNoDuty(ControllerType.KELLY)
            ),
            availability[MotionAlertKind.DUTY]
        )
        // and the rest of the kinds are unaffected — the gate is per kind.
        assertEquals(AlertAvailability.Available, availability[MotionAlertKind.SPEED])
        assertEquals(AlertAvailability.Available, availability[MotionAlertKind.MOTOR_TEMP])
        assertEquals(AlertAvailability.Available, availability[MotionAlertKind.ESC_TEMP])
    }

    @Test fun kelly_has_no_duty_while_disconnected_either_duty_never_goes_unknown() {
        // Duty is settled by the protocol alone; there is no sensor to discover.
        val availability = availabilityFor(vehicle(ControllerType.KELLY), latestMotion = null)
        assertEquals(
            AlertAvailability.Unavailable(
                AlertUnavailableReason.ControllerReportsNoDuty(ControllerType.KELLY)
            ),
            availability[MotionAlertKind.DUTY]
        )
    }

    @Test fun vesc_has_duty_available_with_no_sample_at_all() {
        val availability = availabilityFor(vehicle(ControllerType.VESC), latestMotion = null)
        assertEquals(AlertAvailability.Available, availability[MotionAlertKind.DUTY])
    }

    @Test fun one_duty_reporting_controller_is_enough_on_a_mixed_vehicle() {
        // The vehicle-level motion aggregate folds controllers together, so a
        // VESC beside a Kelly still supplies duty.
        val availability = availabilityFor(
            vehicle(ControllerType.KELLY, ControllerType.VESC),
            latestMotion = null
        )
        assertEquals(AlertAvailability.Available, availability[MotionAlertKind.DUTY])
    }

    @Test fun the_no_duty_reason_names_the_actual_controller_type() {
        // The sentence must not hard-code "Kelly": it is generated from the type.
        val reason = availabilityFor(vehicle(ControllerType.KELLY), fullSample())[MotionAlertKind.DUTY]
        assertEquals(
            ControllerType.KELLY,
            (reason as AlertAvailability.Unavailable)
                .let { it.reason as AlertUnavailableReason.ControllerReportsNoDuty }
                .type
        )
    }

    @Test fun the_static_duty_table_answers_every_controller_type() {
        // Pins today's answers so Part E flipping FarDriver (E §9.3) is deliberate.
        assertTrue(ControllerType.VESC.reportsDuty)
        assertTrue(ControllerType.BEGODE.reportsDuty)
        assertTrue(ControllerType.FARDRIVER.reportsDuty)
        assertFalse(ControllerType.KELLY.reportsDuty)
    }

    // ---------------------------------------------------------------- layer 2

    @Test fun missing_motor_thermistor_makes_motor_temp_unavailable() {
        val availability = availabilityFor(
            vehicle(ControllerType.VESC),
            fullSample().copy(hasMotorTemp = false)
        )
        assertEquals(
            AlertAvailability.Unavailable(AlertUnavailableReason.NoMotorTempSensor),
            availability[MotionAlertKind.MOTOR_TEMP]
        )
        // neighbouring kinds untouched
        assertEquals(AlertAvailability.Available, availability[MotionAlertKind.ESC_TEMP])
        assertEquals(AlertAvailability.Available, availability[MotionAlertKind.DUTY])
    }

    @Test fun missing_esc_sensor_makes_esc_temp_unavailable() {
        // hasEscTemp is computed: VESC's no-sensor sentinel is implausibly low.
        val availability = availabilityFor(
            vehicle(ControllerType.VESC),
            fullSample().copy(escTempC = -99.9f)
        )
        assertFalse(fullSample().copy(escTempC = -99.9f).hasEscTemp, "fixture must trip the sentinel")
        assertEquals(
            AlertAvailability.Unavailable(AlertUnavailableReason.NoEscTempSensor),
            availability[MotionAlertKind.ESC_TEMP]
        )
        assertEquals(AlertAvailability.Available, availability[MotionAlertKind.MOTOR_TEMP])
    }

    @Test fun no_speed_source_makes_speed_unavailable() {
        val availability = availabilityFor(
            vehicle(ControllerType.VESC),
            fullSample().copy(speedSource = SpeedSource.NONE)
        )
        assertEquals(
            AlertAvailability.Unavailable(AlertUnavailableReason.NoSpeedSource),
            availability[MotionAlertKind.SPEED]
        )
    }

    @Test fun derived_speed_counts_as_a_speed_source() {
        val availability = availabilityFor(
            vehicle(ControllerType.VESC),
            fullSample().copy(speedSource = SpeedSource.DERIVED)
        )
        assertEquals(AlertAvailability.Available, availability[MotionAlertKind.SPEED])
    }

    // ----------------------------------------------------------------- unknown

    @Test fun no_sample_yields_unknown_not_unavailable_for_the_sensor_dependent_kinds() {
        // The rider opens settings while disconnected. We must not tell them
        // their motor has no thermistor — we have never looked.
        val availability = availabilityFor(vehicle(ControllerType.VESC), latestMotion = null)
        for (kind in listOf(
            MotionAlertKind.SPEED,
            MotionAlertKind.MOTOR_TEMP,
            MotionAlertKind.ESC_TEMP
        )) {
            assertEquals(AlertAvailability.Unknown, availability[kind], "$kind with no sample")
        }
    }

    @Test fun unknown_is_not_armable_but_is_not_an_unavailable_claim() {
        val unknown: AlertAvailability = AlertAvailability.Unknown
        assertFalse(unknown.isArmable, "no sample means no value to compare — nothing to arm")
        assertFalse(
            unknown is AlertAvailability.Unavailable,
            "Unknown must never be reported as 'your hardware lacks this sensor'"
        )
    }

    @Test fun isArmable_is_true_only_for_Available() {
        assertTrue(AlertAvailability.Available.isArmable)
        assertFalse(AlertAvailability.Unknown.isArmable)
        assertFalse(
            AlertAvailability.Unavailable(AlertUnavailableReason.NoController).isArmable
        )
    }

    // ------------------------------------------------- the negative that matters

    @Test fun an_unavailable_kind_with_enabled_levels_configured_still_never_arms() {
        // F §10: an alert the hardware cannot supply must be IMPOSSIBLE to arm.
        // This is the restored-backup / swapped-hardware case: a full, valid,
        // enabled duty rule on a Kelly vehicle that can never report duty.
        val kelly = vehicle(ControllerType.KELLY)
        val configured = listOf(
            AlertRule(MotionAlertKind.DUTY, listOf(AlertLevel(80f), AlertLevel(90f))),
            AlertRule(MotionAlertKind.MOTOR_TEMP, listOf(AlertLevel(110f)))
        )
        // The config itself is armed-looking: nothing about it says "off".
        assertTrue(configured.all { !it.isOff })
        assertTrue(configured.all { rule -> rule.levels.all { it.enabled } })

        val armed = armedRules(kelly, fullSample(), configured)

        assertNull(
            armed.firstOrNull { it.kind == MotionAlertKind.DUTY },
            "a duty rule reached the alarm engine on hardware that reports no duty"
        )
        // and the gate is not a blanket refusal — the available kind survives.
        assertEquals(listOf(MotionAlertKind.MOTOR_TEMP), armed.map { it.kind })
    }

    @Test fun an_unknown_kind_with_enabled_levels_configured_also_never_arms() {
        // Never connected: no sample, so no value to compare. Nothing may fire.
        val configured = listOf(AlertRule(MotionAlertKind.MOTOR_TEMP, listOf(AlertLevel(110f))))
        val armed = armedRules(vehicle(ControllerType.VESC), latestMotion = null, configured = configured)
        assertEquals(emptyList(), armed)
    }

    @Test fun a_vehicle_with_no_controllers_arms_nothing_even_from_full_defaults() {
        val armed = armedRules(packOnlyVehicle(), latestMotion = null, configured = AlarmDefaults.all())
        assertEquals(emptyList(), armed, "battery-only vehicle armed a motion alarm")
    }

    @Test fun armedRules_drops_rules_the_rider_switched_off_and_keeps_the_rest() {
        val vesc = vehicle(ControllerType.VESC)
        val configured = listOf(
            AlertRule(MotionAlertKind.SPEED, emptyList()), // rider turned it off
            AlertRule(MotionAlertKind.DUTY, listOf(AlertLevel(80f)))
        )
        val armed = armedRules(vesc, fullSample(), configured)
        assertEquals(listOf(MotionAlertKind.DUTY), armed.map { it.kind })
    }

    @Test fun armedRules_passes_available_levels_through_untouched_including_muted_ones() {
        // Muting a middle step must not shift the ones above it (F §10.2), so
        // the gate may filter whole kinds but must never rewrite a level list.
        val levels = listOf(AlertLevel(80f), AlertLevel(85f, enabled = false), AlertLevel(90f))
        val armed = armedRules(
            vehicle(ControllerType.VESC),
            fullSample(),
            listOf(AlertRule(MotionAlertKind.DUTY, levels))
        )
        assertEquals(listOf(AlertRule(MotionAlertKind.DUTY, levels)), armed)
    }

    @Test fun armedRules_fails_closed_on_a_kind_the_availability_map_does_not_mention() {
        // Defensive: a caller assembling a partial map must not accidentally
        // open a gate that was never evaluated.
        val armed = armedRules(
            configured = listOf(AlertRule(MotionAlertKind.DUTY, listOf(AlertLevel(80f)))),
            availability = emptyMap()
        )
        assertEquals(emptyList(), armed)
    }

    @Test fun availability_covers_every_kind_so_the_ui_can_render_the_full_greyed_list() {
        val availability = availabilityFor(vehicle(ControllerType.VESC), fullSample())
        assertEquals(MotionAlertKind.entries, availability.keys.toList())
    }
}
