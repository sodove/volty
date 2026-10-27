package ru.sodovaya.volty.data.history

import ru.sodovaya.volty.domain.model.*
import ru.sodovaya.volty.domain.pwm.PwmEvidence
import kotlin.test.*

class HistoryEstimatedDutyTest {
    private fun controller(duty: TelemetryReading, role: TelemetryRole = TelemetryRole.CONTRIBUTOR) =
        TelemetryObservation(TelemetryOrigin.CONTROLLER, 0, listOf(
            TelemetryReading.measured(TelemetryMetric.CONTROLLER_BATTERY_CURRENT, 3.0), duty), role)
    private fun rawZero() = TelemetryReading.measured(TelemetryMetric.DUTY, 0.0)
    private fun TelemetryObservation.duty() = readings.single { it.metric == TelemetryMetric.DUTY }
    private fun view(evidence: PwmEvidence, percent: Float?, id: String = "v") = HistoryPwmView(id, evidence, percent)

    @Test fun aNeverReportingWheelRecordsTheEstimateInsteadOfTheRawConstant() {
        val out = withHistoryEstimatedDuty(controller(rawZero()), "v") { view(PwmEvidence.NEVER_REPORTED, 37.5f) }
        assertEquals(37.5, out.duty().value)
        assertNull(out.duty().unknown)
        // The other readings of the observation ride through untouched.
        assertEquals(3.0, out.readings.single { it.metric == TelemetryMetric.CONTROLLER_BATTERY_CURRENT }.value)
    }

    @Test fun aNeverReportingWheelWithNoEstimateRecordsUnknownNotTheConstantZero() {
        val out = withHistoryEstimatedDuty(controller(rawZero()), "v") { view(PwmEvidence.NEVER_REPORTED, null) }
        assertNull(out.duty().value)
        assertEquals(TelemetryUnknown.UNAVAILABLE, out.duty().unknown)
        val noView = withHistoryEstimatedDuty(controller(rawZero()), "v") { null }
        // No view at all (no estimator yet) is not proof of anything: the decoder's reading stands.
        assertEquals(0.0, noView.duty().value)
    }

    @Test fun aWheelThatReportsItsOwnDutyIsLeftAlone() {
        val real = TelemetryReading.measured(TelemetryMetric.DUTY, 41.0)
        for (evidence in listOf(PwmEvidence.REPORTS, PwmEvidence.UNKNOWN)) {
            // Deliberately incoherent: an estimate beside non-NEVER_REPORTED evidence, which no producer emits.
            val out = withHistoryEstimatedDuty(controller(real), "v") { view(evidence, 99f) }
            assertEquals(41.0, out.duty().value, "$evidence")
        }
    }

    @Test fun anEstimateOfAnotherVehicleIsNeverRecorded() {
        val out = withHistoryEstimatedDuty(controller(rawZero()), "v") { view(PwmEvidence.NEVER_REPORTED, 55f, id = "other") }
        assertEquals(0.0, out.duty().value)
    }

    @Test fun nonFiniteEstimateIsUnknown() {
        val out = withHistoryEstimatedDuty(controller(rawZero()), "v") { view(PwmEvidence.NEVER_REPORTED, Float.NaN) }
        assertNull(out.duty().value)
    }

    @Test fun observationsWithoutADutyReadingAndBatteryObservationsPassThrough() {
        val noDuty = TelemetryObservation(TelemetryOrigin.CONTROLLER, 0, listOf(TelemetryReading.measured(TelemetryMetric.SPEED, 5.0)))
        assertSame(noDuty, withHistoryEstimatedDuty(noDuty, "v") { view(PwmEvidence.NEVER_REPORTED, 10f) })
        val battery = TelemetryObservation(TelemetryOrigin.BATTERY, 0, listOf(TelemetryReading.measured(TelemetryMetric.VOLTAGE, 70.0)))
        assertSame(battery, withHistoryEstimatedDuty(battery, "v") { view(PwmEvidence.NEVER_REPORTED, 10f) })
    }
}
