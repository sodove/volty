package ru.sodovaya.volty.domain.video

import kotlin.test.Test
import kotlin.test.assertEquals

class OverlayPolicyTest {
    @Test
    fun laterMatchingRuleOverridesOnlyTheFieldsItDefines() {
        val baseStyle = OverlayStyle(background = "#11000000", foreground = "#FFFFFFFF")
        val widget = OverlayWidget(
            id = "speed",
            metric = VideoMetric.SPEED,
            style = baseStyle,
            rules = listOf(
                OverlayRule(
                    comparison = OverlayComparison.GT,
                    threshold = 20.0,
                    foreground = "#FFFF0000",
                    background = "#22000000",
                ),
                OverlayRule(
                    comparison = OverlayComparison.GTE,
                    threshold = 40.0,
                    foreground = "#FF00FF00",
                ),
            ),
        )

        assertEquals(
            baseStyle.copy(background = "#22000000", foreground = "#FF00FF00"),
            overlayStyle(widget, VideoReading(value = 50.0)),
        )
    }

    @Test
    fun unknownOrNonFiniteReadingDoesNotEvaluateNumericRules() {
        val baseStyle = OverlayStyle(background = "#11000000", foreground = "#FFFFFFFF")
        val widget = OverlayWidget(
            id = "speed",
            metric = VideoMetric.SPEED,
            style = baseStyle,
            rules = listOf(
                OverlayRule(
                    comparison = OverlayComparison.LT,
                    threshold = 1.0,
                    foreground = "#FFFF0000",
                ),
            ),
        )

        assertEquals(baseStyle, overlayStyle(widget, null))
        assertEquals(baseStyle, overlayStyle(widget, VideoReading(value = Double.NaN)))
        assertEquals(baseStyle, overlayStyle(widget, VideoReading(value = Double.POSITIVE_INFINITY)))
    }

    @Test
    fun everyComparisonUsesItsNamedBoundary() {
        val cases = listOf(
            OverlayComparison.GT to (11.0 to true),
            OverlayComparison.GTE to (10.0 to true),
            OverlayComparison.LT to (9.0 to true),
            OverlayComparison.LTE to (10.0 to true),
            OverlayComparison.EQ to (10.0 to true),
            OverlayComparison.NE to (11.0 to true),
        )

        cases.forEach { (comparison, input) ->
            val widget = OverlayWidget(
                id = "speed",
                metric = VideoMetric.SPEED,
                style = OverlayStyle(foreground = "#FFFFFFFF"),
                rules = listOf(
                    OverlayRule(comparison = comparison, threshold = 10.0, foreground = "#FFFF0000"),
                ),
            )

            val expectedForeground = if (input.second) "#FFFF0000" else "#FFFFFFFF"
            assertEquals(
                expectedForeground,
                overlayStyle(widget, VideoReading(value = input.first)).foreground,
                "$comparison at ${input.first}",
            )
        }
    }

    @Test
    fun overlayCanBeEditedOnlyWhileRecordingIsIdle() {
        assertEquals(true, canEditVideoOverlay(VideoRecordingPhase.IDLE))
        assertEquals(false, canEditVideoOverlay(VideoRecordingPhase.STARTING))
        assertEquals(false, canEditVideoOverlay(VideoRecordingPhase.RECORDING))
        assertEquals(false, canEditVideoOverlay(VideoRecordingPhase.STOPPING))
    }
}
