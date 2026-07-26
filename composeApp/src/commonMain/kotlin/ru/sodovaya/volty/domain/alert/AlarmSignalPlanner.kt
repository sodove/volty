package ru.sodovaya.volty.domain.alert

/**
 * Everything the audible alarm decides, with none of the machinery that makes
 * a sound.
 *
 * `AudibleAlarm`'s Android actual owns an `AudioTrack`, a tone thread, a
 * `Vibrator` and an audio-focus request — four things a unit test cannot touch.
 * What it must *not* also own is the reasoning: whether this sample changes
 * anything, whether a preview may play, what a modality switch does to a
 * currently-sounding alarm. Those are decisions with edge cases, and every one of
 * them lives here, where a test can drive them.
 *
 * The actual's whole remaining job is a four-branch `when` over the returned
 * [AlarmTransition]. That is the split the brief asks for: keep the platform
 * layer as thin as possible above a pure core.
 *
 * **No clock and no coroutines.** The planner never asks how much time has
 * passed; a preview ends because someone calls [endPreview], not because a timer
 * fired here. The platform owns the timer. That keeps this class deterministic
 * and keeps its tests free of virtual time.
 *
 * Not thread-safe — the Android actual serialises calls on its own lock.
 */
class AlarmSignalPlanner(modalities: AlarmModalities = AlarmModalities.DEFAULT) {

    /** The rider's three switches, as last set. */
    var modalities: AlarmModalities = modalities
        private set

    /**
     * What should be playing right now.
     *
     * The single source of "what is the platform currently doing", which is what
     * makes idempotence checkable: every entry point routes through
     * [applyCommand], so a call that changes nothing returns
     * [AlarmTransition.NONE] and cannot restart a tone by accident.
     */
    var command: AlarmCommand = AlarmCommand.Silent
        private set

    /** The most recent engine state. Kept so a preview can hand the alarm back. */
    private var liveState: AlarmState = AlarmState.SILENT

    /** The step a preview is playing, or null when nothing is being previewed. */
    private var previewLevel: Int? = null

    /** Whether the current [command] is a preview rather than a real alarm. */
    val isPreviewing: Boolean get() = previewLevel != null

    /**
     * Fold one engine state in.
     *
     * **A real alarm outranks a preview**, always: a sounding state cancels a
     * preview in progress and takes the speaker, because the rider poking at the
     * settings screen while the motor is overheating is owed the overheating, not
     * the demo. A *silent* state leaves a preview alone — otherwise every sample
     * that arrives while the rider auditions a level would cut the preview off
     * after a few hundred milliseconds, and the button would appear broken.
     */
    fun update(state: AlarmState): AlarmTransition {
        liveState = state
        if (previewLevel != null && !state.isSounding) return AlarmTransition.NONE
        previewLevel = null
        return applyCommand(alarmCommandFor(state, modalities))
    }

    /**
     * Play [level]'s signal for the settings screen's "проверить сигнал" button.
     *
     * **Refused outright while a live alarm is sounding.** Silencing a real alarm
     * to demonstrate a different one is the one way this button could hurt
     * somebody. The refusal is a [AlarmTransition.NONE], so the platform layer
     * does nothing and the real alarm keeps playing uninterrupted.
     *
     * A preview that the modality gate turns into [AlarmCommand.Silent] — master
     * switch off, or both tone and vibration off — leaves nothing being previewed,
     * so no [endPreview] is owed and none is needed.
     */
    fun preview(level: Int): AlarmTransition {
        if (liveState.isSounding) return AlarmTransition.NONE
        val next = alarmPreviewCommand(level, modalities)
        previewLevel = if (next is AlarmCommand.Play) level else null
        return applyCommand(next)
    }

    /**
     * End a preview and hand the alarm back to whatever the engine last said.
     *
     * Deliberately *not* "go silent": the engine may have started sounding since
     * the preview began — [update] would have cancelled the preview in that case,
     * but a modality change or a race need not have gone through [update] — so the
     * safe end state is the live one, recomputed, not a hard stop.
     */
    fun endPreview(): AlarmTransition {
        if (previewLevel == null) return AlarmTransition.NONE
        previewLevel = null
        return applyCommand(alarmCommandFor(liveState, modalities))
    }

    /**
     * Apply the rider's switches, taking effect on anything already playing.
     *
     * Turning tones off mid-alarm has to silence the tone *now*, not at the next
     * sample — a rider reaching for that switch wants it to stop. So the current
     * command is recomputed rather than merely stored for later.
     */
    fun setModalities(next: AlarmModalities): AlarmTransition {
        // No `if (next == modalities) return NONE` short-circuit here, and that is
        // deliberate: recomputing an unchanged set of switches produces an equal
        // command, and [applyCommand] already answers NONE for that. The guard was
        // written, swept, and found to be an equivalent mutant — a second code path
        // that no test could tell from this one.
        modalities = next
        val level = previewLevel
        val recomputed = if (level != null) alarmPreviewCommand(level, next)
        else alarmCommandFor(liveState, next)
        if (recomputed !is AlarmCommand.Play) previewLevel = null
        return applyCommand(recomputed)
    }

    /**
     * Hard stop, forgetting the live state — the ride ended or the service is
     * going away. The next [update] is judged on its own sample, which mirrors
     * [AlarmController.reset].
     */
    fun stop(): AlarmTransition {
        liveState = AlarmState.SILENT
        previewLevel = null
        return applyCommand(AlarmCommand.Silent)
    }

    private fun applyCommand(next: AlarmCommand): AlarmTransition {
        val transition = alarmTransitionFor(command, next)
        command = next
        return transition
    }
}

/**
 * How long a preview should play, in milliseconds.
 *
 * A preview has to teach a *rhythm*, and one burst is not a rhythm — a rider who
 * hears a single beep has learned the pitch and nothing about the repetition rate
 * that F §12 makes half the signal. So the answer is two full cycles of the
 * slower of the two modalities, which is what [PREVIEW_CYCLES] means.
 *
 * The clamp is what keeps that sane at both ends. Step 3 repeats roughly twice a
 * second, so two cycles is under a second — long enough to count the pulses but
 * over before the rider is sure they pressed anything, hence [PREVIEW_MIN_MS].
 * Step 1 rests for over a second between single beeps, and its two cycles run to
 * three seconds, which is already at the edge of what a settings screen should
 * hold the speaker for; [PREVIEW_MAX_MS] stops any future slower step running
 * away with it.
 *
 * Lives here as a pure function of the command rather than as a constant in the
 * Android layer because it is arithmetic over the tone table, and it changes
 * silently whenever those numbers change.
 */
fun alarmPreviewDurationMs(command: AlarmCommand): Int = when (command) {
    is AlarmCommand.Silent -> 0
    is AlarmCommand.Play -> {
        // The slower modality decides: previewing vibration-only must still run
        // long enough for two buzzes, and the haptic periods are the longer pair.
        val period = maxOf(command.tone?.periodMs ?: 0, command.vibration?.periodMs ?: 0)
        (period * PREVIEW_CYCLES).coerceIn(PREVIEW_MIN_MS, PREVIEW_MAX_MS)
    }
}

/** Full repetitions of the pattern a preview aims to play. See [alarmPreviewDurationMs]. */
const val PREVIEW_CYCLES: Int = 2

/** Floor on a preview, milliseconds — short steps must not flash past. */
const val PREVIEW_MIN_MS: Int = 1200

/** Ceiling on a preview, milliseconds — the settings screen does not hold the speaker forever. */
const val PREVIEW_MAX_MS: Int = 3000
