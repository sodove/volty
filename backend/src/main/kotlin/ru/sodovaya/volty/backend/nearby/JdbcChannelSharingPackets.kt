package ru.sodovaya.volty.backend.nearby

import java.sql.Connection
import java.util.UUID
import kotlinx.serialization.encodeToString
import ru.sodovaya.volty.backend.backendJson
import ru.sodovaya.volty.backend.sha256
import ru.sodovaya.volty.backend.LIVE_LOCATION_FRESHNESS_MILLIS

internal class JdbcChannelSharingPackets(private val sessions: JdbcSessionRepository) {
    private val shares = JdbcChannelSharingRepository(sessions)
    private val json = backendJson()
    fun publish(actor: String, session: String, generation: String, packet: ChannelSharingPacket, now: () -> Long): StoredChannelSharingPacket? {
        validate(actor, session, generation)
        return sessions.withChannelSharingActor(actor) {c ->
            val at = time(now)
            val authority = shares.authority(c, actor, session, generation, at) ?: return@withChannelSharingActor null
            val safe = normalize(packet, checkNotNull(authority.consent.mode), authority.startedAt, at)
            val deadline = sampleDeadline(safe)
            val encoded = json.encodeToString(safe); val digest = sha256(encoded)
            val previous = read(c, actor, session, generation)
            if (previous != null) {
                if (safe.sequence < previous.sequence) return@withChannelSharingActor null
                if (safe.sequence == previous.sequence)
                    return@withChannelSharingActor StoredChannelSharingPacket(safe, previous.deadline).takeIf {previous.digest == digest}
            }
            c.prepareStatement("""
                INSERT INTO nearby_channel_sharing_packets(actor_id,session_id,generation_id,sequence,payload_hash,payload_json,captured_at,deadline_epoch_millis)
                VALUES (?::uuid,?::uuid,?::uuid,?,?,?,?,?)
                ON CONFLICT(actor_id,session_id,generation_id) DO UPDATE SET sequence=EXCLUDED.sequence,
                payload_hash=EXCLUDED.payload_hash,payload_json=EXCLUDED.payload_json,captured_at=EXCLUDED.captured_at,deadline_epoch_millis=EXCLUDED.deadline_epoch_millis
            """.trimIndent()).use {
                it.setString(1, actor); it.setString(2, session); it.setString(3, generation); it.setLong(4, safe.sequence)
                it.setString(5, digest); it.setString(6, encoded); it.setLong(7, safe.capturedAtEpochMillis); it.setLong(8, deadline); it.executeUpdate()
            }
            StoredChannelSharingPacket(safe, deadline)
        }
    }

    /** One audience query; peer rows are never reconciled while holding the viewer actor lock. */
    fun snapshot(actor: String, session: String, now: () -> Long): ChannelSharingAudience? {
        validate(actor, session)
        return sessions.withChannelSharingActor(actor) {c ->
            val at = time(now)
            val viewer = sessions.activeChannelSharingSession(c, actor, session, at) ?: return@withChannelSharingActor null
            val channel = checkNotNull(viewer.operation.destinationId)
            val peers = c.prepareStatement("""
                SELECT p.actor_id,p.session_id,p.generation_id,p.payload_json,p.sequence,p.captured_at,s.mode,s.started_at,
                    LEAST(o.deadline_epoch_millis,s.deadline_epoch_millis,p.deadline_epoch_millis,?) AS peer_deadline
                FROM nearby_channel_sharing_packets p
                JOIN nearby_channel_sharing_consent s ON s.actor_id=p.actor_id AND s.session_id=p.session_id AND s.generation_id=p.generation_id
                JOIN nearby_session_operations o ON o.actor_id=p.actor_id AND o.session_id=p.session_id
                JOIN nearby_actor_claims a ON a.actor_id=o.actor_id AND a.operation_id=o.operation_id AND a.session_id=o.session_id
                    AND a.room_epoch=o.room_epoch AND a.node_epoch=o.node_epoch
                JOIN users u ON u.id=o.actor_id AND u.deleted_at IS NULL
                JOIN nearby_voice_channels v ON v.id=o.destination_id AND v.state='ACTIVE'
                JOIN nearby_communities g ON g.id=v.community_id AND g.state='ACTIVE'
                JOIN nearby_community_members m ON m.community_id=g.id AND m.user_id=o.actor_id
                WHERE o.destination_kind='CHANNEL' AND o.destination_id=?::uuid AND o.phase='ACTIVE'
                    AND o.room_epoch=? AND o.deadline_epoch_millis>?
                    AND s.phase='ACTIVE' AND s.started_at IS NOT NULL AND s.deadline_epoch_millis>?
                    AND p.deadline_epoch_millis>? AND p.captured_at<=?
                    AND NOT EXISTS (SELECT 1 FROM nearby_community_bans b WHERE b.community_id=g.id AND b.user_id=o.actor_id)
                ORDER BY p.actor_id,p.session_id,p.generation_id
            """.trimIndent()).use {
                it.setLong(1, checkNotNull(viewer.operation.deadlineEpochMillis)); it.setString(2, channel)
                it.setString(3, checkNotNull(viewer.sfuEpoch).roomEpoch)
                for (index in 4..7) it.setLong(index, at)
                it.executeQuery().use {rows -> buildList {
                    while (rows.next()) {
                        // Stored input is also a contributor: don't infer complete data from the writer.
                        val packet = try {
                            val decoded = json.decodeFromString<ChannelSharingPacket>(rows.getString("payload_json"))
                            require(decoded.sequence == rows.getLong("sequence") && decoded.capturedAtEpochMillis == rows.getLong("captured_at"))
                            normalize(decoded, ChannelSharingMode.valueOf(rows.getString("mode")), rows.getLong("started_at"), at)
                        } catch (_: IllegalArgumentException) {continue}
                        val deadline = minOf(rows.getLong("peer_deadline"), sampleDeadline(packet))
                        if (deadline > at) add(ChannelSharingAudiencePeer(rows.getString("actor_id"), rows.getString("session_id"),
                            rows.getString("generation_id"), packet, deadline))
                    }
                }}
            }
            ChannelSharingAudience(channel, at, peers)
        }
    }

    private fun normalize(packet: ChannelSharingPacket, mode: ChannelSharingMode, started: Long, at: Long): ChannelSharingPacket {
        require(started >= 0 && packet.sequence >= 0 && packet.capturedAtEpochMillis in started..at)
        require(packet.capturedAtEpochMillis <= Long.MAX_VALUE - LIVE_LOCATION_FRESHNESS_MILLIS)
        require(at - packet.capturedAtEpochMillis < LIVE_LOCATION_FRESHNESS_MILLIS)
        packet.location?.let {
            require(it.latitude.isFinite() && it.latitude in -90.0..90.0 && it.longitude.isFinite() && it.longitude in -180.0..180.0)
            require(it.accuracyMeters.isFinite() && it.accuracyMeters >= 0)
            require(it.capturedAtEpochMillis in started..packet.capturedAtEpochMillis)
            require(it.staleAfterEpochMillis > at && it.staleAfterEpochMillis > it.capturedAtEpochMillis)
            require(it.staleAfterEpochMillis - it.capturedAtEpochMillis <= LIVE_LOCATION_FRESHNESS_MILLIS)
        }
        return packet.copy(telemetry = ChannelSharingTelemetryPolicy.sanitize(mode, packet.telemetry))
    }
    private fun sampleDeadline(packet: ChannelSharingPacket) = minOf(packet.capturedAtEpochMillis + LIVE_LOCATION_FRESHNESS_MILLIS,
        packet.location?.staleAfterEpochMillis ?: Long.MAX_VALUE)
    private data class Receipt(val sequence: Long, val deadline: Long, val digest: String)
    private fun read(c: Connection, actor: String, session: String, generation: String): Receipt? =
        c.prepareStatement("SELECT sequence,deadline_epoch_millis,payload_hash FROM nearby_channel_sharing_packets WHERE actor_id=?::uuid AND session_id=?::uuid AND generation_id=?::uuid").use {
            it.setString(1, actor); it.setString(2, session); it.setString(3, generation)
            it.executeQuery().use {rows -> if (rows.next()) Receipt(rows.getLong(1), rows.getLong(2), rows.getString(3)) else null}
        }
    private fun validate(vararg ids: String) {ids.forEach {require(UUID.fromString(it).toString() == it)}}
    private fun time(now: () -> Long) = now().also {require(it >= 0)}
}
