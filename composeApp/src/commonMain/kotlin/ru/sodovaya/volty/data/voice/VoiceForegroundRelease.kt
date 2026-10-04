package ru.sodovaya.volty.data.voice

import ru.sodovaya.volty.domain.voice.VoiceGeneration

internal data class VoiceForegroundResourceState(
    val wanted: Boolean,
    val cleanupPending: Boolean,
    val failedCleanup: Set<VoiceGeneration>,
    val nativeInFlight: Int,
    val submissionFailed: Boolean,
)

/** Local foreground release only; remote/SFU stop remains separate evidence. */
internal fun canReleaseVoiceForeground(demanded: Boolean, owners: List<VoiceForegroundResourceState>): Boolean =
    !demanded && owners.all {
        !it.wanted && !it.cleanupPending && it.failedCleanup.isEmpty() &&
            it.nativeInFlight == 0 && !it.submissionFailed
    }
