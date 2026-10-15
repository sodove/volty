package ru.sodovaya.volty.data.history

import ru.sodovaya.volty.domain.history.*
import kotlin.test.*

class HistoryBatchFingerprintTest {
    private fun gpsBatch() = HistoryBatch("ride", emptyList(),
        listOf(HistoryGpsFix(HistoryTick(10000, 100, 1), 56.0, 60.0, 5.0, null)), emptyList(), "receipt")

    @Test fun addingGpsProviderChangesReceiptIdentityAndCannotAliasAnotherProvider() {
        val old = gpsBatch()
        val gps = old.copy(gps = old.gps.map { it.copy(provider = HistoryGpsProvider.GPS) })
        val network = old.copy(gps = old.gps.map { it.copy(provider = HistoryGpsProvider.NETWORK) })
        assertNotEquals(historyBatchFingerprint(old), historyBatchFingerprint(gps))
        assertNotEquals(historyBatchFingerprint(gps), historyBatchFingerprint(network))
    }

    @Test fun providerAbsentRetainsTheExistingV1DurableFingerprint() {
        // Independent big-endian v1 encoding, pinned before adding the provider field.
        assertEquals("f4b46b3dddf997ea6135a0020de23e3e377c5df906955c7fa931bf7c394b3d7a", historyBatchFingerprint(gpsBatch()))
    }

    @Test fun distinctUtf16IdsCannotAliasThroughEncodingReplacement() {
        val sample = HistorySample(HistoryTick(10000, 100, 1), HistorySeriesKey("\uD800", "power_w"), 1.0)
        val batch = HistoryBatch("ride", listOf(sample), emptyList(), emptyList(), "receipt")
        val changed = batch.copy(samples = listOf(sample.copy(key = sample.key.copy(sourceId = "\uD801"))))
        assertNotEquals(historyBatchFingerprint(batch), historyBatchFingerprint(changed))
    }
}
