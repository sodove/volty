package ru.sodovaya.volty.data.ble

import ru.sodovaya.volty.domain.model.BleDiagnosticsState
import ru.sodovaya.volty.domain.plausibility.PlausibilityRule
import ru.sodovaya.volty.domain.plausibility.RejectedFrame
import ru.sodovaya.volty.presentation.settings.SettingsComponent
import ru.sodovaya.volty.presentation.settings.bleCaptureFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The retired-ring store and the dump it feeds. The repository's wiring (which drop sites call retire) is BLE-bound
 * and not unit-testable here; these pin the store's contract and that the dump prints what it is handed.
 */
class RetiredRejectionRingsTest {
    private fun ring(hex: String, at: Long = 3L) =
        listOf(RejectedFrame(at, 0x04, PlausibilityRule.R1_ODOMETER_WITNESS, hex, "2816164 m", "+7 m"))

    private fun dump(store: RetiredRejectionRings) =
        bleCaptureFile(SettingsComponent.State(diagnostics = BleDiagnosticsState(retiredLinks = store.snapshot())), emptyList())

    @Test fun aTornReadThenDisconnectStillShowsItsRawHexInTheDump() {
        val store = RetiredRejectionRings()
        store.retire("AA", ring("55aa002bf8ab"))
        val file = dump(store)
        assertTrue(file.contains("retired links"), file)
        assertTrue(file.contains("retiredLink=AA connection=1"), file)
        assertTrue(file.contains("raw=55aa002bf8ab"), file)
    }

    @Test fun noRetiredBlockWhenNothingWasRetired() {
        val store = RetiredRejectionRings()
        store.retire("AA", emptyList())
        assertFalse(dump(store).contains("retired links"))
    }

    @Test fun aReconnectKeepsTheEarlierEntriesAndTagsEachConnection() {
        val store = RetiredRejectionRings()
        store.retire("AA", ring("aaaa"))
        store.retire("AA", ring("bbbb", at = 1L)) // the next connection's ring: atFrame restarted
        val rings = store.snapshot()
        assertEquals(listOf(1, 2), rings.map { it.connection })
        assertEquals(listOf("aaaa", "bbbb"), rings.map { it.frames.single().rawHex })
        val file = dump(store)
        assertTrue(file.contains("raw=aaaa") && file.contains("raw=bbbb"), file)
    }

    @Test fun retiringTheSameRingTwiceDoesNotDuplicateIt() {
        val store = RetiredRejectionRings()
        store.retire("AA", ring("aaaa")); store.retire("AA", ring("aaaa"))
        assertEquals(1, store.snapshot().size)
    }

    @Test fun theMapIsBoundedToTheLastFourAddresses() {
        val store = RetiredRejectionRings()
        for (a in listOf("A1", "A2", "A3", "A4", "A5")) store.retire(a, ring("00$a"))
        assertEquals(listOf("A2", "A3", "A4", "A5"), store.snapshot().map { it.deviceAddress })
        store.retire("A2", ring("ffff")) // A2 is now the most recent, so A3 is next to go
        store.retire("A6", ring("0006"))
        assertEquals(setOf("A2", "A4", "A5", "A6"), store.snapshot().map { it.deviceAddress }.toSet())
    }
}
