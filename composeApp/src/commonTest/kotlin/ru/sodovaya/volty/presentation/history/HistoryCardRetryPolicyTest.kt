package ru.sodovaya.volty.presentation.history

import kotlin.test.*

/** Final review M2: the ride card offers a retry when either the preview or the card-stats read failed. */
class HistoryCardRetryPolicyTest {
    private fun visible(previewErrors: Set<String>, statsErrors: Set<String>) =
        historyCardRetryVisible("r", HistoryPreviewsState(errors = previewErrors), HistoryCardStatsState(errors = statsErrors))

    @Test fun eitherFailureOffersTheRetryAndHealthyReadsOfferNone() {
        assertFalse(visible(emptySet(), emptySet()))
        assertTrue(visible(setOf("r"), emptySet()))
        assertTrue(visible(emptySet(), setOf("r")), "the duty read failed and the preview succeeded: retry must still show")
        assertTrue(visible(setOf("r"), setOf("r")))
        assertFalse(visible(setOf("other"), setOf("other")), "another ride's failure is not this card's")
    }

    @Test fun theSeparateStatsOnlyAffordanceIsLeftToThePreviewIconWhenThePreviewFailedToo() {
        fun only(previewErrors: Set<String>, statsErrors: Set<String>) =
            historyCardStatsRetryOnly("r", HistoryPreviewsState(errors = previewErrors), HistoryCardStatsState(errors = statsErrors))
        assertTrue(only(emptySet(), setOf("r")))
        assertFalse(only(setOf("r"), setOf("r")), "the preview's own retry icon already covers it")
        assertFalse(only(emptySet(), emptySet()))
    }
}
