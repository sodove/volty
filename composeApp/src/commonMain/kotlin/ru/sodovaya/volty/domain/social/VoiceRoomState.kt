package ru.sodovaya.volty.domain.social

import kotlinx.serialization.Serializable

@Serializable
sealed interface VoiceRoomState {
    @Serializable
    data object Unavailable : VoiceRoomState

    @Serializable
    data object Available : VoiceRoomState

    @Serializable
    data object Joining : VoiceRoomState

    @Serializable
    data class Joined(
        val muted: Boolean = false,
        val participants: List<VoiceParticipant> = emptyList(),
    ) : VoiceRoomState

    @Serializable
    data class Failed(
        val reason: VoiceRoomFailureReason,
    ) : VoiceRoomState
}

@Serializable
enum class VoiceRoomFailureReason {
    MICROPHONE_PERMISSION_DENIED,
    CONNECTION_FAILED,
}

sealed interface VoiceRoomEvent {
    data object JoinRequested : VoiceRoomEvent
    data object JoinSucceeded : VoiceRoomEvent
    data class JoinFailed(val reason: VoiceRoomFailureReason) : VoiceRoomEvent
    data class SetMuted(val muted: Boolean) : VoiceRoomEvent
    data class ParticipantsChanged(val participants: List<VoiceParticipant>) : VoiceRoomEvent
    data object LeaveRequested : VoiceRoomEvent
}

fun VoiceRoomState.reduce(event: VoiceRoomEvent): VoiceRoomState = when (this) {
    VoiceRoomState.Unavailable -> this
    VoiceRoomState.Available -> when (event) {
        VoiceRoomEvent.JoinRequested -> VoiceRoomState.Joining
        else -> this
    }
    VoiceRoomState.Joining -> when (event) {
        VoiceRoomEvent.JoinSucceeded -> VoiceRoomState.Joined(muted = false)
        is VoiceRoomEvent.JoinFailed -> VoiceRoomState.Failed(event.reason)
        VoiceRoomEvent.LeaveRequested -> VoiceRoomState.Available
        else -> this
    }
    is VoiceRoomState.Joined -> when (event) {
        is VoiceRoomEvent.SetMuted -> copy(muted = event.muted)
        is VoiceRoomEvent.ParticipantsChanged -> copy(participants = event.participants)
        VoiceRoomEvent.LeaveRequested -> VoiceRoomState.Available
        else -> this
    }
    is VoiceRoomState.Failed -> when (event) {
        VoiceRoomEvent.JoinRequested -> VoiceRoomState.Joining
        VoiceRoomEvent.LeaveRequested -> VoiceRoomState.Available
        else -> this
    }
}
