package ru.sodovaya.volty.backend.nearby

import ru.sodovaya.volty.backend.LIVE_LOCATION_FRESHNESS_MILLIS

internal class JdbcChannelSharingPacketEndpoint(private val packets: JdbcChannelSharingPackets,
    private val now: () -> Long) : ChannelSharingPacketEndpointPort {
    override fun publish(actor: String, session: String, generation: String, packet: ChannelSharingPacket): ChannelSharingPacketAcknowledgement? {
        val stored = packets.publish(actor, session, generation, packet, now) ?: return null
        val at = now(); require(at >= 0)
        if (at < stored.packet.capturedAtEpochMillis || stored.deadlineEpochMillis <= at) return null
        val remaining = stored.deadlineEpochMillis - at
        require(remaining in 1..LIVE_LOCATION_FRESHNESS_MILLIS)
        return ChannelSharingPacketAcknowledgement(session, generation, stored.packet.sequence, remaining)
    }
    override fun audience(actor: String, session: String) = packets.snapshot(actor, session, now)
}
