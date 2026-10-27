package ru.sodovaya.volty.domain.pwm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [OnsetLookback.onsetFor] with a `firstSetAtMillis` (Begode R12 debounce): when the lookback cannot find the sample the
 * first set frame arrived beside, it must say so, never fall back to the confirmed edge (a later, faster onset, LOW).
 */
class OnsetLookbackDebounceTest {
    private fun p(at: Long, kmh: Float) = OnsetPoint(at, kmh, 96f, null)

    @Test
    fun `the normal case takes the sample before the first set frame's cycle`() {
        var l = OnsetLookback()
        for (s in listOf(p(1_000, 64f), p(1_310, 66.2f), p(1_620, 68.4f), p(1_930, 70.1f))) l = l.push(s)
        assertEquals(OnsetVerdict.Take(p(1_310, 66.2f)), l.onsetFor(p(1_930, 70.1f), firstSetAtMillis = 1_630))
    }

    @Test
    fun `a lookback reset between the first set frame and the confirming one finds no onset instead of a late one`() {
        // The control above takes 66.2; here the samples before 1_630 were forgotten (a sample with no voltage resets
        // the lookback), and the plain rule against the confirmed edge would take 68.4: LOW.
        var l = OnsetLookback()
        for (s in listOf(p(1_000, 64f), p(1_310, 66.2f), p(1_620, 68.4f))) l = l.push(s)
        l = OnsetLookback()
        for (s in listOf(p(1_640, 68.4f), p(1_930, 70.1f), p(1_940, 70.1f))) l = l.push(s)
        assertEquals(OnsetVerdict.NoPrevious, l.onsetFor(p(1_940, 70.1f), firstSetAtMillis = 1_630))
        assertEquals(OnsetVerdict.Take(p(1_640, 68.4f)), l.onsetFor(p(1_940, 70.1f)), "the unguarded fallback is what this refuses")
    }

    @Test
    fun `more samples than the history holds between the two frames find no onset instead of a late one`() {
        var l = OnsetLookback().push(p(1_000, 64f)).push(p(1_310, 66.2f))
        l = l.push(p(1_620, 68.4f))
        // battery-driven republishes of the same motion, more than HISTORY of them, all after the first set frame
        for (k in 0 until OnsetLookback.HISTORY + 2) l = l.push(p(1_640L + k, 70.1f))
        assertTrue(l.recent.all { it.atMillis > 1_630 })
        assertEquals(OnsetVerdict.NoPrevious, l.onsetFor(l.last!!, firstSetAtMillis = 1_630))
    }

    @Test
    fun `a stall of more than a second before the first set frame is not pinned down`() {
        var l = OnsetLookback()
        for (s in listOf(p(1_000, 64f), p(1_310, 66.2f), p(4_000, 70.1f))) l = l.push(s)
        assertEquals(OnsetVerdict.TooFast, l.onsetFor(p(4_000, 70.1f), firstSetAtMillis = 3_900))
    }
}
