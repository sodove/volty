package ru.sodovaya.volty.data.ble

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Rider, 2026-10-26: a free-spin test with the dumper on outran a 200-record buffer in seconds, and
 * the only way out was the clipboard. The ring now keeps minutes; the UI still gets the newest 200.
 */
class BleDiagnosticBufferTest {

    private fun BleDiagnosticBuffer.feed(count: Int, from: Int = 0) = repeat(count) { i ->
        appendNotification((from + i).toLong(), "AA:BB", "T4", "BEGODE", "ffe1", byteArrayOf((from + i).toByte()))
    }

    @Test
    fun theRingKeepsFarMoreThanTheUiShows() {
        val buffer = BleDiagnosticBuffer().apply { start() }
        buffer.feed(5_000)
        assertEquals(5_000, buffer.state.value.recordCount)
        assertEquals(BleDiagnosticBuffer.UI_RECORDS, buffer.state.value.records.size)
        assertEquals(4_999L, buffer.state.value.records.last().timestampEpochMillis, "the UI shows the newest")
        val all = buffer.snapshot()
        assertEquals(5_000, all.size)
        assertEquals(0L, all.first().timestampEpochMillis, "the snapshot is oldest first")
    }

    @Test
    fun theRingDropsTheOldestPastItsCapacity() {
        val buffer = BleDiagnosticBuffer().apply { start() }
        buffer.feed(BleDiagnosticBuffer.MAX_RECORDS + 10)
        val all = buffer.snapshot()
        assertEquals(BleDiagnosticBuffer.MAX_RECORDS, all.size)
        assertEquals(10L, all.first().timestampEpochMillis)
        assertEquals(BleDiagnosticBuffer.MAX_RECORDS, buffer.state.value.recordCount)
    }

    @Test
    fun clearEmptiesTheRingAndTheCount() {
        val buffer = BleDiagnosticBuffer().apply { start() }
        buffer.feed(300)
        buffer.clear()
        assertTrue(buffer.snapshot().isEmpty())
        assertEquals(0, buffer.state.value.recordCount)
        assertTrue(buffer.state.value.records.isEmpty())
    }

    @Test
    fun nothingIsRecordedWhileStopped() {
        val buffer = BleDiagnosticBuffer()
        buffer.feed(10)
        assertEquals(0, buffer.state.value.recordCount)
        assertTrue(buffer.snapshot().isEmpty())
    }
}
