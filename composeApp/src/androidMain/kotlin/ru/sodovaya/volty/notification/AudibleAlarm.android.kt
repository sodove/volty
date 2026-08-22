package ru.sodovaya.volty.notification

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import ru.sodovaya.volty.domain.alert.AlarmCommand
import ru.sodovaya.volty.domain.alert.AlarmMusicMode
import ru.sodovaya.volty.domain.alert.AlarmModalities
import ru.sodovaya.volty.domain.alert.AlarmSignalPlanner
import ru.sodovaya.volty.domain.alert.AlarmState
import ru.sodovaya.volty.domain.alert.AlarmTone
import ru.sodovaya.volty.domain.alert.AlarmTransition
import ru.sodovaya.volty.domain.alert.AlarmVibration
import ru.sodovaya.volty.domain.alert.alarmPreviewDurationMs

/**
 * Android's audible alarm: an [AlarmTonePlayer] for the tone, a [Vibrator] for
 * the haptic, and an audio-focus request that ducks music without ever silencing
 * either.
 *
 * ### This class decides nothing
 *
 * Every question with an edge case — did anything change, may a preview play,
 * what does a modality switch do to a running alarm — is answered by
 * [AlarmSignalPlanner] in `commonMain`, where it is covered by tests. What is
 * left here is [applyTransition]: a four-branch `when` over the answer. If you
 * find yourself adding an `if` to this file, check first whether it belongs in
 * the planner.
 *
 * ### Resource ownership
 *
 * - **The `AudioTrack` is owned by the tone thread, never by this class.** See
 *   [AlarmTonePlayer]: it opens one and releases it in its own `finally`. This
 *   class holds only a reference to the *player*, and drops it the instant it
 *   calls `quit()`.
 * - **Nothing is retained while silent.** A `STOP` ends the thread, cancels the
 *   vibrator and abandons focus, so `update()` at level 0 leaves zero audio
 *   resources open — and a *long* silence is not a different case, because there
 *   is no idle-retention path and no timer keeping anything warm. The cost is one
 *   `AudioTrack` construction (order of ten milliseconds) on the transition out
 *   of silence, paid once per alarm rather than continuously.
 * - **[release] is the hard teardown** and briefly joins the tone thread, so a
 *   service's `onDestroy` returns with the track provably gone rather than
 *   probably gone.
 *
 * ### Focus policy (F §11, §5)
 *
 * `USAGE_ALARM` + `CONTENT_TYPE_SONIFICATION`. In [AlarmMusicMode.DUCK_MEDIA]
 * mode focus is requested as `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`; in
 * [AlarmMusicMode.PLAY_OVER_MEDIA] mode no focus is requested, so media keeps
 * playing at its current volume. **Losing focus never stops the alarm** — see
 * [onFocusChange]. Safety beats politeness; focus is a courtesy to the media
 * app, not a permission slip.
 *
 * Thread-safe: every entry point serialises on [lock].
 */
actual class AudibleAlarm(context: Context) {

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val vibrator = resolveVibrator(appContext)

    /** Delivers the focus callback and the preview timeout. Main looper — neither does real work. */
    private val handler = Handler(Looper.getMainLooper())

    private val planner = AlarmSignalPlanner()
    private val lock = Any()

    private var tonePlayer: AlarmTonePlayer? = null
    private var focusRequest: AudioFocusRequest? = null
    private var vibrating = false
    private var released = false

    /** The waveform currently looping, so an unchanged one is not re-issued. */
    private var currentVibration: AlarmVibration? = null

    /** When the last tone thread was started, for [reviveToneIfDead]'s backoff. */
    private var lastToneStartMs = 0L

    private val attributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private val endPreviewTask = Runnable { endPreview() }

    private val regainFocusTask = Runnable {
        synchronized(lock) {
            // Only while something is actually sounding: re-requesting focus for
            // an alarm that has since stopped would duck the rider's music for
            // nothing. A player whose thread has died counts as not sounding —
            // holding focus for it would duck music for silence.
            if (released || !isTonePlaying()) return@synchronized
            val command = planner.command as? AlarmCommand.Play ?: return@synchronized
            if (command.tone == null || command.musicMode != AlarmMusicMode.DUCK_MEDIA) return@synchronized
            val request = focusRequest ?: return@synchronized
            runCatching { audioManager?.requestAudioFocus(request) }
        }
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change -> onFocusChange(change) }

    actual fun update(state: AlarmState) {
        synchronized(lock) {
            if (released) return
            applyTransition(planner.update(state))
        }
    }

    actual fun setModalities(modalities: AlarmModalities) {
        synchronized(lock) {
            if (released) return
            applyTransition(planner.setModalities(modalities))
        }
    }

    actual fun preview(level: Int) {
        synchronized(lock) {
            if (released) return
            applyTransition(planner.preview(level))
            handler.removeCallbacks(endPreviewTask)
            // Asking the planner rather than the transition: previewing the same
            // level twice is a NONE, and the second press should still extend the
            // preview rather than let the first one's timer cut it short.
            if (!planner.isPreviewing) return
            val durationMs = alarmPreviewDurationMs(planner.command)
            if (durationMs > 0) handler.postDelayed(endPreviewTask, durationMs.toLong())
        }
    }

    actual fun stop() {
        synchronized(lock) {
            if (released) return
            handler.removeCallbacks(endPreviewTask)
            applyTransition(planner.stop())
        }
    }

    actual fun release() {
        val player = synchronized(lock) {
            if (released) return
            released = true
            handler.removeCallbacks(endPreviewTask)
            handler.removeCallbacks(regainFocusTask)
            planner.stop()
            stopVibration()
            abandonFocus()
            val current = tonePlayer
            tonePlayer = null
            current?.quit()
            current
        }
        // Joined outside the lock — the tone thread never takes it, but blocking a
        // caller inside a lock is how deadlocks get written later. Bounded, so a
        // wedged audio stack delays onDestroy rather than hanging it.
        runCatching { player?.join(TONE_JOIN_TIMEOUT_MS) }
    }

    private fun endPreview() {
        synchronized(lock) {
            if (released) return
            applyTransition(planner.endPreview())
        }
    }

    /**
     * The entire platform-side decision surface. See the class doc.
     *
     * Dispatches on the **command** first and the transition second, so "is there
     * something to play" is answered by the type system rather than by a discarded
     * null check. The combinations that cannot occur — a `Play` with `STOP`, a
     * `Silent` with `RESTART` — are `Unit` arms required for exhaustiveness, not
     * defensive guards: [alarmTransitionFor] decides both, and it is tested.
     */
    private fun applyTransition(transition: AlarmTransition) {
        when (val command = planner.command) {
            is AlarmCommand.Silent -> when (transition) {
                AlarmTransition.STOP -> {
                    startTone(null)
                    startVibration(null)
                    abandonFocus()
                }
                else -> Unit
            }

            is AlarmCommand.Play -> when (transition) {
                // Not "do nothing": this is the common case — a steady level
                // produces NONE on every sample — and so it is the only place a
                // tone thread that died on its own can be noticed.
                AlarmTransition.NONE -> reviveToneIfDead(command)

                AlarmTransition.RETUNE ->
                    if (!reviveToneIfDead(command)) {
                        command.tone?.let { tone -> tonePlayer?.retune(tone) }
                    }

                AlarmTransition.RESTART -> {
                    // Focus is about the speaker only: a vibration-only alarm has
                    // no reason to duck anybody's music.
                    if (command.tone != null) requestFocus(command.musicMode) else abandonFocus()
                    startTone(command.tone)
                    // An unchanged waveform is left running. Toggling the *tone*
                    // switch mid-alarm is a RESTART, and re-issuing an identical
                    // pattern would restart the buzz from its first pulse for no
                    // reason the rider asked for.
                    if (command.vibration != currentVibration) startVibration(command.vibration)
                }

                AlarmTransition.STOP -> Unit
            }
        }
    }

    /**
     * Restart the tone when it should be sounding but its thread has ended.
     * Returns whether it did.
     *
     * Without this the alarm can go **permanently** silent mid-ride: if the audio
     * server restarts, `AudioTrack.write` fails, [AlarmTonePlayer] releases its
     * track and exits, and every later sample is a `NONE` that touches nothing.
     * F §12's single-level temperature defaults mean the level then never changes
     * either — urgency is pinned at 1.0 from 91 °C to 200 °C — so nothing would
     * ever restart it. With vibration switched off that is total, silent,
     * unreported failure of the safety feature.
     *
     * Rate-limited by [TONE_REVIVE_MIN_INTERVAL_MS] so that an audio stack which
     * is broken rather than merely interrupted cannot make this build an
     * `AudioTrack` on every incoming sample.
     */
    private fun reviveToneIfDead(command: AlarmCommand.Play): Boolean {
        val tone = command.tone ?: return false
        val player = tonePlayer
        if (player != null && !player.finished) return false
        val now = SystemClock.uptimeMillis()
        if (now - lastToneStartMs < TONE_REVIVE_MIN_INTERVAL_MS) return false
        Log.w(TAG, "alarm tone thread ended while level ${command.level} was still sounding; restarting it")
        requestFocus(command.musicMode)
        startTone(tone)
        return true
    }

    /** Whether a tone thread exists and is still running its loop. */
    private fun isTonePlaying(): Boolean = tonePlayer?.finished == false

    /** Replace the running tone, or stop it when [tone] is null. */
    private fun startTone(tone: AlarmTone?) {
        tonePlayer?.quit()
        tonePlayer = null
        if (tone == null) return
        val player = AlarmTonePlayer(tone, attributes)
        tonePlayer = player
        lastToneStartMs = SystemClock.uptimeMillis()
        runCatching { player.start() }.onFailure {
            Log.e(TAG, "could not start the alarm tone thread", it)
            tonePlayer = null
        }
    }

    /** Replace the running haptic, or cancel it when [vibration] is null. */
    private fun startVibration(vibration: AlarmVibration?) {
        stopVibration()
        currentVibration = null
        if (vibration == null) return
        val vib = vibrator ?: return
        if (!vib.hasVibrator()) return
        // repeat = 0: loop the whole timing array, which is built to sum to one
        // full period, so the pattern repeats seamlessly until cancelled.
        val effect = VibrationEffect.createWaveform(vibration.waveformMs(), 0)
        runCatching { vibrate(vib, effect) }
            .onSuccess {
                vibrating = true
                currentVibration = vibration
            }
            .onFailure { Log.e(TAG, "could not start the alarm vibration", it) }
    }

    private fun stopVibration() {
        if (!vibrating) return
        vibrating = false
        runCatching { vibrator?.cancel() }
    }

    private fun requestFocus(mode: AlarmMusicMode) {
        if (mode == AlarmMusicMode.PLAY_OVER_MEDIA) {
            abandonFocus()
            return
        }
        if (focusRequest != null) return
        val manager = audioManager ?: return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            // We will not pause for a ducking request; see onFocusChange.
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener(focusListener, handler)
            .build()
        focusRequest = request
        // The result is ignored **on purpose** (F §11): whether or not the system
        // grants focus, the alarm plays. USAGE_ALARM is audible regardless, and a
        // denied request is not a reason to leave a rider unwarned.
        runCatching { manager.requestAudioFocus(request) }
    }

    private fun abandonFocus() {
        handler.removeCallbacks(regainFocusTask)
        val request = focusRequest ?: return
        focusRequest = null
        runCatching { audioManager?.abandonAudioFocusRequest(request) }
    }

    /**
     * **Nothing here stops the alarm, and that is the whole point** (F §5, §11).
     *
     * The only action taken on a loss is to ask for focus again after a short
     * delay, so that whatever grabbed it gets a moment to settle and we go back to
     * ducking it rather than fighting it in a tight loop. A `CAN_DUCK` loss is
     * ignored outright: it asks *us* to duck, and a safety alarm does not.
     */
    private fun onFocusChange(change: Int) {
        if (change == AudioManager.AUDIOFOCUS_GAIN ||
            change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
        ) {
            return
        }
        handler.removeCallbacks(regainFocusTask)
        handler.postDelayed(regainFocusTask, FOCUS_REGAIN_DELAY_MS)
    }

    @Suppress("DEPRECATION")
    private fun vibrate(vib: Vibrator, effect: VibrationEffect) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vib.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } else {
            vib.vibrate(effect, attributes)
        }
    }

    private companion object {
        const val TAG = "AudibleAlarm"

        /** How long to wait after a focus loss before asking for it back. */
        const val FOCUS_REGAIN_DELAY_MS = 500L

        /** Upper bound on how long [release] waits for the tone thread to let go of its track. */
        const val TONE_JOIN_TIMEOUT_MS = 500L

        /**
         * Floor on how often a died-on-its-own tone thread is restarted.
         *
         * Recovery has to be prompt — a second of silence in a safety alarm is
         * already too much — but an audio stack that is broken rather than briefly
         * interrupted would otherwise have this building an `AudioTrack` on every
         * incoming sample, several times a second, for the rest of the ride.
         */
        const val TONE_REVIVE_MIN_INTERVAL_MS = 1_000L

        @Suppress("DEPRECATION")
        fun resolveVibrator(context: Context): Vibrator? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            } else {
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
    }
}
