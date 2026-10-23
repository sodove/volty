package ru.sodovaya.volty.domain.alert

import ru.sodovaya.volty.domain.audio.AlertSoundKind
import ru.sodovaya.volty.domain.audio.AlertSoundSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class AlertAudioPlanTest {

    @Test
    fun navigationDucksByDefault() {
        val plan = alertAudioPlan(AlertSoundSettings(), AlarmMusicMode.DUCK_MEDIA)
        assertEquals(AlertAudioPlan(AlertUsage.NAVIGATION_GUIDANCE, AlertFocus.TRANSIENT_MAY_DUCK, 0), plan)
    }

    @Test
    fun overMediaRequestsNoFocusInEitherKind() {
        for (kind in AlertSoundKind.entries) {
            val plan = alertAudioPlan(AlertSoundSettings(kind = kind), AlarmMusicMode.PLAY_OVER_MEDIA)
            assertEquals(AlertFocus.NONE, plan.focus, "kind=$kind")
        }
    }

    @Test
    fun alarmKindKeepsTheOldAttributes() {
        val plan = alertAudioPlan(AlertSoundSettings(kind = AlertSoundKind.ALARM), AlarmMusicMode.DUCK_MEDIA)
        assertEquals(AlertUsage.ALARM, plan.usage)
        assertEquals(AlertFocus.TRANSIENT_MAY_DUCK, plan.focus)
    }

    @Test
    fun gainBecomesMillibelsAndIsCapped() {
        fun mb(db: Float) = alertAudioPlan(AlertSoundSettings(gainDb = db), AlarmMusicMode.DUCK_MEDIA).loudnessMillibels
        assertEquals(600, mb(6f))
        assertEquals(1200, mb(40f))
        assertEquals(0, mb(-3f))
    }

    @Test
    fun aChangedSoundRestartsARunningAlarmInsteadOfRetuning() {
        val base = alarmCommandFor(1, 0f, AlarmModalities())
        val louder = alarmCommandFor(
            1, 0f, AlarmModalities(sound = AlertSoundSettings(gainDb = 6f))
        )
        assertNotEquals(base, louder)
        assertEquals(AlarmTransition.RESTART, alarmTransitionFor(base, louder))
    }
}
