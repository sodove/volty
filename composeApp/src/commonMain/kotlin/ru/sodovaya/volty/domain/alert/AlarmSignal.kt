package ru.sodovaya.volty.domain.alert

import kotlin.math.roundToInt

/**
 * What one audible step sounds like: a **burst of short pulses at one pitch**,
 * then silence, repeating (F §12).
 *
 * The burst/rest split is the field layout on purpose. [restMs] is stored and
 * [periodMs] derived, not the other way round, because urgency shortens the
 * *silence* between bursts — shrinking the whole period would eventually make it
 * shorter than the burst it has to contain, and a "period" with no rest in it is
 * a continuous tone, not a rhythm.
 *
 * @property frequencyHz pitch of every pulse in the burst.
 * @property pulseCount how many pulses the burst contains — the categorical cue.
 * @property pulseOnMs how long each pulse sounds.
 * @property pulseGapMs silence *inside* the burst, between pulses (0 when there is one pulse).
 * @property restMs silence after the burst, before the next one.
 */
data class AlarmTone(
    val frequencyHz: Float,
    val pulseCount: Int,
    val pulseOnMs: Int,
    val pulseGapMs: Int,
    val restMs: Int
) {
    /** Length of the burst itself — pulses plus the gaps between them. */
    val burstMs: Int get() = pulseCount * pulseOnMs + (pulseCount - 1) * pulseGapMs

    /** Burst plus rest: how often the pattern repeats. */
    val periodMs: Int get() = burstMs + restMs
}

/**
 * The haptic half of one step, in the same burst/rest shape as [AlarmTone] but
 * with its own numbers, and deliberately **not phase-locked to the tone**.
 *
 * Two transducers with two different response times: a vibration motor needs
 * roughly a tenth of a second to be felt at all, so its pulses are longer and
 * fewer than the tone's, and the OS runs the waveform on its own clock anyway
 * (see [waveformMs]). Pretending the two are synchronised would be a claim the
 * platform cannot honour.
 *
 * **Urgency does not bend these.** A rider cannot resolve a 10 % change in buzz
 * rate through a pocket, and re-issuing the waveform to express one would restart
 * it from the beginning — a stutter in exchange for nothing. The haptic carries
 * the *step*; the tone carries the refinement.
 */
data class AlarmVibration(
    val pulseCount: Int,
    val pulseOnMs: Int,
    val pulseGapMs: Int,
    val restMs: Int
) {
    val burstMs: Int get() = pulseCount * pulseOnMs + (pulseCount - 1) * pulseGapMs
    val periodMs: Int get() = burstMs + restMs

    /**
     * Android `VibrationEffect.createWaveform` timings: alternating **off, on,
     * off, on, …** starting with an off-duration, which is why the first entry is
     * always 0 (start buzzing immediately). The trailing entry is [restMs], so
     * looping the array from index 0 reproduces the pattern exactly and the whole
     * array sums to [periodMs].
     *
     * Built here rather than in the Android actual so the arithmetic — the part
     * that can silently be off by one pulse — is covered by a common test.
     */
    fun waveformMs(): LongArray {
        val timings = ArrayList<Long>(2 * pulseCount + 1)
        timings.add(0L)
        repeat(pulseCount) { index ->
            timings.add(pulseOnMs.toLong())
            if (index < pulseCount - 1) timings.add(pulseGapMs.toLong())
        }
        timings.add(restMs.toLong())
        return timings.toLongArray()
    }
}

/**
 * Which of the three modality switches are on (F §4). All default true; the
 * master switch overrides both others.
 */
data class AlarmModalities(
    val alarmEnabled: Boolean = true,
    val toneEnabled: Boolean = true,
    val vibrationEnabled: Boolean = true
) {
    companion object {
        /** Everything on — the shipped defaults, and the state before prefs have loaded. */
        val DEFAULT: AlarmModalities = AlarmModalities()
    }
}

/**
 * The whole instruction to the platform layer: either be quiet, or play this.
 *
 * A value type rather than a pile of setter calls so "did anything actually
 * change?" is `==`, which is what makes idempotence ([alarmTransitionFor])
 * something a test can check instead of something the Android code hopes it got
 * right.
 */
sealed interface AlarmCommand {

    /** Nothing sounds and nothing buzzes. */
    data object Silent : AlarmCommand

    /**
     * @property level the audible step, 1..[AlarmToneTable.STEPS] — already
     *   clamped to the table, so two engine levels that map to the same sound
     *   compare equal here and do not cause a needless restart.
     * @property tone null when the rider turned tones off.
     * @property vibration null when the rider turned vibration off.
     *
     * Both null never occurs: [alarmCommandFor] returns [Silent] instead, because
     * "play nothing" and "be silent" are the same instruction and two spellings
     * of it would be two things for the platform layer to handle identically.
     */
    data class Play(
        val level: Int,
        val tone: AlarmTone?,
        val vibration: AlarmVibration?
    ) : AlarmCommand
}

/**
 * What the platform layer has to *do* to get from one command to the next.
 *
 * This exists so the Android actual is a four-branch `when` over a decision made
 * and tested in common code, rather than a tangle of "did the level change? did
 * the tone go away?" checks buried next to the `AudioTrack` calls where nothing
 * can reach them.
 */
enum class AlarmTransition {
    /** Identical command. Touch nothing — this is the idempotence the brief requires. */
    NONE,

    /**
     * Same step, same modalities, only the urgency-bent tone parameters differ.
     * The tone loop keeps running and picks the new pitch up at the next burst;
     * the vibration waveform is not re-issued. **Nothing restarts.**
     */
    RETUNE,

    /** Start, or tear down and start again: a different step, or a modality switched. */
    RESTART,

    /** Stop everything. */
    STOP
}

/**
 * The escalating tone table (F §12).
 *
 * ### Why three fixed steps rather than one continuous climb
 *
 * F §12: out of the box only duty has two levels, so only duty produces a
 * ramping urgency. ESC temperature and motor temperature ship **one** level each
 * — urgency is 1.0 the instant they engage and still 1.0 at 200 °C — and urgency
 * is pinned to 0 for the whole hold band while a step releases. A tone that
 * conveyed escalation through urgency alone would therefore sound *identical* at
 * 91 °C and at 200 °C, identical for ESC and motor temperature, and would say
 * nothing at all while a step is letting go.
 *
 * So each step is distinguishable **on its own**, by three independent cues, and
 * urgency only refines within a step:
 *
 * | Step | Pitch | Pulses per burst | Burst rate |
 * |---|---|---|---|
 * | 1 | 1000 Hz | 1 | ~0.7 /s |
 * | 2 | 1600 Hz | 2 | 1.25 /s |
 * | 3 | 2400 Hz | 3 | ~2.2 /s |
 *
 * ### Why these numbers, for a phone in a pocket
 *
 * - **1–2.4 kHz.** Small phone speakers roll off hard below ~700 Hz, so a
 *   "serious" low tone is the one a pocket eats first; above ~4 kHz cloth
 *   absorbs quickly and the sound stops localising. 1–2.4 kHz sits in the band
 *   the speaker can actually drive *and* the ear is most sensitive to, and each
 *   step is a musical fifth or so above the last — an interval that survives
 *   muffling, where 10 % would not.
 * - **Pulse count 1/2/3.** The categorical cue, and the one that survives worst
 *   case: through a jacket a rider may lose the pitch entirely and still count
 *   *beep* versus *beep-beep* versus *beep-beep-beep*. This is why the design
 *   does not rely on pitch alone. Medical and industrial alarm standards code
 *   urgency this way for the same reason.
 * - **Rest shrinking with the step.** Step 1 is a calm reminder with over a
 *   second of silence; step 3 is a near-continuous trill with 70 ms of gap. Rate
 *   is the cue that reads even when the phone is buzzing against fabric.
 *
 * ### The separation invariant
 *
 * Urgency bends pitch up by [PITCH_BEND] and cuts the rest by [REST_SHRINK], and
 * those bends are **capped so a step at full urgency never reaches the next
 * step's parameters** — otherwise the refinement would erase the thing it
 * refines, which is precisely F §12's failure. `AlarmToneTableTest` pins this:
 * every step's bent range stays strictly clear of its neighbours' on both pitch
 * and period. Anyone editing these numbers has to keep that true.
 */
object AlarmToneTable {

    /** How many audible steps exist — the rider's level list caps at [AlertRule.MAX_LEVELS]. */
    const val STEPS: Int = 3

    /** Fraction by which full urgency raises the pitch within a step. */
    const val PITCH_BEND: Float = 0.12f

    /** Fraction by which full urgency shortens the silence between bursts. */
    const val REST_SHRINK: Float = 0.35f

    /**
     * Urgency is rounded to this many steps before it reaches a tone.
     *
     * Without it, a duty reading wandering through its band would produce a
     * different [AlarmCommand] on *every* sample, and the platform layer — which
     * decides what to do by comparing commands — would see a change each time.
     * The comparison would still be correct and the tone would still not restart
     * (that is [AlarmTransition.RETUNE]'s job), but the pitch would wobble
     * audibly on noise. Eight steps across a band is finer than a rider can hear
     * and coarse enough to sit still.
     */
    const val URGENCY_STEPS: Int = 8

    /** Step 1..3, indexed from 0. See the class doc for why each number is what it is. */
    val TONES: List<AlarmTone> = listOf(
        AlarmTone(frequencyHz = 1000f, pulseCount = 1, pulseOnMs = 150, pulseGapMs = 0, restMs = 1250),
        AlarmTone(frequencyHz = 1600f, pulseCount = 2, pulseOnMs = 110, pulseGapMs = 80, restMs = 500),
        AlarmTone(frequencyHz = 2400f, pulseCount = 3, pulseOnMs = 90, pulseGapMs = 55, restMs = 70)
    )

    /** The haptic per step — same 1/2/3 pulse coding, motor-sized durations. */
    val VIBRATIONS: List<AlarmVibration> = listOf(
        AlarmVibration(pulseCount = 1, pulseOnMs = 400, pulseGapMs = 0, restMs = 1100),
        AlarmVibration(pulseCount = 2, pulseOnMs = 250, pulseGapMs = 150, restMs = 350),
        AlarmVibration(pulseCount = 3, pulseOnMs = 150, pulseGapMs = 90, restMs = 120)
    )

    /**
     * Round urgency onto [URGENCY_STEPS] and clamp it into 0..1.
     *
     * **NaN answers 0.** [AlarmController] cannot currently produce one (a NaN
     * reading engages no step, so urgency comes back 0), but `coerceIn` passes
     * NaN straight through — it fails both comparisons — and a NaN frequency
     * would render as garbage rather than as a quiet tone. The guard costs a
     * comparison and removes a whole class of silent failure downstream.
     */
    fun quantizeUrgency(urgency: Float): Float {
        if (urgency.isNaN()) return 0f
        val clamped = urgency.coerceIn(0f, 1f)
        return (clamped * URGENCY_STEPS).roundToInt() / URGENCY_STEPS.toFloat()
    }

    /** The tone for a step, refined by urgency. [level] is clamped into 1..[STEPS]. */
    fun toneFor(level: Int, urgency: Float): AlarmTone {
        val base = TONES[indexFor(level)]
        val u = quantizeUrgency(urgency)
        return base.copy(
            frequencyHz = base.frequencyHz * (1f + PITCH_BEND * u),
            restMs = (base.restMs * (1f - REST_SHRINK * u)).roundToInt()
        )
    }

    /** The haptic for a step. Urgency is not a parameter — see [AlarmVibration]. */
    fun vibrationFor(level: Int): AlarmVibration = VIBRATIONS[indexFor(level)]

    /**
     * A level above [STEPS] uses the top step rather than crashing. The engine
     * cannot produce one today ([AlertRule.MAX_LEVELS] is 3), but "the loudest
     * thing we have" is the only safe answer to a level we do not recognise, and
     * an index-out-of-bounds inside the alarm would silence it entirely.
     */
    private fun indexFor(level: Int): Int = (level - 1).coerceIn(0, TONES.lastIndex)

    /** [level] clamped to the table — what [AlarmCommand.Play.level] carries. */
    fun clampLevel(level: Int): Int = indexFor(level) + 1
}

/**
 * Turn the engine's [AlarmState] and the rider's switches into one instruction.
 *
 * The whole modality gate lives here, in common code, so "master off means
 * silence" is a tested fact rather than a branch somewhere in the Android layer.
 */
fun alarmCommandFor(state: AlarmState, modalities: AlarmModalities): AlarmCommand =
    alarmCommandFor(state.level, state.urgency, modalities)

/** As above, from a bare level and urgency — the form [alarmPreviewCommand] needs. */
fun alarmCommandFor(level: Int, urgency: Float, modalities: AlarmModalities): AlarmCommand {
    if (!modalities.alarmEnabled) return AlarmCommand.Silent
    if (level <= 0) return AlarmCommand.Silent
    val tone = if (modalities.toneEnabled) AlarmToneTable.toneFor(level, urgency) else null
    val vibration = if (modalities.vibrationEnabled) AlarmToneTable.vibrationFor(level) else null
    if (tone == null && vibration == null) return AlarmCommand.Silent
    return AlarmCommand.Play(AlarmToneTable.clampLevel(level), tone, vibration)
}

/**
 * What the settings screen's "проверить сигнал" button plays for a step
 * (F §11): the step at **urgency 0**, its plainest form, because a preview is
 * meant to teach the rider the step's identity — the thing F §12 says must carry
 * the signal — not a particular point inside a band they are not currently in.
 *
 * It goes through the same gate as a live alarm, so previewing with tones off
 * gives the rider only the buzz they will actually get, and previewing with the
 * master switch off gives nothing. Task 9 should grey the button out in that
 * case; a second gating rule here would be a second thing to keep in sync.
 */
fun alarmPreviewCommand(level: Int, modalities: AlarmModalities): AlarmCommand =
    alarmCommandFor(level, 0f, modalities)

/**
 * What changed between two commands, in terms of what the platform must do.
 *
 * The one that matters is [AlarmTransition.RETUNE]: a rising duty reading inside
 * one step produces a new command every few samples, and re-issuing the whole
 * alarm for each would chop the tone into stutters. Same step, same modalities ⇒
 * the loop keeps running and only its parameters move.
 *
 * A modality switch at the same step is a [AlarmTransition.RESTART] and not a
 * retune: a tone that was off and is now on has no running loop to retune, and a
 * vibration waveform can only change by being re-issued.
 *
 * **The `level` comparison is load-bearing even though the vibration comparison
 * usually implies it.** With vibration on, two steps always differ in their
 * waveform, so the level check never decides anything. With vibration switched
 * *off* it is the only thing separating an escalation from an urgency retune —
 * and a retune keeps the running loop, which picks a new tone up only at its next
 * burst. Escalating that way would make the rider wait out the old step's rest
 * (up to 1.25 s at step 1) before hearing that things had got worse. Pinned by
 * `a_step_change_restarts_even_when_only_the_tone_is_playing`.
 */
fun alarmTransitionFor(previous: AlarmCommand, next: AlarmCommand): AlarmTransition = when {
    previous == next -> AlarmTransition.NONE
    next is AlarmCommand.Silent -> AlarmTransition.STOP
    previous is AlarmCommand.Play && next is AlarmCommand.Play &&
        previous.level == next.level &&
        (previous.tone == null) == (next.tone == null) &&
        previous.vibration == next.vibration -> AlarmTransition.RETUNE
    else -> AlarmTransition.RESTART
}
