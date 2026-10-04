package ru.sodovaya.volty.data.voice

import ru.sodovaya.volty.domain.voice.VoiceGeneration
import kotlin.test.*

class VoiceForegroundReleaseTest {
    private val clean = VoiceForegroundResourceState(false, false, emptySet(), 0, false)

    @Test fun newestCleanOwnerCannotHideOldNativeProducerAfterSuccessfulClose() {
        assertFalse(canReleaseVoiceForeground(false, listOf(clean.copy(nativeInFlight = 1), clean)))
        assertTrue(canReleaseVoiceForeground(false, listOf(clean, clean)))
    }

    @Test fun inactiveIntentCannotFabricateLocalResourceOrSubmissionAcknowledgements() {
        for (unknown in listOf(clean.copy(cleanupPending = true),
            clean.copy(failedCleanup = setOf(VoiceGeneration(1))),
            clean.copy(submissionFailed = true), clean.copy(nativeInFlight = -1))) {
            assertFalse(canReleaseVoiceForeground(false, listOf(clean, unknown)))
        }
    }

    @Test fun pendingForegroundDemandSurvivesInitialIdleBeforeVoiceJoin() {
        assertFalse(canReleaseVoiceForeground(true, listOf(clean)))
        assertFalse(canReleaseVoiceForeground(false, listOf(clean.copy(wanted = true))))
        assertTrue(canReleaseVoiceForeground(false, emptyList()))
    }
}
