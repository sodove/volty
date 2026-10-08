package ru.sodovaya.volty.backend.nearby

import java.security.MessageDigest
import java.sql.Connection
import java.sql.SQLException
import java.util.HexFormat
import javax.sql.DataSource

/** Repository boundary only; routes and delivery are deliberately not wired yet. */
class JdbcPushDeviceRegistry(private val source: DataSource) {
    fun apply(registration: PushDeviceRegistration, nowMillis: Long): PushDeviceDecision {
        val r = registration
        if (!validPushRegistration(r, nowMillis)) return PushDeviceDecision.INVALID
        return persisted(write = true) { c ->
            if (!activeAuthority(c, r)) return@persisted PushDeviceDecision.ACCESS_DENIED
            val keyHash = hash(r.installationKey)
            val tokenHash = r.token?.let(::hash)
            val old = stored(c, r.installationId)
            if (old != null) {
                if (!MessageDigest.isEqual(old.keyHash.toByteArray(Charsets.US_ASCII), keyHash.toByteArray(Charsets.US_ASCII)))
                    return@persisted PushDeviceDecision.ACCESS_DENIED
                if (r.generation < old.generation) return@persisted PushDeviceDecision.STALE
                if (r.generation == old.generation) {
                    return@persisted if (r.actorId == old.actor && r.bindingId == old.binding &&
                        r.authIssuedAtSeconds == old.issued && tokenHash == old.tokenHash) PushDeviceDecision.REPLAY
                    else PushDeviceDecision.STALE
                }
                // A logout may advance its own fence, never another account's binding.
                if (r.token == null && r.actorId != old.actor) return@persisted PushDeviceDecision.ACCESS_DENIED
            }
            if (tokenHash != null && tokenConflict(c, r.installationId, tokenHash))
                return@persisted PushDeviceDecision.TOKEN_CONFLICT
            c.prepareStatement("""
                INSERT INTO nearby_push_devices(installation_id,installation_key_hash,generation,binding_id,
                    actor_id,auth_issued_at_seconds,token,token_hash,updated_at_millis)
                VALUES (?::uuid,?,?,?::uuid,?::uuid,?,?,?,?)
                ON CONFLICT (installation_id) DO UPDATE SET generation=EXCLUDED.generation,
                    binding_id=EXCLUDED.binding_id,actor_id=EXCLUDED.actor_id,
                    auth_issued_at_seconds=EXCLUDED.auth_issued_at_seconds,token=EXCLUDED.token,
                    token_hash=EXCLUDED.token_hash,updated_at_millis=EXCLUDED.updated_at_millis
            """.trimIndent()).use {
                it.setString(1, r.installationId); it.setString(2, keyHash); it.setLong(3, r.generation)
                it.setString(4, r.bindingId); it.setString(5, r.actorId); it.setLong(6, r.authIssuedAtSeconds)
                it.setString(7, r.token); it.setString(8, tokenHash); it.setLong(9, nowMillis)
                check(it.executeUpdate() == 1)
            }
            PushDeviceDecision.APPLIED
        }
    }

    /** Internal delivery enumeration, never an HTTP response. Rechecks current account authority. */
    internal fun targets(actorId: String, limit: Int = 100, afterInstallationId: String? = null): List<PushDeviceTarget> {
        require(pushDeviceCanonicalId(actorId) && limit in 1..100 && (afterInstallationId == null || pushDeviceCanonicalId(afterInstallationId)))
        return persisted(write = false) { c ->
            c.prepareStatement("""
                SELECT d.* FROM nearby_push_devices d JOIN users u ON u.id=d.actor_id
                WHERE d.actor_id=?::uuid AND d.token IS NOT NULL AND u.deleted_at IS NULL
                    AND u.tokens_revoked_at<=d.auth_issued_at_seconds
                    AND (?::uuid IS NULL OR d.installation_id>?::uuid)
                ORDER BY d.installation_id LIMIT ?
            """.trimIndent()).use {
                it.setString(1, actorId); it.setString(2, afterInstallationId); it.setString(3, afterInstallationId); it.setInt(4, limit)
                it.executeQuery().use { rows -> buildList {
                    while (rows.next()) add(PushDeviceTarget(rows.getString("actor_id"), rows.getString("installation_id"),
                        rows.getLong("generation"), rows.getString("binding_id"), rows.getString("token"), rows.getString("token_hash")))
                } }
            }
        }
    }

    /** Conditional invalid-token acknowledgement; never retire a replacement binding. */
    internal fun retire(target: PushDeviceTarget, nowMillis: Long): Boolean {
        if (nowMillis < 0) return false
        return persisted(write = true) { c ->
            c.prepareStatement("""
                UPDATE nearby_push_devices SET token=NULL,token_hash=NULL,updated_at_millis=?
                WHERE installation_id=?::uuid AND actor_id=?::uuid AND generation=?
                    AND binding_id=?::uuid AND token_hash=?
            """.trimIndent()).use {
                it.setLong(1, nowMillis); it.setString(2, target.installationId); it.setString(3, target.actorId)
                it.setLong(4, target.generation); it.setString(5, target.bindingId); it.setString(6, target.tokenHash)
                it.executeUpdate() == 1
            }
        }
    }

    private fun <T> persisted(write: Boolean, block: (Connection) -> T): T = try {
        communityTransaction(source, write, block)
    } catch (failure: SQLException) {
        // PostgreSQL errors can include the failing row's raw token. StatusPages
        // logs exception chains, so retain only non-secret diagnostic codes.
        throw SQLException("Push device storage unavailable", failure.sqlState, failure.errorCode)
    }

    private fun activeAuthority(c: Connection, r: PushDeviceRegistration): Boolean = c.prepareStatement("""
        SELECT 1 FROM users WHERE id=?::uuid AND deleted_at IS NULL AND tokens_revoked_at<=? FOR SHARE
    """.trimIndent()).use {
        it.setString(1, r.actorId); it.setLong(2, r.authIssuedAtSeconds); it.executeQuery().use { rows -> rows.next() }
    }

    private fun stored(c: Connection, installation: String): Stored? = c.prepareStatement("SELECT * FROM nearby_push_devices WHERE installation_id=?::uuid FOR UPDATE").use {
        it.setString(1, installation); it.executeQuery().use { rows ->
            if (!rows.next()) null else Stored(rows.getString("installation_key_hash"), rows.getLong("generation"),
                rows.getString("binding_id"), rows.getString("actor_id"), rows.getLong("auth_issued_at_seconds"), rows.getString("token_hash"))
        }
    }
    private fun tokenConflict(c: Connection, installation: String, tokenHash: String): Boolean = c.prepareStatement(
        "SELECT 1 FROM nearby_push_devices WHERE token_hash=? AND installation_id<>?::uuid"
    ).use { it.setString(1, tokenHash); it.setString(2, installation); it.executeQuery().use { rows -> rows.next() } }

    private class Stored(val keyHash: String, val generation: Long, val binding: String, val actor: String?, val issued: Long, val tokenHash: String?)
    private fun hash(value: String): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)))
}
