package ru.sodovaya.volty.backend.nearby

import java.sql.Connection

/** Typed voice access, evaluated on the owning session transaction. */
class JdbcVoiceAccess(private val now: () -> Long = System::currentTimeMillis) : SessionAccessPolicy {
    override fun allows(connection: Connection, actor: String, destination: String, roomEpoch: String?): Boolean =
        JdbcChannelAccess().allows(connection, actor, destination, roomEpoch)

    override fun allows(connection: Connection, actor: String, destination: String, roomEpoch: String?, kind: VoiceDestinationKind): Boolean {
        if (kind == VoiceDestinationKind.CHANNEL) return allows(connection, actor, destination, roomEpoch)
        if (roomEpoch != "volty-call-$destination") return false
        return connection.prepareStatement("""
            SELECT d.call_id FROM nearby_direct_calls d
            JOIN users a ON a.id=d.caller_id AND a.deleted_at IS NULL
            JOIN users b ON b.id=d.callee_id AND b.deleted_at IS NULL
            JOIN friendships f ON LEAST(f.requester_id,f.addressee_id)=LEAST(a.id,b.id)
                AND GREATEST(f.requester_id,f.addressee_id)=GREATEST(a.id,b.id) AND f.status='ACCEPTED'
            WHERE d.call_id=?::uuid AND d.phase='ACCEPTED' AND d.ever_rang
                AND ?::uuid IN (d.caller_id,d.callee_id)
                AND (SELECT COUNT(*) FROM nearby_session_operations o JOIN nearby_actor_claims c
                    ON c.actor_id=o.actor_id AND c.operation_id=o.operation_id AND c.session_id=o.session_id
                    AND c.room_epoch=o.room_epoch AND c.node_epoch=o.node_epoch
                    WHERE o.destination_kind='DIRECT_CALL' AND o.destination_id=d.call_id
                    AND o.actor_id IN (d.caller_id,d.callee_id)
                    AND (o.actor_id<>d.caller_id OR o.operation_id=d.caller_operation_id)
                    AND o.phase IN ('PENDING','ACTIVE') AND o.deadline_epoch_millis>?
                    AND o.room_epoch=? AND o.node_epoch IS NOT NULL)=2
                AND (SELECT COUNT(DISTINCT node_epoch) FROM nearby_session_operations
                    WHERE destination_kind='DIRECT_CALL' AND destination_id=d.call_id)=1
            FOR SHARE OF f
        """.trimIndent()).use {
            it.setString(1, destination); it.setString(2, actor); it.setLong(3, now()); it.setString(4, roomEpoch)
            it.executeQuery().use {r -> r.next()}
        }
    }
}
