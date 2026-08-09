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
import kotlin.test.assertIs
import kotlin.test.assertIsNot
import kotlin.test.assertNotEquals
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

    @Test fun vesc_has_duty_available_on_its_first_sample() {
        val availability = availabilityFor(vehicle(ControllerType.VESC), fullSample())
        assertEquals(AlertAvailability.Available, availability[MotionAlertKind.DUTY])
    }

    @Test fun one_duty_reporting_controller_is_enough_on_a_mixed_vehicle() {
        // The vehicle-level motion aggregate folds controllers together, so a
        // VESC beside a Kelly still supplies duty.
        val availability = availabilityFor(
            vehicle(ControllerType.KELLY, ControllerType.VESC),
            fullSample()
        )
        assertEquals(AlertAvailability.Available, availability[MotionAlertKind.DUTY])
    }

    @Test fun the_static_layer_refuses_duty_even_on_a_sample_that_claims_it() {
        // Layer 1 comes first and no sample can rescue it: `hasDuty` says the
        // decoder MEASURED the field it filled, not that the field is a duty.
        // A Kelly decoder writing a plausible number into `dutyPercent` (H §7
        // forbids it, but the type cannot) must still never arm the alarm.
        val availability = availabilityFor(
            vehicle(ControllerType.KELLY),
            fullSample().copy(hasDuty = true)
        )
        assertEquals(
            AlertAvailability.Unavailable(
                AlertUnavailableReason.ControllerReportsNoDuty(ControllerType.KELLY)
            ),
            availability[MotionAlertKind.DUTY]
        )
    }

    @Test fun the_static_duty_table_answers_every_controller_type() {
        // Pins today's answers, including FarDriver's verified lack of duty.
        assertTrue(ControllerType.VESC.reportsDuty)
        assertTrue(ControllerType.BEGODE.reportsDuty)
        assertFalse(ControllerType.FARDRIVER.reportsDuty)
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

    @Test fun a_sample_that_reports_no_measured_duty_makes_duty_unavailable() {
        // `D §7.2`, the reason this layer exists: a Begode's `truePWM` latch is
        // open until the wheel reports a non-zero PWM once, and until then
        // `dutyPercent` reads 0 — indistinguishable from a genuine 0 %. Arming
        // the ШИМ alarm against that constant is F §10's silent-dead-alarm.
        val unmeasured = fullSample().copy(dutyPercent = 0f, hasDuty = false)
        val availability = availabilityFor(vehicle(ControllerType.BEGODE), unmeasured)
        assertEquals(
            AlertAvailability.Unavailable(AlertUnavailableReason.ControllerHasNotReportedDuty),
            availability[MotionAlertKind.DUTY]
        )
        // and the gate is per kind — the wheel's other alarms are untouched.
        assertEquals(AlertAvailability.Available, availability[MotionAlertKind.SPEED])
        assertEquals(AlertAvailability.Available, availability[MotionAlertKind.ESC_TEMP])
    }

    @Test fun an_unreported_duty_never_blames_a_controller_that_does_report_one() {
        // The reason a two-layer gate needs TWO reasons. `MotionAggregator`
        // folds hasDuty with `any` over the ONLINE controllers, so a vehicle
        // with a VESC at index 0 and a Begode at index 1 aggregates to
        // hasDuty = false whenever the VESC is offline and the wheel's truePWM
        // latch is still open. Naming "the lowest-indexed controller" there
        // would grey the row with "VESC controllers do not report duty" — false
        // about hardware that plainly does, and un-actionable, which is the
        // opposite of what F §10's stated reason is for.
        val mixed = vehicle(ControllerType.VESC, ControllerType.BEGODE)
        assertTrue(ControllerType.VESC.reportsDuty, "fixture: the named controller DOES report duty")
        assertEquals(0, mixed.controllers.minBy { it.index }.index, "fixture: VESC is the one that would be named")

        val reason = assertIs<AlertAvailability.Unavailable>(
            availabilityFor(mixed, fullSample().copy(hasDuty = false))[MotionAlertKind.DUTY]
        ).reason
        assertEquals(AlertUnavailableReason.ControllerHasNotReportedDuty, reason)
        assertIsNot<AlertUnavailableReason.ControllerReportsNoDuty>(
            reason,
            "layer 2 must not claim a permanent hardware fact, nor name a controller"
        )
    }

    @Test fun the_two_layers_give_different_reasons_because_they_are_different_claims() {
        // Layer 1 is permanent and names the hardware; layer 2 may stop being
        // true on the very next frame and names nothing. A single reason for
        // both would read to the rider as "your controller cannot do this".
        val kelly = availabilityFor(vehicle(ControllerType.KELLY), fullSample())[MotionAlertKind.DUTY]
        val begode = availabilityFor(
            vehicle(ControllerType.BEGODE),
            fullSample().copy(hasDuty = false)
        )[MotionAlertKind.DUTY]
        assertIs<AlertAvailability.Unavailable>(kelly)
        assertIs<AlertAvailability.Unavailable>(begode)
        assertNotEquals(kelly, begode, "the two layers must be distinguishable to a rider")
    }

    @Test fun hasDuty_defaults_to_true_so_no_other_decoder_had_to_change() {
        // The flag is opt-IN to absence: only a decoder that can tell "reported"
        // from "not yet reported" sets it, and every other sample keeps exactly
        // the availability it had before the layer existed.
        assertTrue(ControllerData().hasDuty)
        assertEquals(
            AlertAvailability.Available,
            availabilityFor(vehicle(ControllerType.VESC), fullSample())[MotionAlertKind.DUTY],
            "fixture check: fullSample() never sets hasDuty, so it must inherit true"
        )
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

    @Test fun the_disconnected_placeholder_is_not_evidence_and_never_claims_a_missing_sensor() {
        // activeMotion is a non-nullable flow that emits a bare ControllerData()
        // whenever nothing is connected. Taken as evidence it tells a double lie:
        // hasMotorTemp = false and speedSource = NONE read as "sensor missing",
        // while escTempC = 0f is above the -50 sentinel and reads as "sensor fine".
        val placeholder = ControllerData()
        assertFalse(placeholder.isConnected)
        assertFalse(placeholder.hasMotorTemp)
        assertEquals(SpeedSource.NONE, placeholder.speedSource)
        assertTrue(placeholder.hasEscTemp, "the false-Available half of the trap")

        // The third half of the trap, added with the observed duty layer: the
        // placeholder's hasDuty is TRUE (the field's default), so taken as
        // evidence it would also say "your wheel measures its PWM".
        assertTrue(placeholder.hasDuty, "the other false-Available half of the trap")

        val availability = availabilityFor(vehicle(ControllerType.VESC), placeholder)

        for (kind in MotionAlertKind.entries) {
            assertEquals(
                AlertAvailability.Unknown,
                availability[kind],
                "$kind: a disconnected placeholder was treated as an observation"
            )
        }
    }

    @Test fun a_disconnected_placeholder_arms_nothing_at_all() {
        // The placeholder is not an observation, so no kind may arm off it —
        // DUTY included since Part D Task 4 gave duty an observed layer. Note
        // this costs the alarm nothing: while the link is down there is no
        // reading to compare a threshold against anyway, and both production
        // callers re-gate on every live sample.
        val armed = armedRules(
            vehicle(ControllerType.VESC),
            ControllerData(),
            AlarmDefaults.all()
        )
        assertEquals(
            ArmedRules.NONE,
            armed,
            "a disconnected placeholder armed an alarm"
        )
    }

    @Test fun a_cached_last_good_sample_still_counts_as_evidence() {
        // isConnected is the discriminator, not "is the link up right now": a
        // caller that caches the last good sample keeps sensor knowledge across
        // the ride ending, so the settings screen does not flicker to Unknown.
        val cached = fullSample().copy(hasMotorTemp = false)
        assertTrue(cached.isConnected)
        val availability = availabilityFor(vehicle(ControllerType.VESC), cached)
        assertEquals(
            AlertAvailability.Unavailable(AlertUnavailableReason.NoMotorTempSensor),
            availability[MotionAlertKind.MOTOR_TEMP]
        )
    }

    @Test fun no_sample_yields_unknown_not_unavailable_for_every_observed_kind() {
        // The rider opens settings while disconnected. We must not tell them
        // their motor has no thermistor — we have never looked. DUTY joined
        // them in Part D Task 4: "this protocol reports duty" is not the same
        // statement as "this wheel's firmware fills the field in", and the
        // second is a sample's business. Unknown, never Unavailable, so the
        // thresholds stay editable ([isConfigurable]) while nothing may arm.
        val availability = availabilityFor(vehicle(ControllerType.VESC), latestMotion = null)
        for (kind in MotionAlertKind.entries) {
            assertEquals(AlertAvailability.Unknown, availability[kind], "$kind with no sample")
            assertTrue(availability.getValue(kind).isConfigurable, "$kind must stay editable")
        }
    }

    @Test fun isArmable_is_true_only_for_Available() {
        assertTrue(AlertAvailability.Available.isArmable)
        assertFalse(AlertAvailability.Unknown.isArmable)
        assertFalse(
            AlertAvailability.Unavailable(AlertUnavailableReason.NoController).isArmable
        )
    }

    @Test fun isConfigurable_and_isArmable_disagree_on_Unknown_and_that_is_the_point() {
        // The rider opens settings while disconnected: the thresholds must stay
        // editable (else they are untunable on a phone that is never connected
        // while parked), but nothing may fire off a sample we do not have.
        assertTrue(AlertAvailability.Unknown.isConfigurable, "Unknown must stay editable")
        assertFalse(AlertAvailability.Unknown.isArmable, "Unknown must never arm")
        // Available agrees with itself; Unavailable is refused by both.
        assertTrue(AlertAvailability.Available.isConfigurable)
        assertTrue(AlertAvailability.Available.isArmable)
        val gone = AlertAvailability.Unavailable(AlertUnavailableReason.NoMotorTempSensor)
        assertFalse(gone.isConfigurable, "an alert the hardware cannot supply has nothing to edit")
        assertFalse(gone.isArmable)
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
            armed.rules.firstOrNull { it.kind == MotionAlertKind.DUTY },
            "a duty rule reached the alarm engine on hardware that reports no duty"
        )
        // and the gate is not a blanket refusal — the available kind survives.
        assertEquals(listOf(MotionAlertKind.MOTOR_TEMP), armed.rules.map { it.kind })
    }

    @Test fun an_unknown_kind_with_enabled_levels_configured_also_never_arms() {
        // Never connected: no sample, so no value to compare. Nothing may fire.
        val configured = listOf(AlertRule(MotionAlertKind.MOTOR_TEMP, listOf(AlertLevel(110f))))
        val armed = armedRules(vehicle(ControllerType.VESC), latestMotion = null, configured = configured)
        assertEquals(ArmedRules.NONE, armed)
    }

    @Test fun a_vehicle_with_no_controllers_arms_nothing_even_from_full_defaults() {
        val armed = armedRules(packOnlyVehicle(), latestMotion = null, configured = AlarmDefaults.all())
        assertEquals(ArmedRules.NONE, armed, "battery-only vehicle armed a motion alarm")
    }

    @Test fun armedRules_drops_rules_the_rider_switched_off_and_keeps_the_rest() {
        val vesc = vehicle(ControllerType.VESC)
        val configured = listOf(
            AlertRule(MotionAlertKind.SPEED, emptyList()), // rider turned it off
            AlertRule(MotionAlertKind.DUTY, listOf(AlertLevel(80f)))
        )
        val armed = armedRules(vesc, fullSample(), configured)
        assertEquals(listOf(MotionAlertKind.DUTY), armed.rules.map { it.kind })
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
        assertEquals(listOf(AlertRule(MotionAlertKind.DUTY, levels)), armed.rules)
    }

    @Test fun armedRules_fails_closed_on_a_kind_the_availability_map_does_not_mention() {
        // Defensive: a caller assembling a partial map must not accidentally
        // open a gate that was never evaluated.
        val armed = armedRules(
            configured = listOf(AlertRule(MotionAlertKind.DUTY, listOf(AlertLevel(80f)))),
            availability = emptyMap()
        )
        assertEquals(ArmedRules.NONE, armed)
    }

    @Test fun armedRules_returns_a_type_the_engine_cannot_confuse_with_raw_config() {
        // F §10 is a claim about reachability, so the gate's output is a
        // distinct type: Task 5's engine signature can then refuse ungated
        // config at compile time instead of trusting convention.
        val configured = listOf(AlertRule(MotionAlertKind.DUTY, listOf(AlertLevel(80f))))
        val armed: ArmedRules = armedRules(vehicle(ControllerType.VESC), fullSample(), configured)
        assertEquals(configured, armed.rules)
        assertFalse(armed.isEmpty)
        assertTrue(ArmedRules.NONE.isEmpty)
    }

    @Test fun availability_covers_every_kind_so_the_ui_can_render_the_full_greyed_list() {
        val availability = availabilityFor(vehicle(ControllerType.VESC), fullSample())
        assertEquals(MotionAlertKind.entries, availability.keys.toList())
    }
}
