package ru.sodovaya.volty.presentation.root.nearby

import ru.sodovaya.volty.domain.radio.RadioClientPhase
import ru.sodovaya.volty.domain.radio.RadioError
import ru.sodovaya.volty.domain.radio.RadioMember
import ru.sodovaya.volty.domain.radio.RadioMode
import ru.sodovaya.volty.domain.radio.RadioState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NearbyRadioPanelStateTest {
    private val c = "community-1"
    private fun member(slot: Int, name: String) = RadioMember(slot, "u$slot", name, 0, null)
    private val roster = listOf(member(0, "Anna"), member(1, "Boris"))

    private fun panel(state: RadioState, roster: List<RadioMember> = emptyList(), supported: Boolean = true, community: String? = c) =
        radioPanelState(state, roster, supported, community)

    @Test
    fun idleSupportedOffersRaiseAndJoin() {
        val p = panel(RadioState.Idle)
        assertEquals(RadioTitle.IDLE, p.title)
        assertEquals(RadioAction.RAISE, p.primary)
        assertEquals(RadioAction.JOIN, p.secondary)
        assertNull(p.error)
    }

    @Test
    fun unsupportedShowsErrorAndNoActions() {
        val p = panel(RadioState.Idle, supported = false)
        assertEquals(RadioTitle.UNSUPPORTED, p.title)
        assertEquals(RadioError.UNSUPPORTED_ANDROID, p.error)
        assertNull(p.primary)
        assertNull(p.secondary)
        assertEquals(RadioModePicker.HIDDEN, p.modePicker)
        // Even a stale active state cannot offer actions on an unsupported device.
        assertNull(panel(RadioState.Hub(c), roster, supported = false).primary)
    }

    @Test
    fun lostOffersReraise() {
        val p = panel(RadioState.Lost(c, 100))
        assertEquals(RadioTitle.LOST, p.title)
        assertEquals(RadioAction.RERAISE, p.primary)
        assertEquals(RadioAction.LEAVE, p.secondary)
    }

    @Test
    fun hubShowsLeaveAndMembers() {
        val p = panel(RadioState.Hub(c), roster)
        assertEquals(RadioTitle.MEMBERS, p.title)
        assertEquals(RadioAction.LEAVE, p.primary)
        assertNull(p.secondary)
        assertEquals(listOf("Anna", "Boris"), p.members)
    }

    @Test
    fun clientShowsLeaveAndMembers() {
        val p = panel(RadioState.Client(c, RadioClientPhase.CONNECTED), roster)
        assertEquals(RadioTitle.MEMBERS, p.title)
        assertEquals(RadioAction.LEAVE, p.primary)
        assertEquals(listOf("Anna", "Boris"), p.members)
        assertEquals(RadioTitle.CONNECTING, panel(RadioState.Client(c, RadioClientPhase.JOINING)).title)
    }

    @Test
    fun startingCanBeCancelled() {
        val p = panel(RadioState.Starting(c))
        assertEquals(RadioTitle.STARTING, p.title)
        assertEquals(RadioAction.LEAVE, p.primary)
        assertNull(p.secondary)
    }

    @Test
    fun fullShowsErrorAndJoin() {
        val p = panel(RadioState.Error(c, RadioError.FULL))
        assertEquals(RadioTitle.ERROR, p.title)
        assertEquals(RadioError.FULL, p.error)
        assertEquals(RadioAction.JOIN, p.primary)
    }

    @Test
    fun notFoundOffersJoinAgainAndRaise() {
        val p = panel(RadioState.Error(c, RadioError.NOT_FOUND))
        assertEquals(RadioAction.JOIN, p.primary)
        assertEquals(RadioAction.RAISE, p.secondary)
        assertEquals(RadioAction.JOIN, panel(RadioState.Error(c, RadioError.STALE_KEY)).primary)
    }

    @Test
    fun audioUnavailableShowsRetry() {
        for (reason in listOf(RadioError.AUDIO_UNAVAILABLE, RadioError.WIFI_FAILED)) {
            val p = panel(RadioState.Error(c, reason))
            assertEquals(reason, p.error)
            assertEquals(RadioAction.RAISE, p.primary, reason.name) // raise = look for a radio, else start one
        }
    }

    @Test
    fun noKeyOffersNoActionAndUnsupportedErrorOffersNone() {
        val noKey = panel(RadioState.Error(c, RadioError.NO_KEY))
        assertEquals(RadioError.NO_KEY, noKey.error)
        assertNull(noKey.primary)
        assertNull(noKey.secondary)
        assertNull(panel(RadioState.Error(c, RadioError.UNSUPPORTED_ANDROID)).primary)
    }

    @Test
    fun modePickerOnlyForHub() {
        // Before raising and as hub: editable. As client or while lost: read-only. Elsewhere: hidden.
        assertEquals(RadioModePicker.EDITABLE, panel(RadioState.Idle).modePicker)
        assertEquals(RadioModePicker.EDITABLE, panel(RadioState.Hub(c), roster).modePicker)
        assertEquals(RadioModePicker.EDITABLE, panel(RadioState.Error(c, RadioError.NOT_FOUND)).modePicker)
        assertEquals(RadioModePicker.READ_ONLY, panel(RadioState.Client(c, RadioClientPhase.CONNECTED), roster).modePicker)
        assertEquals(RadioModePicker.READ_ONLY, panel(RadioState.Client(c, RadioClientPhase.JOINING)).modePicker)
        assertEquals(RadioModePicker.HIDDEN, panel(RadioState.Lost(c, 1)).modePicker)
        assertEquals(RadioModePicker.HIDDEN, panel(RadioState.Starting(c)).modePicker)
    }

    @Test
    fun radioOfAnotherServerIsNotThisServersRadio() {
        val p = panel(RadioState.Hub("other"), roster)
        assertEquals(RadioTitle.ELSEWHERE, p.title)
        assertEquals(RadioAction.LEAVE, p.primary)
        assertEquals(emptyList(), p.members)
        assertEquals(RadioModePicker.HIDDEN, p.modePicker)
        // An old error of another server is not shown here; this server can start its own radio.
        val old = panel(RadioState.Error("other", RadioError.FULL))
        assertEquals(RadioTitle.IDLE, old.title)
        assertNull(old.error)
        assertEquals(RadioAction.RAISE, old.primary)
    }

    @Test
    fun diagnosticsExportWheneverASnapshotExists() {
        fun can(state: RadioState, has: Boolean, supported: Boolean = true) =
            radioPanelState(state, roster, supported, c, hasDiagnostics = has).canExportDiagnostics
        assertEquals(false, can(RadioState.Idle, has = false))
        assertEquals(true, can(RadioState.Idle, has = true)) // after a run ended
        assertEquals(true, can(RadioState.Error(c, RadioError.NOT_FOUND), has = true))
        assertEquals(true, can(RadioState.Hub(c), has = true))
        assertEquals(false, can(RadioState.Hub(c), has = false))
        assertEquals(false, can(RadioState.Idle, has = true, supported = false))
    }

    @Test
    fun hubWithEmptyRosterShowsStarting() {
        val p = panel(RadioState.Hub(c), emptyList())
        assertEquals(RadioTitle.STARTING, p.title)
        assertEquals(emptyList(), p.members)
        assertEquals(RadioAction.LEAVE, p.primary)
        assertEquals(RadioTitle.STARTING, radioStatusTitle(p))
    }

    @Test
    fun connectedClientWithEmptyRosterShowsConnecting() {
        val p = panel(RadioState.Client(c, RadioClientPhase.CONNECTED), emptyList())
        assertEquals(RadioTitle.CONNECTING, p.title)
        assertEquals(emptyList(), p.members)
        assertEquals(RadioTitle.CONNECTING, radioStatusTitle(p))
    }

    @Test
    fun joiningClientShowsNoMode() {
        // The rider's own saved mode must not pose as the hub's.
        val joining = radioPanelState(RadioState.Client(c, RadioClientPhase.JOINING), emptyList(), true, c, mode = RadioMode.RELIABLE)
        assertNull(joining.shownMode)
        val heard = radioPanelState(RadioState.Client(c, RadioClientPhase.CONNECTED), roster, true, c, mode = RadioMode.LOW_LATENCY)
        assertEquals(RadioMode.LOW_LATENCY, heard.shownMode)
        // Raising or idle shows the rider's own choice.
        assertEquals(RadioMode.RELIABLE, radioPanelState(RadioState.Idle, emptyList(), true, c, mode = RadioMode.RELIABLE).shownMode)
    }

    @Test
    fun statusLineOnlyWhileARadioIsStartingRunningOrLost() {
        fun line(state: RadioState) = radioStatusTitle(panel(state, roster, community = null))
        assertNull(line(RadioState.Idle))
        assertNull(line(RadioState.Error(c, RadioError.FULL)))
        assertEquals(RadioTitle.STARTING, line(RadioState.Starting(c)))
        assertEquals(RadioTitle.MEMBERS, line(RadioState.Hub(c)))
        assertEquals(RadioTitle.STARTING, radioStatusTitle(panel(RadioState.Hub(c), emptyList(), community = null)))
        assertEquals(RadioTitle.LOST, line(RadioState.Lost(c, 1)))
        assertNull(radioStatusTitle(panel(RadioState.Hub(c), roster, supported = false)))
    }

    @Test
    fun notificationSaysWhatThePanelSays() {
        // I5: the foreground notification never counts people the radio has not heard.
        assertEquals(RadioTitle.STARTING, radioNotificationTitle(RadioState.Starting(c), emptyList()))
        assertEquals(RadioTitle.STARTING, radioNotificationTitle(RadioState.Hub(c), emptyList()))
        assertEquals(RadioTitle.CONNECTING, radioNotificationTitle(RadioState.Client(c, RadioClientPhase.JOINING), emptyList()))
        // A stale roster must not turn a connecting client into "N people".
        assertEquals(RadioTitle.CONNECTING, radioNotificationTitle(RadioState.Client(c, RadioClientPhase.JOINING), roster))
        assertEquals(RadioTitle.LOST, radioNotificationTitle(RadioState.Lost(c, 1), roster))
        assertEquals(RadioTitle.MEMBERS, radioNotificationTitle(RadioState.Hub(c), roster))
        assertEquals(RadioTitle.MEMBERS, radioNotificationTitle(RadioState.Client(c, RadioClientPhase.CONNECTED), roster))
        // Idle/Error for the few ms before the service stops: never a member count.
        assertEquals(RadioTitle.STARTING, radioNotificationTitle(RadioState.Idle, roster))
        assertEquals(RadioTitle.STARTING, radioNotificationTitle(RadioState.Error(c, RadioError.FULL), roster))
    }
}
