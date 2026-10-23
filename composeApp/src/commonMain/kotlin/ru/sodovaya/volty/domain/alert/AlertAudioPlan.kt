package ru.sodovaya.volty.domain.alert

import ru.sodovaya.volty.domain.audio.AlertSoundKind
import ru.sodovaya.volty.domain.audio.AlertSoundSettings
import ru.sodovaya.volty.domain.audio.MAX_ALERT_GAIN_DB

/** Which Android audio usage the alert is played with. */
enum class AlertUsage { NAVIGATION_GUIDANCE, ALARM }

enum class AlertFocus { NONE, TRANSIENT_MAY_DUCK }

/**
 * Everything the platform needs to open an alert track and its focus request.
 * One value feeds both, so the track's attributes and the focus request's
 * attributes cannot drift apart.
 */
data class AlertAudioPlan(
    val usage: AlertUsage,
    val focus: AlertFocus,
    /** Target gain for a `LoudnessEnhancer`; 0 means "do not attach one". */
    val loudnessMillibels: Int,
)

fun alertAudioPlan(settings: AlertSoundSettings, music: AlarmMusicMode): AlertAudioPlan = AlertAudioPlan(
    usage = when (settings.kind) {
        AlertSoundKind.NAVIGATION -> AlertUsage.NAVIGATION_GUIDANCE
        AlertSoundKind.ALARM -> AlertUsage.ALARM
    },
    focus = when (music) {
        AlarmMusicMode.DUCK_MEDIA -> AlertFocus.TRANSIENT_MAY_DUCK
        AlarmMusicMode.PLAY_OVER_MEDIA -> AlertFocus.NONE
    },
    // An alert is never made quieter than the system volume makes it.
    loudnessMillibels = (settings.gainDb.coerceIn(0f, MAX_ALERT_GAIN_DB) * 100f).toInt(),
)
