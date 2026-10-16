package ru.sodovaya.volty.data.history

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import ru.sodovaya.volty.domain.history.HistoryTick
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class HistorySourceAdmissionGateTest {
    private fun tick(delta: Long = 0) = HistoryTick(10000 + delta, 100 + delta, 1)

    @Test fun source_negative_rejects_its_new_receipts_but_preserves_healthy_telemetry_and_gps() = runTest {
        val gate = HistoryAdmissionGate(tick())
        assertNull(gate.closeSources(setOf("a")))
        val lost = gate.reserveTelemetry(tick(10), setOf("a"))
        val healthy = gate.reserveTelemetry(tick(20), setOf("b"))
        try {
            assertNull(lost)
            assertNotNull(healthy)
            assertTrue(gate.reserve(tick(30))); gate.release()
        } finally { lost?.release(); healthy?.release() }
    }

    @Test fun source_close_waits_its_accepted_prefix_while_other_sources_keep_flowing() = runTest {
        val gate = HistoryAdmissionGate(tick())
        val a = assertNotNull(gate.reserveTelemetry(tick(10), setOf("a")))
        val b = assertNotNull(gate.reserveTelemetry(tick(20), setOf("b")))
        val closing = async { gate.closeSources(setOf("a")) }
        var nextB: HistoryAdmissionGate.SourceReservation? = null
        try {
            runCurrent(); assertFalse(closing.isCompleted)
            nextB = assertNotNull(gate.reserveTelemetry(tick(30), setOf("b")))
            a.release(); runCurrent()
            assertTrue(closing.isCompleted, "Healthy reservations cannot hold the lost source's boundary")
            assertEquals(tick(10), closing.await())
        } finally { a.release(); b.release(); nextB?.release(); closing.cancelAndJoin() }
    }

    @Test fun global_finish_still_waits_telemetry_and_gps_and_reservation_release_is_once_only() = runTest {
        val gate = HistoryAdmissionGate(tick())
        val a = assertNotNull(gate.reserveTelemetry(tick(10), setOf("a")))
        assertTrue(gate.reserve(tick(20)))
        var gpsHeld = true
        assertEquals(tick(20), gate.close())
        val finishing = async { gate.awaitIdle() }
        try {
            runCurrent(); assertFalse(finishing.isCompleted)
            a.release(); a.release(); runCurrent()
            assertFalse(finishing.isCompleted, "Duplicate source release must not release GPS ownership")
            gate.release()
            gpsHeld = false
            finishing.await()
        } finally { a.release(); if (gpsHeld) gate.release(); finishing.cancelAndJoin() }
    }

    @Test fun source_reopen_allows_fresh_receipts_without_replacing_the_global_gate() = runTest {
        val gate = HistoryAdmissionGate(tick())
        gate.closeSources(setOf("a"))
        assertTrue(gate.reopenSources(setOf("a")))
        assertFalse(gate.reopenSources(setOf("a")))
        val next = assertNotNull(gate.reserveTelemetry(tick(10), setOf("a")))
        next.release()
    }

    @Test fun reopening_a_source_cannot_resurrect_a_globally_retired_owner() = runTest {
        val gate = HistoryAdmissionGate(tick())
        gate.closeSources(setOf("a")); gate.close()
        assertFalse(gate.reopenSources(setOf("a")))
        assertNull(gate.reserveTelemetry(tick(10), setOf("a")))
        assertNull(gate.reserveTelemetry(tick(10), setOf("b")))
        assertFalse(gate.reserve(tick(10)))
    }

    @Test fun reservation_freezes_source_ids_before_caller_mutates_its_collection() = runTest {
        val gate = HistoryAdmissionGate(tick())
        val ids = mutableSetOf("a")
        val a = assertNotNull(gate.reserveTelemetry(tick(10), ids))
        ids.clear(); ids += "b"
        val b = assertNotNull(gate.reserveTelemetry(tick(20), setOf("b")))
        val closing = async { gate.closeSources(setOf("a")) }
        try {
            runCurrent(); assertFalse(closing.isCompleted)
            a.release(); runCurrent()
            assertTrue(closing.isCompleted)
            assertEquals(tick(10), closing.await())
        } finally { a.release(); b.release(); closing.cancelAndJoin() }
    }

    @Test fun source_boundary_uses_its_own_accepted_tick_instead_of_a_later_peer_or_gps_tick() = runTest {
        val gate = HistoryAdmissionGate(tick())
        val a = assertNotNull(gate.reserveTelemetry(tick(10), setOf("a"))); a.release()
        val b = assertNotNull(gate.reserveTelemetry(tick(20), setOf("b"))); b.release()
        assertTrue(gate.reserve(tick(30))); gate.release()
        assertEquals(tick(10), gate.closeSources(setOf("a")))
        assertNull(gate.closeSources(setOf("unobserved")))
        assertEquals(tick(30), gate.close())
    }

    @Test fun empty_or_blank_source_sets_are_rejected_before_accepting_any_reservation() = runTest {
        val gate = HistoryAdmissionGate(tick())
        for (invalid in listOf(emptySet(), setOf(" "))) {
            assertFailsWith<IllegalArgumentException> {
                gate.reserveTelemetry(tick(10), invalid)?.release()
            }
            assertFailsWith<IllegalArgumentException> { gate.closeSources(invalid) }
            assertFailsWith<IllegalArgumentException> { gate.reopenSources(invalid) }
        }
        assertEquals(tick(), gate.close())
        gate.awaitIdle()
    }
}
