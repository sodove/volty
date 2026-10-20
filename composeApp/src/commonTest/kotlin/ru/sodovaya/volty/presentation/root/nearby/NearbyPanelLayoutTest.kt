package ru.sodovaya.volty.presentation.root.nearby

import kotlin.test.*
import ru.sodovaya.volty.domain.voice.*

class NearbyPanelLayoutTest {
    private val operation = VoiceOperationId("original")
    private val trip = NearbyTripAction(operation, "generation")

    @Test fun positionRowHiddenWithoutAnyPositionAction() {
        assertEquals(NearbyPositionRow(visible = false, checked = false, enabled = false),
            nearbyPositionRow(NearbyConversationPanelView()))
    }

    @Test fun positionRowOffersStartWhenTripCanStart() {
        assertEquals(NearbyPositionRow(visible = true, checked = false, enabled = true),
            nearbyPositionRow(NearbyConversationPanelView(startTripOperation = operation)))
    }

    @Test fun activeTripIsCheckedAndCanBeTurnedOff() {
        assertEquals(NearbyPositionRow(visible = true, checked = true, enabled = true),
            nearbyPositionRow(NearbyConversationPanelView(tripActive = true, hideTrip = trip)))
    }

    @Test fun preparingTripIsCheckedAndCancellable() {
        assertEquals(NearbyPositionRow(visible = true, checked = true, enabled = true),
            nearbyPositionRow(NearbyConversationPanelView(tripPreparing = true, cancelTripRequestId = "id")))
    }

    // Deliberately incoherent: a trip shown active with no way to stop it must not offer a dead switch.
    @Test fun activeTripWithoutStopActionIsDisabled() {
        assertEquals(NearbyPositionRow(visible = true, checked = true, enabled = false),
            nearbyPositionRow(NearbyConversationPanelView(tripActive = true, startTripOperation = operation)))
    }

    @Test fun noticeIsNoneWhenNothingIsWaiting() {
        assertEquals(NearbyPanelNotice.NONE, nearbyPanelNotice(NearbyConversationPanelView()))
    }

    @Test fun cleanupNoticesWinOverEverythingElse() {
        val busy = NearbyConversationPanelView(tripPreparing = true, localCleanupPending = true, serverConfirmationPending = true)
        assertEquals(NearbyPanelNotice.LOCAL_CLEANUP, nearbyPanelNotice(busy))
        assertEquals(NearbyPanelNotice.SERVER_CLEANUP, nearbyPanelNotice(busy.copy(localCleanupPending = false)))
    }

    @Test fun preparingPositionThenAudioChange() {
        val session = VoiceSessionId("session")
        val audio = ru.sodovaya.volty.data.voice.VoiceConversationAudioState(operation, VoiceSessionTag(session, VoiceGeneration(1)),
            VoiceAudioPreferences(), pending = VoiceAudioPreferences(output = VoiceOutputPreference.SPEAKER))
        assertEquals(NearbyPanelNotice.PREPARING_POSITION,
            nearbyPanelNotice(NearbyConversationPanelView(tripPreparing = true, audio = audio)))
        assertEquals(NearbyPanelNotice.AUDIO_CHANGING, nearbyPanelNotice(NearbyConversationPanelView(audio = audio)))
        assertEquals(NearbyPanelNotice.NONE, nearbyPanelNotice(NearbyConversationPanelView(audio = audio.copy(pending = null))))
    }
}
