package ru.sodovaya.volty.backend.nearby

data class ChannelSharingPacketAcknowledgement(val sessionId: String, val generation: String,
    val sequence: Long, val remainingMillis: Long)

interface ChannelSharingPacketEndpointPort {
    fun publish(actor: String, session: String, generation: String, packet: ChannelSharingPacket): ChannelSharingPacketAcknowledgement?
    fun audience(actor: String, session: String): ChannelSharingAudience?
    /** Opt-in map view may retain last-known coordinates under current consent. */
    fun mapAudience(actor: String, session: String): ChannelSharingAudience? = audience(actor, session)
}
