package ru.sodovaya.volty.backend.nearby

enum class SfuParticipantState { JOINING, JOINED, ACTIVE, DISCONNECTED }

/** Current SFU transport observation only; ACTIVE cannot establish device audibility. */
data class SfuParticipantObservation(val participantSid: String?, val state: SfuParticipantState?)

interface SfuParticipantReader {
    fun observeParticipant(room: String, session: String, expectedNodeEpoch: String): SfuReply<SfuParticipantObservation>
}
