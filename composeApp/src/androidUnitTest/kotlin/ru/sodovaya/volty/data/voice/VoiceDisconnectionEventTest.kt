package ru.sodovaya.volty.data.voice

import io.livekit.android.events.DisconnectReason
import ru.sodovaya.volty.domain.voice.*
import kotlin.test.*

class VoiceDisconnectionEventTest {
    @Test fun explicit_room_closure_is_normal_and_all_transport_failures_remain_failures() {
        val tag = VoiceSessionTag(VoiceSessionId("owned-session"), VoiceGeneration(4))
        val closed = setOf(DisconnectReason.CLIENT_INITIATED, DisconnectReason.PARTICIPANT_REMOVED,
            DisconnectReason.ROOM_DELETED, DisconnectReason.ROOM_CLOSED)
        for (reason in DisconnectReason.entries) {
            val event = voiceDisconnectionEvent(tag, reason)
            assertEquals(tag, event.tag)
            if (reason in closed) assertIs<VoiceSessionEvent.EngineClosed>(event, reason.name)
            else assertIs<VoiceSessionEvent.EngineFailed>(event, reason.name)
        }
    }
}
