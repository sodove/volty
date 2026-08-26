package ru.sodovaya.volty.backend

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import java.sql.Connection
import java.sql.ResultSet
import java.time.Duration
import java.util.UUID
import javax.sql.DataSource

data class AppConfig(
    val databaseUrl: String,
    val databaseUser: String,
    val databasePassword: String,
    val jwtSecret: String,
    val accessTtlSeconds: Long = 15 * 60,
    val refreshTtlSeconds: Long = 30L * 24 * 60 * 60,
    val maxShareTtlMillis: Long = 24L * 60 * 60 * 1_000,
    val corsOrigins: Set<String> = setOf("https://volty.sodove.ru"),
    val voiceProvider: String = "unconfigured",
    val liveKitUrl: String? = null,
    val liveKitApiKey: String? = null,
    val liveKitApiSecret: String? = null,
    val voiceTokenTtlSeconds: Long = DEFAULT_VOICE_TOKEN_TTL_SECONDS,
    val publicIp: String? = null,
) {
    fun liveKitConfigOrNull(): LiveKitConfig? = if (voiceProvider == "livekit") {
        LiveKitConfig(
            serverUrl = checkNotNull(liveKitUrl),
            apiKey = checkNotNull(liveKitApiKey),
            apiSecret = checkNotNull(liveKitApiSecret),
            tokenTtlSeconds = voiceTokenTtlSeconds,
            publicIp = checkNotNull(publicIp),
        )
    } else {
        null
    }

    companion object {
        fun fromEnvironment(env: Map<String, String> = System.getenv()): AppConfig {
            val provider = env["VOLTY_VOICE_PROVIDER"]?.trim()?.lowercase()?.ifEmpty { "unconfigured" } ?: "unconfigured"
            require(provider in setOf("unconfigured", "livekit")) {
                "VOLTY_VOICE_PROVIDER must be one of: unconfigured, livekit"
            }

            val config = AppConfig(
                databaseUrl = env["VOLTY_DATABASE_URL"] ?: "jdbc:postgresql://localhost:5432/volty",
                databaseUser = env["VOLTY_DATABASE_USER"] ?: "volty",
                databasePassword = env["VOLTY_DATABASE_PASSWORD"] ?: "volty-dev-password",
                jwtSecret = env["VOLTY_JWT_SECRET"] ?: error("VOLTY_JWT_SECRET is required"),
                accessTtlSeconds = env["VOLTY_ACCESS_TTL_SECONDS"]?.toLongOrNull() ?: 900,
                refreshTtlSeconds = env["VOLTY_REFRESH_TTL_SECONDS"]?.toLongOrNull() ?: 2_592_000,
                maxShareTtlMillis = env["VOLTY_MAX_SHARE_TTL_MILLIS"]?.toLongOrNull() ?: 86_400_000,
                corsOrigins = (env["VOLTY_CORS_ORIGINS"] ?: "https://volty.sodove.ru")
                    .split(',').map(String::trim).filter(String::isNotEmpty).toSet(),
                voiceProvider = provider,
                liveKitUrl = env["LIVEKIT_URL"]?.trim()?.takeIf(String::isNotEmpty),
                liveKitApiKey = env["LIVEKIT_API_KEY"]?.trim()?.takeIf(String::isNotEmpty),
                liveKitApiSecret = env["LIVEKIT_API_SECRET"]?.trim()?.takeIf(String::isNotEmpty),
                voiceTokenTtlSeconds = env["VOLTY_VOICE_TOKEN_TTL_SECONDS"]?.toLongOrNull() ?: DEFAULT_VOICE_TOKEN_TTL_SECONDS,
                publicIp = env["VOLTY_PUBLIC_IP"]?.trim()?.takeIf(String::isNotEmpty),
            )

            if (provider == "livekit") {
                val missing = buildList {
                    if (config.liveKitUrl == null) add("LIVEKIT_URL")
                    if (config.liveKitApiKey == null) add("LIVEKIT_API_KEY")
                    if (config.liveKitApiSecret == null) add("LIVEKIT_API_SECRET")
                    if (config.publicIp == null) add("VOLTY_PUBLIC_IP")
                }
                require(missing.isEmpty()) {
                    "VOLTY_VOICE_PROVIDER=livekit requires ${missing.joinToString(", ")}"
                }
                require(config.voiceTokenTtlSeconds in MIN_VOICE_TOKEN_TTL_SECONDS..MAX_VOICE_TOKEN_TTL_SECONDS) {
                    "VOLTY_VOICE_TOKEN_TTL_SECONDS must be between $MIN_VOICE_TOKEN_TTL_SECONDS and $MAX_VOICE_TOKEN_TTL_SECONDS"
                }
            }

            return config
        }

        fun forTests() = AppConfig(
            databaseUrl = "jdbc:postgresql://localhost:5432/volty-test",
            databaseUser = "volty",
            databasePassword = "test",
            jwtSecret = "test-secret-that-is-long-enough-for-hmac",
            corsOrigins = setOf("https://volty.sodove.ru"),
        )

        const val DEFAULT_VOICE_TOKEN_TTL_SECONDS = 24L * 60 * 60
        const val MIN_VOICE_TOKEN_TTL_SECONDS = 30L
        const val MAX_VOICE_TOKEN_TTL_SECONDS = 24L * 60 * 60
    }
}

data class LiveKitConfig(
    val serverUrl: String,
    val apiKey: String,
    val apiSecret: String,
    val tokenTtlSeconds: Long,
    val publicIp: String,
)

data class UserRecord(
    val id: String,
    val email: String,
    val passwordHash: String,
    val displayName: String,
    val emailVerified: Boolean,
    val deletedAtEpochMillis: Long? = null,
    val tokensRevokedAtEpochSeconds: Long = 0,
)

data class RefreshRow(
    val id: String,
    val userId: String,
    val tokenHash: String,
    val expiresAtEpochSeconds: Long,
    val revokedAtEpochSeconds: Long?,
)

data class FriendshipRow(
    val id: String,
    val requesterId: String,
    val addresseeId: String,
    val status: String,
)

data class GroupRow(
    val id: String,
    val name: String,
    val ownerId: String,
    val inviteCode: String,
    val inviteExpiresAtEpochMillis: Long?,
)

data class ShareRow(
    val userId: String,
    val groupId: String,
    val profile: String,
    val startedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
)

data class LiveRow(
    val userId: String,
    val groupId: String,
    val location: LocationDto?,
    val telemetry: SharedTelemetryDto?,
    val capturedAtEpochMillis: Long,
    val lastSeenAtEpochMillis: Long,
)

interface BackendStore {
    fun findUserById(id: String): UserRecord? = null
    fun findUserByEmail(email: String): UserRecord? = null
    fun createUser(email: String, passwordHash: String, displayName: String, now: Long): UserRecord = unsupported()
    fun updateDisplayName(userId: String, displayName: String): UserRecord = unsupported()
    fun updatePassword(userId: String, passwordHash: String, now: Long): Int = unsupported()
    fun markEmailVerified(userId: String): Int = unsupported()
    fun revokeAllUserTokens(userId: String, nowEpochSeconds: Long): Unit = unsupported()
    fun deleteAccount(userId: String): Int = unsupported()
    fun createOneTimeToken(userId: String, purpose: String, hash: String, expiresAt: Long): Int = unsupported()
    fun consumeOneTimeToken(purpose: String, hash: String, now: Long): String? = unsupported()
    fun insertRefreshToken(userId: String, tokenHash: String, expiresAt: Long, now: Long): String = unsupported()
    fun findRefreshToken(hash: String): RefreshRow? = null
    fun rotateRefreshToken(id: String, replacementHash: String, now: Long): Boolean = unsupported()
    fun isAccessActive(userId: String, issuedAtEpochSeconds: Long): Boolean = false
    fun listFriends(userId: String): List<FriendSummaryDto> = unsupported()
    fun searchUsers(userId: String, query: String, limit: Int = 20): List<UserSearchResultDto> = unsupported()
    fun createFriendRequest(requesterId: String, addresseeId: String, now: Long): FriendRequestResultDto = unsupported()
    fun respondToFriendRequest(userId: String, friendshipId: String, accept: Boolean): FriendRequestResultDto = unsupported()
    fun listGroups(userId: String): List<GroupDto> = unsupported()
    fun createGroup(userId: String, name: String, now: Long): GroupDto = unsupported()
    fun joinGroup(userId: String, inviteCode: String, now: Long): GroupDto = unsupported()
    fun leaveGroup(userId: String, groupId: String): Unit = unsupported()
    fun deleteGroup(userId: String, groupId: String): Unit = unsupported()
    fun isGroupMember(userId: String, groupId: String): Boolean = false
    fun startSharing(userId: String, request: StartSharingRequest, expiresAt: Long): ShareRow = unsupported()
    fun getShare(userId: String, groupId: String): ShareRow? = null
    fun stopSharing(userId: String, groupId: String): Boolean = unsupported()
    fun publishSharing(userId: String, groupId: String, location: LocationDto?, telemetry: SharedTelemetryDto?, capturedAt: Long, now: Long): Boolean = unsupported()
    fun expireShares(now: Long): List<ShareRow> = unsupported()
    fun snapshot(groupId: String, now: Long): LiveSnapshotDto = unsupported()

    private fun unsupported(): Nothing = error("This store operation is not available in the test store")
}

class JdbcStore(private val dataSource: DataSource, private val json: Json) : BackendStore {
    override fun findUserById(id: String): UserRecord? = dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT * FROM users WHERE id = ? AND deleted_at IS NULL").use { statement ->
            statement.setObject(1, UUID.fromString(id))
            statement.executeQuery().use { if (it.next()) it.user() else null }
        }
    }

    override fun findUserByEmail(email: String): UserRecord? = dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT * FROM users WHERE email = ? AND deleted_at IS NULL").use { statement ->
            statement.setString(1, email)
            statement.executeQuery().use { if (it.next()) it.user() else null }
        }
    }

    override fun createUser(email: String, passwordHash: String, displayName: String, now: Long): UserRecord = dataSource.connection.use { connection ->
        // Registration is immediately usable. This deployment has no mail
        // transport, so a pending state would only make a valid account look
        // broken and gate social actions for no reason.
        connection.prepareStatement("INSERT INTO users (id,email,password_hash,display_name,email_verified,created_at) VALUES (?, ?, ?, ?, true, ?) RETURNING *").use { statement ->
            statement.setObject(1, UUID.randomUUID())
            statement.setString(2, email)
            statement.setString(3, passwordHash)
            statement.setString(4, displayName)
            statement.setLong(5, now)
            statement.executeQuery().use { it.next(); it.user() }
        }
    }

    override fun updateDisplayName(userId: String, displayName: String): UserRecord = dataSource.connection.use { connection ->
        connection.prepareStatement("UPDATE users SET display_name = ? WHERE id = ? AND deleted_at IS NULL RETURNING *").use { statement ->
            statement.setString(1, displayName)
            statement.setObject(2, UUID.fromString(userId))
            statement.executeQuery().use { if (it.next()) it.user() else error("user not found") }
        }
    }

    override fun updatePassword(userId: String, passwordHash: String, now: Long) = dataSource.connection.use { connection ->
        connection.prepareStatement("UPDATE users SET password_hash = ?, tokens_revoked_at = ? WHERE id = ?").use { statement ->
            statement.setString(1, passwordHash)
            statement.setLong(2, now / 1000)
            statement.setObject(3, UUID.fromString(userId))
            statement.executeUpdate()
        }
    }

    override fun markEmailVerified(userId: String) = dataSource.connection.use { connection ->
        connection.prepareStatement("UPDATE users SET email_verified = true WHERE id = ?").use { statement ->
            statement.setObject(1, UUID.fromString(userId)); statement.executeUpdate()
        }
    }

    override fun revokeAllUserTokens(userId: String, nowEpochSeconds: Long) = dataSource.connection.use { connection ->
        connection.autoCommit = false
        connection.prepareStatement("UPDATE users SET tokens_revoked_at = ? WHERE id = ?").use { statement ->
            statement.setLong(1, nowEpochSeconds); statement.setObject(2, UUID.fromString(userId)); statement.executeUpdate()
        }
        connection.prepareStatement("UPDATE refresh_tokens SET revoked_at = ? WHERE user_id = ? AND revoked_at IS NULL").use { statement ->
            statement.setLong(1, nowEpochSeconds); statement.setObject(2, UUID.fromString(userId)); statement.executeUpdate()
        }
        connection.commit()
    }

    override fun deleteAccount(userId: String): Int = dataSource.connection.use { connection ->
        connection.autoCommit = false
        try {
            val now = nowMillis()
            val changed = connection.prepareStatement("UPDATE users SET deleted_at = ?, tokens_revoked_at = ? WHERE id = ?").use { statement ->
                statement.setLong(1, now); statement.setLong(2, now / 1000); statement.setObject(3, UUID.fromString(userId)); statement.executeUpdate()
            }
            connection.prepareStatement("DELETE FROM live_updates WHERE user_id = ?").use { it.setObject(1, UUID.fromString(userId)); it.executeUpdate() }
            connection.prepareStatement("DELETE FROM sharing_sessions WHERE user_id = ?").use { it.setObject(1, UUID.fromString(userId)); it.executeUpdate() }
            connection.commit()
            changed
        } catch (error: Throwable) {
            runCatching { connection.rollback() }
            throw error
        }
    }

    override fun createOneTimeToken(userId: String, purpose: String, hash: String, expiresAt: Long) = dataSource.connection.use { connection ->
        connection.prepareStatement("INSERT INTO one_time_tokens (id,user_id,purpose,token_hash,expires_at) VALUES (?, ?, ?, ?, ?)").use { statement ->
            statement.setObject(1, UUID.randomUUID()); statement.setObject(2, UUID.fromString(userId)); statement.setString(3, purpose); statement.setString(4, hash); statement.setLong(5, expiresAt); statement.executeUpdate()
        }
    }

    override fun consumeOneTimeToken(purpose: String, hash: String, now: Long): String? = dataSource.connection.use { connection ->
        connection.autoCommit = false
        val result = connection.prepareStatement("SELECT id,user_id FROM one_time_tokens WHERE purpose = ? AND token_hash = ? AND consumed_at IS NULL AND expires_at > ? FOR UPDATE").use { statement ->
            statement.setString(1, purpose); statement.setString(2, hash); statement.setLong(3, now); statement.executeQuery().use { if (it.next()) it.getString("user_id") else null }
        }
        if (result != null) connection.prepareStatement("UPDATE one_time_tokens SET consumed_at = ? WHERE purpose = ? AND token_hash = ?").use { statement ->
            statement.setLong(1, now); statement.setString(2, purpose); statement.setString(3, hash); statement.executeUpdate()
        }
        connection.commit(); result
    }

    override fun insertRefreshToken(userId: String, tokenHash: String, expiresAt: Long, now: Long): String = dataSource.connection.use { connection ->
        val id = UUID.randomUUID()
        connection.prepareStatement("INSERT INTO refresh_tokens (id,user_id,token_hash,expires_at,created_at) VALUES (?, ?, ?, ?, ?)").use { statement ->
            statement.setObject(1, id); statement.setObject(2, UUID.fromString(userId)); statement.setString(3, tokenHash); statement.setLong(4, expiresAt); statement.setLong(5, now); statement.executeUpdate()
        }
        id.toString()
    }

    override fun findRefreshToken(hash: String): RefreshRow? = dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT id,user_id,token_hash,expires_at,revoked_at FROM refresh_tokens WHERE token_hash = ?").use { statement ->
            statement.setString(1, hash); statement.executeQuery().use { if (it.next()) RefreshRow(it.getString("id"), it.getString("user_id"), it.getString("token_hash"), it.getLong("expires_at"), it.longOrNull("revoked_at")) else null }
        }
    }

    override fun rotateRefreshToken(id: String, replacementHash: String, now: Long): Boolean = dataSource.connection.use { connection ->
        connection.prepareStatement("UPDATE refresh_tokens SET revoked_at = ?, replaced_by_hash = ? WHERE id = ? AND revoked_at IS NULL AND expires_at > ?").use { statement ->
            statement.setLong(1, now); statement.setString(2, replacementHash); statement.setObject(3, UUID.fromString(id)); statement.setLong(4, now); statement.executeUpdate() == 1
        }
    }

    override fun isAccessActive(userId: String, issuedAtEpochSeconds: Long): Boolean = dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT tokens_revoked_at FROM users WHERE id = ? AND deleted_at IS NULL").use { statement ->
            statement.setObject(1, UUID.fromString(userId)); statement.executeQuery().use { it.next() && it.getLong("tokens_revoked_at") <= issuedAtEpochSeconds }
        }
    }

    override fun listFriends(userId: String): List<FriendSummaryDto> = dataSource.connection.use { connection ->
        connection.prepareStatement("""
            SELECT f.id, u.id AS other_id, u.display_name, f.status,
                   f.requester_id, f.addressee_id
            FROM friendships f JOIN users u ON u.id = CASE WHEN f.requester_id = ? THEN f.addressee_id ELSE f.requester_id END
            WHERE (f.requester_id = ? OR f.addressee_id = ?) AND u.deleted_at IS NULL ORDER BY u.display_name
        """.trimIndent()).use { statement ->
            repeat(3) { statement.setObject(it + 1, UUID.fromString(userId)) }
            statement.executeQuery().use { rows ->
                buildList { while (rows.next()) add(FriendSummaryDto(rows.getString("id"), rows.getString("other_id"), rows.getString("display_name"), friendState(rows.getString("status"), rows.getString("requester_id"), userId, rows.getString("addressee_id")))) }
            }
        }
    }

    override fun searchUsers(userId: String, query: String, limit: Int): List<UserSearchResultDto> = dataSource.connection.use { connection ->
        connection.prepareStatement("""
            SELECT u.id, u.display_name, f.id AS friendship_id, f.status AS friendship_status,
                   f.requester_id, f.addressee_id
            FROM users u
            LEFT JOIN friendships f ON
                ((f.requester_id = ? AND f.addressee_id = u.id) OR
                 (f.addressee_id = ? AND f.requester_id = u.id))
            WHERE u.id <> ? AND u.deleted_at IS NULL
              AND (u.display_name ILIKE ? OR u.email = ?)
            ORDER BY u.display_name
            LIMIT ?
        """.trimIndent()).use { statement ->
            val normalized = query.trim()
            statement.setObject(1, UUID.fromString(userId))
            statement.setObject(2, UUID.fromString(userId))
            statement.setObject(3, UUID.fromString(userId))
            statement.setString(4, "%$normalized%")
            statement.setString(5, normalized.lowercase())
            statement.setInt(6, limit.coerceIn(1, 50))
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        val friendshipId = rows.getString("friendship_id")
                        val status = rows.getString("friendship_status")
                        add(
                            UserSearchResultDto(
                                userId = rows.getString("id"),
                                displayName = rows.getString("display_name"),
                                friendshipId = friendshipId,
                                state = status?.let {
                                    friendState(it, rows.getString("requester_id"), userId, rows.getString("addressee_id"))
                                },
                            ),
                        )
                    }
                }
            }
        }
    }

    override fun createFriendRequest(requesterId: String, addresseeId: String, now: Long): FriendRequestResultDto = dataSource.connection.use { connection ->
        connection.autoCommit = false
        try {
            val requester = UUID.fromString(requesterId)
            val addressee = UUID.fromString(addresseeId)
            val existing = connection.prepareStatement("""
                SELECT id, requester_id, addressee_id, status FROM friendships
                WHERE (requester_id = ? AND addressee_id = ?) OR (requester_id = ? AND addressee_id = ?)
                FOR UPDATE
            """.trimIndent()).use { statement ->
                statement.setObject(1, requester); statement.setObject(2, addressee)
                statement.setObject(3, addressee); statement.setObject(4, requester)
                statement.executeQuery().use { if (it.next()) FriendshipRow(it.getString(1), it.getString(2), it.getString(3), it.getString(4)) else null }
            }
            val result = if (existing != null) {
                FriendRequestResultDto(existing.id, friendState(existing.status, existing.requesterId, requesterId, existing.addresseeId))
            } else {
                val id = connection.prepareStatement("INSERT INTO friendships (id,requester_id,addressee_id,status,created_at) VALUES (?, ?, ?, 'PENDING', ?) ON CONFLICT DO NOTHING RETURNING id").use { statement ->
                    statement.setObject(1, UUID.randomUUID()); statement.setObject(2, requester); statement.setObject(3, addressee); statement.setLong(4, now)
                    statement.executeQuery().use { if (it.next()) it.getString(1) else null }
                } ?: connection.prepareStatement("""
                    SELECT id, requester_id, addressee_id, status FROM friendships
                    WHERE (requester_id = ? AND addressee_id = ?) OR (requester_id = ? AND addressee_id = ?)
                """.trimIndent()).use { statement ->
                    statement.setObject(1, requester); statement.setObject(2, addressee)
                    statement.setObject(3, addressee); statement.setObject(4, requester)
                    statement.executeQuery().use { if (it.next()) it.getString("id") else throw IllegalStateException("friend request could not be created") }
                }
                FriendRequestResultDto(id, "REQUEST_SENT")
            }
            connection.commit()
            result
        } catch (error: Throwable) {
            runCatching { connection.rollback() }
            throw error
        }
    }

    override fun respondToFriendRequest(userId: String, friendshipId: String, accept: Boolean): FriendRequestResultDto = dataSource.connection.use { connection ->
        connection.autoCommit = false
        try {
            val row = connection.prepareStatement("SELECT id,requester_id,addressee_id,status FROM friendships WHERE id = ? FOR UPDATE").use { statement ->
                statement.setObject(1, UUID.fromString(friendshipId))
                statement.executeQuery().use { if (it.next()) FriendshipRow(it.getString(1), it.getString(2), it.getString(3), it.getString(4)) else null }
            } ?: throw IllegalStateException("friend request not found")
            if (row.addresseeId != userId) throw IllegalStateException("friend request not found")
            val result = when {
                row.status == "ACCEPTED" && accept -> FriendRequestResultDto(row.id, "ACCEPTED")
                row.status != "PENDING" -> throw IllegalStateException("friend request not found")
                accept -> {
                    connection.prepareStatement("UPDATE friendships SET status = 'ACCEPTED' WHERE id = ?").use { statement -> statement.setObject(1, UUID.fromString(friendshipId)); statement.executeUpdate() }
                    FriendRequestResultDto(row.id, "ACCEPTED")
                }
                else -> {
                    connection.prepareStatement("DELETE FROM friendships WHERE id = ? AND addressee_id = ? AND status = 'PENDING'").use { statement -> statement.setObject(1, UUID.fromString(friendshipId)); statement.setObject(2, UUID.fromString(userId)); statement.executeUpdate() }
                    FriendRequestResultDto(row.id, "DECLINED")
                }
            }
            connection.commit()
            result
        } catch (error: Throwable) {
            runCatching { connection.rollback() }
            throw error
        }
    }

    override fun listGroups(userId: String): List<GroupDto> = dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT g.* FROM groups g JOIN group_members m ON m.group_id = g.id WHERE m.user_id = ? ORDER BY g.created_at").use { statement ->
            statement.setObject(1, UUID.fromString(userId)); statement.executeQuery().use { rows -> buildList { while (rows.next()) add(group(rows, connection, userId)) } }
        }
    }

    override fun createGroup(userId: String, name: String, now: Long): GroupDto = dataSource.connection.use { connection ->
        connection.autoCommit = false
        val id = UUID.randomUUID(); val invite = inviteCode()
        connection.prepareStatement("INSERT INTO groups (id,name,owner_id,invite_code,invite_expires_at,created_at) VALUES (?, ?, ?, ?, ?, ?)").use { statement ->
            statement.setObject(1, id); statement.setString(2, name); statement.setObject(3, UUID.fromString(userId)); statement.setString(4, invite); statement.setLong(5, now + 30L * 24 * 60 * 60 * 1000); statement.setLong(6, now); statement.executeUpdate()
        }
        connection.prepareStatement("INSERT INTO group_members (group_id,user_id,role,joined_at) VALUES (?, ?, 'OWNER', ?)").use { statement -> statement.setObject(1, id); statement.setObject(2, UUID.fromString(userId)); statement.setLong(3, now); statement.executeUpdate() }
        connection.commit()
        connection.prepareStatement("SELECT * FROM groups WHERE id = ?").use { statement -> statement.setObject(1, id); statement.executeQuery().use { it.next(); group(it, connection, userId) } }
    }

    override fun joinGroup(userId: String, inviteCode: String, now: Long): GroupDto = dataSource.connection.use { connection ->
        val group = connection.prepareStatement("SELECT * FROM groups WHERE invite_code = ? AND (invite_expires_at IS NULL OR invite_expires_at > ?)").use { statement -> statement.setString(1, inviteCode); statement.setLong(2, now); statement.executeQuery().use { if (it.next()) it.groupRow() else null } } ?: throw NoSuchElementException("invite not found or expired")
        connection.prepareStatement("INSERT INTO group_members (group_id,user_id,role,joined_at) VALUES (?, ?, 'MEMBER', ?) ON CONFLICT DO NOTHING").use { statement -> statement.setObject(1, UUID.fromString(group.id)); statement.setObject(2, UUID.fromString(userId)); statement.setLong(3, now); statement.executeUpdate() }
        val ownerIsStillMember = connection.prepareStatement("SELECT 1 FROM group_members WHERE group_id = ? AND user_id = ?").use { statement -> statement.setObject(1, UUID.fromString(group.id)); statement.setObject(2, UUID.fromString(group.ownerId)); statement.executeQuery().use { it.next() } }
        val effectiveGroup = if (ownerIsStillMember) group else {
            connection.prepareStatement("UPDATE groups SET owner_id = ? WHERE id = ?").use { statement -> statement.setObject(1, UUID.fromString(userId)); statement.setObject(2, UUID.fromString(group.id)); statement.executeUpdate() }
            connection.prepareStatement("UPDATE group_members SET role = 'OWNER' WHERE group_id = ? AND user_id = ?").use { statement -> statement.setObject(1, UUID.fromString(group.id)); statement.setObject(2, UUID.fromString(userId)); statement.executeUpdate() }
            group.copy(ownerId = userId)
        }
        groupDto(effectiveGroup, connection, userId)
    }

    override fun leaveGroup(userId: String, groupId: String) = dataSource.connection.use { connection ->
        connection.autoCommit = false
        try {
            val owner = connection.prepareStatement("SELECT owner_id FROM groups WHERE id = ?").use { statement -> statement.setObject(1, UUID.fromString(groupId)); statement.executeQuery().use { if (it.next()) it.getString(1) else null } } ?: throw NoSuchElementException("group not found")
            if (owner == userId) {
                val replacementOwner = connection.prepareStatement("SELECT user_id FROM group_members WHERE group_id = ? AND user_id <> ? ORDER BY joined_at, user_id LIMIT 1").use { statement -> statement.setObject(1, UUID.fromString(groupId)); statement.setObject(2, UUID.fromString(userId)); statement.executeQuery().use { if (it.next()) it.getString(1) else null } }
                if (replacementOwner != null) {
                    connection.prepareStatement("UPDATE groups SET owner_id = ? WHERE id = ?").use { statement -> statement.setObject(1, UUID.fromString(replacementOwner)); statement.setObject(2, UUID.fromString(groupId)); statement.executeUpdate() }
                    connection.prepareStatement("UPDATE group_members SET role = 'OWNER' WHERE group_id = ? AND user_id = ?").use { statement -> statement.setObject(1, UUID.fromString(groupId)); statement.setObject(2, UUID.fromString(replacementOwner)); statement.executeUpdate() }
                }
            }
            connection.prepareStatement("DELETE FROM group_members WHERE group_id = ? AND user_id = ?").use { statement -> statement.setObject(1, UUID.fromString(groupId)); statement.setObject(2, UUID.fromString(userId)); if (statement.executeUpdate() != 1) throw IllegalStateException("not a group member") }
            connection.prepareStatement("UPDATE sharing_sessions SET revoked_at = ? WHERE user_id = ? AND group_id = ? AND revoked_at IS NULL").use { statement -> statement.setLong(1, nowMillis()); statement.setObject(2, UUID.fromString(userId)); statement.setObject(3, UUID.fromString(groupId)); statement.executeUpdate() }
            connection.prepareStatement("DELETE FROM live_updates WHERE user_id = ? AND group_id = ?").use { statement -> statement.setObject(1, UUID.fromString(userId)); statement.setObject(2, UUID.fromString(groupId)); statement.executeUpdate() }
            connection.commit()
        } catch (error: Throwable) {
            runCatching { connection.rollback() }
            throw error
        }
    }

    override fun deleteGroup(userId: String, groupId: String) = dataSource.connection.use { connection ->
        connection.autoCommit = false
        try {
            val ownerId = connection.prepareStatement("SELECT owner_id FROM groups WHERE id = ?").use { statement ->
                statement.setObject(1, UUID.fromString(groupId))
                statement.executeQuery().use { if (it.next()) it.getString(1) else null }
            } ?: throw NoSuchElementException("group not found")
            if (ownerId != userId) throw GroupOwnerRequiredException()
            val stillMember = connection.prepareStatement("SELECT 1 FROM group_members WHERE group_id = ? AND user_id = ?").use { statement ->
                statement.setObject(1, UUID.fromString(groupId))
                statement.setObject(2, UUID.fromString(userId))
                statement.executeQuery().use { it.next() }
            }
            if (!stillMember) throw GroupOwnerRequiredException()
            connection.prepareStatement("DELETE FROM groups WHERE id = ? AND owner_id = ?").use { statement ->
                statement.setObject(1, UUID.fromString(groupId))
                statement.setObject(2, UUID.fromString(userId))
                statement.executeUpdate()
            }
            connection.commit()
        } catch (error: Throwable) {
            runCatching { connection.rollback() }
            throw error
        }
    }

    override fun isGroupMember(userId: String, groupId: String): Boolean = dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT 1 FROM group_members WHERE group_id = ? AND user_id = ?").use { statement -> statement.setObject(1, UUID.fromString(groupId)); statement.setObject(2, UUID.fromString(userId)); statement.executeQuery().use { it.next() } }
    }

    override fun startSharing(userId: String, request: StartSharingRequest, expiresAt: Long): ShareRow = dataSource.connection.use { connection ->
        connection.prepareStatement("UPDATE sharing_sessions SET revoked_at = ? WHERE user_id = ? AND group_id = ? AND revoked_at IS NULL").use { statement -> statement.setLong(1, nowMillis()); statement.setObject(2, UUID.fromString(userId)); statement.setObject(3, UUID.fromString(request.groupId)); statement.executeUpdate() }
        connection.prepareStatement("DELETE FROM live_updates WHERE user_id = ? AND group_id = ?").use { statement -> statement.setObject(1, UUID.fromString(userId)); statement.setObject(2, UUID.fromString(request.groupId)); statement.executeUpdate() }
        connection.prepareStatement("INSERT INTO sharing_sessions (id,user_id,group_id,profile,started_at,expires_at) VALUES (?, ?, ?, ?, ?, ?)").use { statement -> statement.setObject(1, UUID.randomUUID()); statement.setObject(2, UUID.fromString(userId)); statement.setObject(3, UUID.fromString(request.groupId)); statement.setString(4, request.profile); statement.setLong(5, request.startedAtEpochMillis); statement.setLong(6, expiresAt); statement.executeUpdate() }
        ShareRow(userId, request.groupId, request.profile, request.startedAtEpochMillis, expiresAt)
    }

    override fun getShare(userId: String, groupId: String): ShareRow? = dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT user_id,group_id,profile,started_at,expires_at FROM sharing_sessions WHERE user_id = ? AND group_id = ? AND revoked_at IS NULL").use { statement -> statement.setObject(1, UUID.fromString(userId)); statement.setObject(2, UUID.fromString(groupId)); statement.executeQuery().use { if (it.next()) ShareRow(it.getString(1), it.getString(2), it.getString(3), it.getLong(4), it.getLong(5)) else null } }
    }

    override fun stopSharing(userId: String, groupId: String): Boolean = dataSource.connection.use { connection ->
        connection.prepareStatement("UPDATE sharing_sessions SET revoked_at = ? WHERE user_id = ? AND group_id = ? AND revoked_at IS NULL").use { statement -> statement.setLong(1, nowMillis()); statement.setObject(2, UUID.fromString(userId)); statement.setObject(3, UUID.fromString(groupId)); val changed = statement.executeUpdate(); connection.prepareStatement("DELETE FROM live_updates WHERE user_id = ? AND group_id = ?").use { cleanup -> cleanup.setObject(1, UUID.fromString(userId)); cleanup.setObject(2, UUID.fromString(groupId)); cleanup.executeUpdate() }; changed > 0 }
    }

    override fun publishSharing(userId: String, groupId: String, location: LocationDto?, telemetry: SharedTelemetryDto?, capturedAt: Long, now: Long): Boolean = dataSource.connection.use { connection ->
        connection.prepareStatement("INSERT INTO live_updates (user_id,group_id,location_json,telemetry_json,captured_at,last_seen_at) VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (user_id,group_id) DO UPDATE SET location_json = EXCLUDED.location_json, telemetry_json = EXCLUDED.telemetry_json, captured_at = EXCLUDED.captured_at, last_seen_at = EXCLUDED.last_seen_at").use { statement ->
            statement.setObject(1, UUID.fromString(userId)); statement.setObject(2, UUID.fromString(groupId)); statement.setString(3, location?.let(json::encodeToString)); statement.setString(4, telemetry?.let(json::encodeToString)); statement.setLong(5, capturedAt); statement.setLong(6, now); statement.executeUpdate() > 0
        }
    }

    override fun expireShares(now: Long): List<ShareRow> = dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT user_id,group_id,profile,started_at,expires_at FROM sharing_sessions WHERE revoked_at IS NULL AND expires_at <= ?").use { statement ->
            statement.setLong(1, now); val expired = statement.executeQuery().use { rows -> buildList { while (rows.next()) add(ShareRow(rows.getString(1), rows.getString(2), rows.getString(3), rows.getLong(4), rows.getLong(5))) } }
            connection.prepareStatement("DELETE FROM live_updates WHERE (user_id,group_id) IN (SELECT user_id,group_id FROM sharing_sessions WHERE revoked_at IS NULL AND expires_at <= ?)").use { cleanup -> cleanup.setLong(1, now); cleanup.executeUpdate() }
            connection.prepareStatement("UPDATE sharing_sessions SET revoked_at = ? WHERE revoked_at IS NULL AND expires_at <= ?").use { update -> update.setLong(1, now); update.setLong(2, now); update.executeUpdate() }
            expired
        }
    }

    override fun snapshot(groupId: String, now: Long): LiveSnapshotDto = dataSource.connection.use { connection ->
        connection.prepareStatement("""
            SELECT m.user_id,u.display_name,l.location_json,l.telemetry_json,l.captured_at,l.last_seen_at,
                   CASE
                       WHEN s.user_id IS NULL OR s.expires_at <= ? THEN 'OFFLINE'
                       WHEN l.last_seen_at IS NULL OR l.last_seen_at + 30_000 <= ? THEN 'STALE'
                       ELSE 'ONLINE'
                   END AS presence
            FROM group_members m
            JOIN users u ON u.id = m.user_id
            LEFT JOIN sharing_sessions s ON s.user_id = m.user_id AND s.group_id = m.group_id AND s.revoked_at IS NULL
            LEFT JOIN live_updates l ON l.user_id = m.user_id AND l.group_id = m.group_id
            WHERE m.group_id = ? AND u.deleted_at IS NULL
            ORDER BY m.joined_at
        """.trimIndent()).use { statement ->
            statement.setLong(1, now); statement.setLong(2, now); statement.setObject(3, UUID.fromString(groupId)); statement.executeQuery().use { rows ->
                LiveSnapshotDto(groupId, now, buildList {
                    while (rows.next()) {
                        add(ParticipantDto(
                            userId = rows.getString("user_id"),
                            displayName = rows.getString("display_name"),
                            presence = rows.getString("presence"),
                            location = rows.getString("location_json")?.let(json::decodeFromString),
                            telemetry = rows.getString("telemetry_json")?.let(json::decodeFromString),
                            lastSeenAtEpochMillis = rows.getLong("last_seen_at").takeUnless { rows.wasNull() } ?: 0L,
                        ))
                    }
                })
            }
        }
    }

    private fun group(rows: ResultSet, connection: Connection, viewerId: String): GroupDto = groupDto(rows.groupRow(), connection, viewerId)

    private fun groupDto(group: GroupRow, connection: Connection, viewerId: String): GroupDto {
        val members = connection.prepareStatement("SELECT m.user_id,u.display_name,m.role FROM group_members m JOIN users u ON u.id = m.user_id WHERE m.group_id = ? ORDER BY m.joined_at").use { statement -> statement.setObject(1, UUID.fromString(group.id)); statement.executeQuery().use { rows -> buildList { while (rows.next()) add(GroupMemberDto(rows.getString(1), rows.getString(2), rows.getString(3))) } } }
        return GroupDto(group.id, group.name, group.ownerId, members, inviteExpiresAtEpochMillis = group.inviteExpiresAtEpochMillis, inviteCode = group.inviteCode.takeIf { group.ownerId == viewerId })
    }

    private fun ResultSet.groupRow() = GroupRow(getString("id"), getString("name"), getString("owner_id"), getString("invite_code"), getLong("invite_expires_at").takeUnless { wasNull() })
    private fun ResultSet.user() = UserRecord(getString("id"), getString("email"), getString("password_hash"), getString("display_name"), getBoolean("email_verified"), getLong("deleted_at").takeUnless { wasNull() }, getLong("tokens_revoked_at"))
    private fun ResultSet.longOrNull(column: String): Long? = getLong(column).takeUnless { wasNull() }

    private fun friendState(status: String, requesterId: String, userId: String, addresseeId: String): String = when {
        status == "ACCEPTED" -> "ACCEPTED"
        status == "BLOCKED" -> "BLOCKED"
        requesterId == userId && addresseeId != userId -> "REQUEST_SENT"
        else -> "REQUEST_RECEIVED"
    }

    private fun inviteCode(): String = java.util.UUID.randomUUID().toString().replace("-", "").take(10).uppercase()
}

class SchemaMigrator(private val dataSource: DataSource) {
    fun migrate() {
        dataSource.connection.use { connection ->
            connection.createStatement().use { it.execute("CREATE TABLE IF NOT EXISTS schema_migrations (version INTEGER PRIMARY KEY, applied_at BIGINT NOT NULL)") }
            val applied = connection.prepareStatement("SELECT version FROM schema_migrations").use { statement -> statement.executeQuery().use { rows -> buildSet { while (rows.next()) add(rows.getInt(1)) } } }
            if (1 !in applied) {
                val sql = javaClass.getResourceAsStream("/db/migration/V1__initial.sql")!!.bufferedReader().readText()
                connection.autoCommit = false
                sql.split(';').map(String::trim).filter(String::isNotEmpty).forEach { connection.createStatement().use { statement -> statement.execute(it) } }
                connection.prepareStatement("INSERT INTO schema_migrations(version,applied_at) VALUES (1, ?)").use { statement -> statement.setLong(1, nowMillis()); statement.executeUpdate() }
                connection.commit()
            }
        }
    }
}

fun createDataSource(config: AppConfig): HikariDataSource = HikariDataSource(HikariConfig().apply {
    jdbcUrl = config.databaseUrl
    username = config.databaseUser
    password = config.databasePassword
    maximumPoolSize = 10
    minimumIdle = 2
    connectionTimeout = Duration.ofSeconds(5).toMillis()
    validationTimeout = Duration.ofSeconds(3).toMillis()
    initializationFailTimeout = -1
})
