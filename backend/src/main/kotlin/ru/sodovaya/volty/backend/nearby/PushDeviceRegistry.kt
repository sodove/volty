package ru.sodovaya.volty.backend.nearby

import java.util.UUID
import java.sql.Connection
import java.sql.SQLException
import javax.sql.DataSource

/** Installation capability and FCM token must never appear in logs or responses. */
class PushDeviceRegistration(
    val actorId: String, val authIssuedAtSeconds: Long,
    val installationId: String, val installationKey: String,
    val generation: Long, val bindingId: String, val token: String?,
) {
    override fun toString() = "PushDeviceRegistration(<redacted>)"
}

enum class PushDeviceDecision { APPLIED, REPLAY, STALE, ACCESS_DENIED, TOKEN_CONFLICT, INVALID }

internal class PushDeviceTarget(
    val actorId: String, val installationId: String, val generation: Long,
    val bindingId: String, val token: String, internal val tokenHash: String,
) {
    override fun toString() = "PushDeviceTarget(<redacted>)"
}

fun interface PushDeviceEndpointPort { fun apply(registration: PushDeviceRegistration): PushDeviceDecision }

internal fun validPushRegistration(r: PushDeviceRegistration, at: Long): Boolean = at >= 0 && r.authIssuedAtSeconds >= 0 && r.generation > 0 &&
    pushDeviceCanonicalId(r.actorId) && pushDeviceCanonicalId(r.installationId) && pushDeviceCanonicalId(r.bindingId) &&
    r.installationKey.matches(Regex("[0-9a-f]{64}")) &&
    (r.token == null || (r.token.length in 1..4096 && r.token.all { it.code in 33..126 }))

internal fun pushDeviceCanonicalId(value: String): Boolean = runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)

internal fun <T> pushStorageTransaction(source: DataSource, write: Boolean, block: (Connection) -> T): T = try {
    communityTransaction(source, write, block)
} catch (failure: SQLException) {
    // PostgreSQL failing-row details can contain capabilities; never log the chain.
    throw SQLException("Push storage unavailable", failure.sqlState, failure.errorCode)
}

internal fun retirePushDevice(c: Connection, target: PushDeviceTarget, at: Long): Boolean {
    check(!c.autoCommit)
    return c.prepareStatement("""
        UPDATE nearby_push_devices SET token=NULL,token_hash=NULL,updated_at_millis=?
        WHERE installation_id=?::uuid AND actor_id=?::uuid AND generation=? AND binding_id=?::uuid AND token_hash=?
    """.trimIndent()).use {
        it.setLong(1, at); it.setString(2, target.installationId); it.setString(3, target.actorId)
        it.setLong(4, target.generation); it.setString(5, target.bindingId); it.setString(6, target.tokenHash)
        it.executeUpdate() == 1
    }
}
