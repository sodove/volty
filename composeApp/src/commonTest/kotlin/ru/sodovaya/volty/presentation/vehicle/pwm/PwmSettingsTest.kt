package ru.sodovaya.volty.presentation.vehicle.pwm

import ru.sodovaya.volty.domain.model.PwmManual
import ru.sodovaya.volty.domain.pwm.PwmEvidence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PwmSettingsTest {
    @Test fun thresholdIsAWholePercentFromOneToOneHundred() {
        assertEquals(80, parsePwmThreshold("80"))
        assertEquals(1, parsePwmThreshold(" 1 "))
        assertEquals(100, parsePwmThreshold("100"))
        for (bad in listOf("", "0", "101", "-5", "8o", "80.5", "NaN", "Infinity")) assertNull(parsePwmThreshold(bad), bad)
    }

    @Test fun manualIsBothNumbersOrNeither() {
        assertEquals(ManualParse.Value(PwmManual(81.7f, 96.5f)), parsePwmManual("81,7", "96.5"))
        assertEquals(ManualParse.Cleared, parsePwmManual("", "  "))
        for ((a, b) in listOf("81" to "", "" to "96", "0" to "96", "81" to "-1", "x" to "96", "Infinity" to "96", "81" to "NaN")) {
            assertEquals(ManualParse.Invalid, parsePwmManual(a, b), "$a / $b")
        }
    }

    @Test fun entryFollowsTheEvidenceAndNothingElse() {
        assertEquals(PwmEntry.WIZARD, pwmEntryOf(PwmEvidence.NEVER_REPORTED))
        assertEquals(PwmEntry.WHEEL_REPORTS, pwmEntryOf(PwmEvidence.REPORTS))
        assertEquals(PwmEntry.NOT_KNOWN_YET, pwmEntryOf(PwmEvidence.UNKNOWN))
    }

    @Test fun hubSummaryJudgesCalibrationOnlyForAProvenNonReportingWheel() {
        val cal = PwmCalibrationSummary(1.1f, ru.sodovaya.volty.domain.pwm.PwmCalibrationSource.WIZARD, 1)
        val manual = PwmManual(81.7f, 96.5f)
        // incoherent on purpose: a calibration AND a manual point on a wheel that reports its own PWM
        assertEquals(PwmHubSummary.REPORTS, pwmHubSummaryOf(PwmEvidence.REPORTS, cal, manual))
        assertEquals(PwmHubSummary.NOT_KNOWN_YET, pwmHubSummaryOf(PwmEvidence.UNKNOWN, cal, manual))
        assertEquals(PwmHubSummary.CALIBRATED, pwmHubSummaryOf(PwmEvidence.NEVER_REPORTED, cal, null))
        assertEquals(PwmHubSummary.CALIBRATED, pwmHubSummaryOf(PwmEvidence.NEVER_REPORTED, null, manual))
        assertEquals(PwmHubSummary.UNCALIBRATED, pwmHubSummaryOf(PwmEvidence.NEVER_REPORTED, null, null))
    }

    @Test fun aManualSourcedStoredRowIsNeverShownAsTheCalibration() {
        fun cal(source: ru.sodovaya.volty.domain.pwm.PwmCalibrationSource) = ru.sodovaya.volty.domain.pwm.PwmCalibration(
            k = 9.99f, r = 0f, points = 1, source = source, spread = null,
            basis = ru.sodovaya.volty.domain.pwm.PwmBasis.FREE_SPIN, onsets = emptyList(),
        )
        assertNull(pwmSummaryOf(cal(ru.sodovaya.volty.domain.pwm.PwmCalibrationSource.MANUAL)), "its k is stale: replaced on read by pwmManual")
        assertEquals(1.0f, pwmSummaryOf(cal(ru.sodovaya.volty.domain.pwm.PwmCalibrationSource.WIZARD).copy(k = 1.0f))?.k)
        assertEquals(1.0f, pwmSummaryOf(cal(ru.sodovaya.volty.domain.pwm.PwmCalibrationSource.RIDING).copy(k = 1.0f))?.k)
        assertNull(pwmSummaryOf(null))
        assertNull(pwmSummaryOf(cal(ru.sodovaya.volty.domain.pwm.PwmCalibrationSource.WIZARD).copy(k = Float.NaN)))
    }
}
