package ru.sodovaya.volty.data.history

import ru.sodovaya.volty.domain.history.*
import kotlin.test.*

class HistorySourceBoundaryTest {
    private val origin = HistoryTick(10000, 100, 1)
    private val ride = HistoryRide("ride", "bike", origin, null, HistoryRideStatus.RECORDING)
    private val sources = listOf("a", "b").map { HistorySource(it, HistorySourceKind.BATTERY, it) }
    private fun tick(delta: Long) = HistoryTick(10000 + delta, 100 + delta, 1)
    private fun rows(source: String) = listOf(0L, 1000L).map {
        HistorySample(tick(it), HistorySeriesKey(source, "power_w"), 100.0)
    }
    private fun gap(source: String?) = HistoryMarker(tick(500), HistoryMarkerKind.BLE_GAP, source)

    @Test fun a_lost_peer_cannot_erase_earned_energy_or_completeness() {
        val stats = historyStats(rows("b"), listOf(gap("a")), 1.0)
        assertTrue(stats.complete)
        assertEquals(100.0 / 3600, stats.energyWh)
    }

    @Test fun an_own_source_boundary_still_breaks_energy() {
        val stats = historyStats(rows("a"), listOf(gap("a")), 1.0)
        assertFalse(stats.complete)
        assertNull(stats.energyWh)
    }

    @Test fun overview_only_inserts_a_gap_for_its_own_source() {
        assertEquals(rows("b"), historyOverview(rows("b"), listOf(gap("a"))))
        assertEquals(1, historyOverview(rows("a"), listOf(gap("a"))).count { it.unknown == HistoryUnknownReason.GAP })
    }

    @Test fun streamed_pages_keep_healthy_peer_continuity_across_page_boundary() {
        val healthy = HistoryOverviewAccumulator(listOf(gap("a")))
        healthy.offerPage(rows("b").take(1)); healthy.offerPage(rows("b").drop(1))
        assertEquals(rows("b"), healthy.snapshot())
        val lost = HistoryOverviewAccumulator(listOf(gap("a")))
        lost.offerPage(rows("a").take(1)); lost.offerPage(rows("a").drop(1))
        assertTrue(lost.snapshot().any { it.unknown == HistoryUnknownReason.GAP })
    }

    @Test fun bucket_marker_cannot_modify_another_source() {
        val bucket = HistoryBucketAccumulator(origin, rows("b").first().key)
        bucket.offer(rows("b").first())
        val before = bucket.snapshot()
        assertNull(bucket.mark(gap("a")))
        assertEquals(before, bucket.snapshot())
        assertFalse(assertNotNull(bucket.mark(gap("b"))).complete)
    }

    @Test fun projection_only_flushes_detail_and_marks_quality_for_the_affected_source() {
        val first = listOf(rows("a").first(), rows("b").first())
        val projection = HistoryRecordingProjection(ride, sources)
            .prepare(HistoryBatch(ride.id, first, emptyList(), emptyList()), false).next
        val affected = projection.prepare(HistoryBatch(ride.id, emptyList(), emptyList(), listOf(gap("a"))), false)
        assertEquals(listOf(first.first()), affected.batch.samples)
        assertEquals(setOf("a"), affected.batch.buckets.map { it.key.sourceId }.toSet())
        assertFalse(affected.batch.buckets.single().complete)
        val remaining = affected.next.prepare(HistoryBatch(ride.id, emptyList(), emptyList(), emptyList()), true)
        assertEquals(listOf(first.last()), remaining.batch.samples)
    }

    @Test fun unscoped_ble_loss_still_breaks_both_sources() {
        for (source in listOf("a", "b")) {
            assertFalse(historyStats(rows(source), listOf(gap(null)), 1.0).complete)
            assertTrue(historyOverview(rows(source), listOf(gap(null))).any { it.unknown == HistoryUnknownReason.GAP })
        }
    }

    @Test fun source_scope_cannot_hide_a_global_clock_or_gps_boundary() {
        assertFailsWith<IllegalArgumentException> { gap(" ") }
        for (kind in listOf(HistoryMarkerKind.CLOCK_CHANGE, HistoryMarkerKind.GPS_GAP, HistoryMarkerKind.OVERFLOW)) {
            assertFailsWith<IllegalArgumentException> { HistoryMarker(tick(500), kind, "a") }
        }
    }

    @Test fun projection_rejects_unregistered_scoped_markers_before_any_series_exists() {
        val projection = HistoryRecordingProjection(ride, sources)
        assertFailsWith<IllegalArgumentException> {
            projection.prepare(HistoryBatch(ride.id, emptyList(), emptyList(), listOf(gap("foreign"))), true)
        }
        val known = projection.prepare(HistoryBatch(ride.id, emptyList(), emptyList(), listOf(gap("a"))), true)
        assertTrue(known.batch.samples.isEmpty())
        assertTrue(known.batch.buckets.isEmpty())
    }
}
