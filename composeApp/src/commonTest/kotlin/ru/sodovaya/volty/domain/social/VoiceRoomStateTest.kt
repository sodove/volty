package ru.sodovaya.volty.domain.social

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VoiceRoomStateTest {
    @Test
    fun joiningAnAvailableRoomStartsProviderJoin() {
        assertEquals(
            VoiceRoomState.Joining,
            VoiceRoomState.Available.reduce(VoiceRoomEvent.JoinRequested),
        )
    }

    @Test
    fun successfulJoinIsOpenMicByDefault() {
        val joined = VoiceRoomState.Joining.reduce(
            VoiceRoomEvent.JoinSucceeded,
        ) as VoiceRoomState.Joined

        assertFalse(joined.muted)
    }

    @Test
    fun muteIsAnExplicitReversibleTransitionWithoutPushToTalk() {
        val joined = VoiceRoomState.Joined(muted = false)
        val muted = joined.reduce(VoiceRoomEvent.SetMuted(true)) as VoiceRoomState.Joined
        val unmuted = muted.reduce(VoiceRoomEvent.SetMuted(false)) as VoiceRoomState.Joined

        assertTrue(muted.muted)
        assertFalse(unmuted.muted)
    }

    @Test
    fun leavingJoinedRoomReturnsToAvailableAndDropsMediaState() {
        val next = VoiceRoomState.Joined(
            muted = false,
            participants = listOf(
                VoiceParticipant(SocialUserId("user-2"), "Passenger", isSpeaking = true),
            ),
        ).reduce(VoiceRoomEvent.LeaveRequested)

        assertEquals(VoiceRoomState.Available, next)
    }

    @Test
    fun unavailableProviderCannotBeJoined() {
        assertEquals(
            VoiceRoomState.Unavailable,
            VoiceRoomState.Unavailable.reduce(VoiceRoomEvent.JoinRequested),
        )
    }

    @Test
    fun providerFailureIsTypedStateAndCanBeRetried() {
        val failed = VoiceRoomState.Joining.reduce(
            VoiceRoomEvent.JoinFailed(VoiceRoomFailureReason.CONNECTION_FAILED),
        ) as VoiceRoomState.Failed

        assertEquals(VoiceRoomFailureReason.CONNECTION_FAILED, failed.reason)
        assertEquals(VoiceRoomState.Joining, failed.reduce(VoiceRoomEvent.JoinRequested))
    }
}
