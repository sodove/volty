package ru.sodovaya.volty.data.ble

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryObservationGateTest {
    @Test fun preparationMustAcknowledgeBeforeAnyDispatch() {
        val gate = HistoryObservationGate(); var calls = 0
        assertFalse(gate.dispatch { calls++ }); assertEquals(0, calls)
        assertTrue(gate.open()); assertTrue(gate.dispatch { calls++ }); assertEquals(1, calls)
    }

    @Test fun closingFencesNewDispatchButBothClosersAwaitOriginalInFlightAction() = runTest {
        val gate = HistoryObservationGate(); assertTrue(gate.open())
        val issued = mutableListOf<String>()
        val closers = mutableListOf<Job>()
        try {
            assertTrue(gate.dispatch {
                repeat(2) { index -> closers += backgroundScope.launch(Dispatchers.Unconfined) {
                    gate.closeAndJoin(); issued += "closed-$index"
                } }
                assertTrue(closers.all { !it.isCompleted })
                assertFalse(gate.dispatch { issued += "late-dispatch" })
                issued += "accepted-dispatch"
            })
            closers.forEach { it.join() }
            assertEquals("accepted-dispatch", issued.first())
            assertEquals(setOf("accepted-dispatch", "closed-0", "closed-1"), issued.toSet())
        } finally { closers.forEach { it.cancelAndJoin() } }
    }

    @Test fun throwingActionStillReleasesItsReservation() = runTest {
        val gate = HistoryObservationGate(); assertTrue(gate.open())
        val failure = IllegalStateException("observer failed")
        assertSame(failure, assertFailsWith<IllegalStateException> { gate.dispatch { throw failure } })
        gate.closeAndJoin()
    }

    @Test fun closedOwnerCannotReopenEvenIfItNeverBecameReady() = runTest {
        for (ready in listOf(false, true)) {
            val gate = HistoryObservationGate(); if (ready) assertTrue(gate.open())
            gate.closeAndJoin(); gate.closeAndJoin()
            assertFalse(gate.open()); assertFalse(gate.dispatch { fail("Retired owner dispatched") })
        }
    }
}
