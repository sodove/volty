package ru.sodovaya.volty.domain.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VoiceDiagnosticReportHistoryTest {
    @Test fun lateFinalUpdatesOlderOwnerWithoutReplacingCurrentReport() {
        val history = VoiceDiagnosticReportHistory()
        // Two different owners can have the same generation; identity must not alias.
        val older = VoiceDiagnostics(VoiceGeneration(1))
        val current = VoiceDiagnostics(VoiceGeneration(1))
        history.remember(older, "older connecting")
        history.remember(current, "current recording")
        assertTrue(history.replace(older, "older CLEANUP_ERROR CLOSED"))
        assertEquals(listOf("older CLEANUP_ERROR CLOSED", "current recording"), history.reports())
    }

    @Test fun evictedOwnerCannotReenterHistoryOrOverwriteNewestReport() {
        val history = VoiceDiagnosticReportHistory(2)
        val first = VoiceDiagnostics(VoiceGeneration(1))
        val second = VoiceDiagnostics(VoiceGeneration(2))
        val third = VoiceDiagnostics(VoiceGeneration(3))
        history.remember(first, "first")
        history.remember(second, "second")
        history.remember(third, "third")
        assertFalse(history.replace(first, "late first CLOSED"))
        assertEquals(listOf("second", "third"), history.reports())
    }

    @Test fun repeatedFinalKeepsOrderAndExportIsAnIndependentSnapshot() {
        val history = VoiceDiagnosticReportHistory()
        val owner = VoiceDiagnostics(VoiceGeneration(1))
        history.remember(owner, "CLEANUP_ERROR")
        val exported = history.reports()
        assertTrue(history.replace(owner, "CLOSED"))
        assertEquals(listOf("CLOSED"), history.reports())
        assertEquals(listOf("CLEANUP_ERROR"), exported)
        history.clear()
        assertFalse(history.replace(owner, "retired"))
        assertTrue(history.reports().isEmpty())
    }
}
