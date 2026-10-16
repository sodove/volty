package ru.sodovaya.volty.data.history

import ru.sodovaya.volty.domain.history.*
import kotlin.test.*

class HistoryBatchFingerprintTest {
    @Test fun markerScopeChangesDurablePayloadIdentityWithoutAliasingAnotherSource() {
        val marker = HistoryMarker(HistoryTick(10000, 100, 1), HistoryMarkerKind.BLE_GAP)
        val global = HistoryBatch("ride", emptyList(), emptyList(), listOf(marker), "receipt")
        val a = global.copy(markers = listOf(marker.copy(sourceId = "a")))
        val b = global.copy(markers = listOf(marker.copy(sourceId = "b")))
        assertNotEquals(historyBatchFingerprint(global), historyBatchFingerprint(a))
        assertNotEquals(historyBatchFingerprint(a), historyBatchFingerprint(b))
        val withGps = a.copy(gps = gpsBatch().gps)
        assertNotEquals(historyBatchFingerprint(withGps),
            historyBatchFingerprint(withGps.copy(gps = withGps.gps.map { it.copy(provider = HistoryGpsProvider.GPS) })))
    }

    @Test fun unscopedMarkersAndKnownGpsRetainPreviouslyEncodedV1AndV2Receipts() {
        val global = HistoryBatch("ride", emptyList(), emptyList(),
            listOf(HistoryMarker(HistoryTick(10000, 100, 1), HistoryMarkerKind.BLE_GAP)), "receipt")
        // Independent big-endian pre-scope encoding, pinned before changing fingerprints.
        assertEquals("5ec83716f3523449e455c25a86d3a30f6e0d6937249429eb47940a40ca57572f", historyBatchFingerprint(global))
        val gps = gpsBatch().copy(gps = gpsBatch().gps.map { it.copy(provider = HistoryGpsProvider.GPS) })
        assertEquals("e08069d4a77d9adcb354fec7f000f7c2babbc1a16cf4a3b9192028d19d7ad70c", historyBatchFingerprint(gps))
    }

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
