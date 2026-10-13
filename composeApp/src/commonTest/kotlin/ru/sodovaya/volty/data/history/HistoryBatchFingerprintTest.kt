package ru.sodovaya.volty.data.history

import ru.sodovaya.volty.domain.history.*
import kotlin.test.*

class HistoryBatchFingerprintTest {
    @Test fun distinctUtf16IdsCannotAliasThroughEncodingReplacement() {
        val sample = HistorySample(HistoryTick(10000, 100, 1), HistorySeriesKey("\uD800", "power_w"), 1.0)
        val batch = HistoryBatch("ride", listOf(sample), emptyList(), emptyList(), "receipt")
        val changed = batch.copy(samples = listOf(sample.copy(key = sample.key.copy(sourceId = "\uD801"))))
        assertNotEquals(historyBatchFingerprint(batch), historyBatchFingerprint(changed))
    }
}
