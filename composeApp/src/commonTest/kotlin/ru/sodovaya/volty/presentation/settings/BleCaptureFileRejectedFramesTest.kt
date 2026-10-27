package ru.sodovaya.volty.presentation.settings

import ru.sodovaya.volty.domain.model.BleDiagnosticLink
import ru.sodovaya.volty.domain.model.BleDiagnosticsState
import ru.sodovaya.volty.domain.plausibility.LedgerKind
import ru.sodovaya.volty.domain.plausibility.PlausibilityRule
import ru.sodovaya.volty.domain.plausibility.RejectedFrame
import kotlin.test.Test
import kotlin.test.assertTrue

class BleCaptureFileRejectedFramesTest {
    private fun state(vararg frames: RejectedFrame) = SettingsComponent.State(
        diagnostics = BleDiagnosticsState(
            isCapturing = false,
            activeLinks = listOf(BleDiagnosticLink("AA:BB", "GotWay", "BEGODE", null, null, null, null, rejectedFrames = frames.toList())),
        ),
    )

    @Test fun aRejectedFrameAppearsWithItsRuleAndExactRawHexEvenWithoutAnyCapture() {
        val hex = "55aa002bf8ab28001c2000c80000010000120418" + "5a5a5a5a"
        val file = bleCaptureFile(state(RejectedFrame(7L, 0x04, PlausibilityRule.R1_ODOMETER_WITNESS, hex, "2816164 m", "+7 m")), emptyList())
        assertTrue(file.contains("rejected frames (1"), file)
        assertTrue(file.contains("kind=REJECTED rule=R1_ODOMETER_WITNESS type=0x04 reference=2816164 m witness=+7 m raw=$hex"), file)
    }

    @Test fun aLinkWithoutRejectionsStatesZeroAndAnEntryWithNoNumbersPrintsDashes() {
        assertTrue(bleCaptureFile(state(), emptyList()).contains("rejected frames (0"))
        val file = bleCaptureFile(state(RejectedFrame(1L, 0x01, PlausibilityRule.R13_PACK_VOLTAGE_WITNESS, "00ff", null, null, LedgerKind.REBASED)), emptyList())
        assertTrue(file.contains("kind=REBASED rule=R13_PACK_VOLTAGE_WITNESS type=0x01 reference=- witness=- raw=00ff"), file)
    }

    @Test fun theDumpLineCarriesTheWallClockTimeOfTheEntry() {
        val file = bleCaptureFile(state(RejectedFrame(7L, 0x04, PlausibilityRule.R1_ODOMETER_WITNESS, "00ff", null, null, atEpochMillis = 1_700_000_000_123L)), emptyList())
        assertTrue(file.contains("rejectedFrame at=1700000000123 atFrame=7 kind=REJECTED"), file)
        val unstamped = bleCaptureFile(state(RejectedFrame(7L, 0x04, PlausibilityRule.R1_ODOMETER_WITNESS, "00ff", null, null)), emptyList())
        assertTrue(unstamped.contains("rejectedFrame at=- atFrame=7"), unstamped)
    }
}
