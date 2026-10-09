package ru.sodovaya.volty.backend.nearby

import org.junit.jupiter.api.Tag
import ru.sodovaya.volty.backend.*
import java.util.UUID
import kotlin.test.*

@Tag("postgres")
class JdbcPasswordRecoveryTest {
    private val source = NearbyPostgresFixture.source()

    @Test fun password_change_prevents_old_refresh_rotation_and_preserves_other_accounts() {
        SchemaMigrator(source).migrate()
        val store = JdbcStore(source, backendJson())
        val nonce = UUID.randomUUID().toString()
        val user = store.createUser("$nonce@example.invalid", "old-hash", "Test", 100_000)
        val other = store.createUser("other-$nonce@example.invalid", "other-hash", "Other", 100_000)
        val old = store.insertRefreshToken(user.id, sha256("old-$nonce"), 1000, 100)
        store.insertRefreshToken(other.id, sha256("other-$nonce"), 1000, 100)
        assertTrue(store.isAccessActive(user.id, 100))
        assertEquals(1, store.updatePassword(user.id, "new-hash", 200_000))
        assertEquals("new-hash", store.findUserById(user.id)!!.passwordHash)
        assertFalse(store.isAccessActive(user.id, 100))
        assertFalse(store.rotateRefreshToken(old, sha256("replacement-$nonce"), 201), "Old session must not renew after password reset")
        assertNotNull(store.findRefreshToken(sha256("old-$nonce"))!!.revokedAtEpochSeconds)
        assertNull(store.findRefreshToken(sha256("other-$nonce"))!!.revokedAtEpochSeconds)
        assertEquals("other-hash", store.findUserById(other.id)!!.passwordHash)
    }

    @Test fun a_deleted_account_cannot_have_its_password_changed_by_an_old_link() {
        SchemaMigrator(source).migrate()
        val store = JdbcStore(source, backendJson())
        val nonce = UUID.randomUUID().toString()
        val user = store.createUser("$nonce@example.invalid", "original-hash", "Test", 100_000)
        source.connection.use { connection ->
            connection.prepareStatement("UPDATE users SET deleted_at = ? WHERE id = ?").use {
                it.setLong(1, 150_000); it.setObject(2, UUID.fromString(user.id)); it.executeUpdate()
            }
        }
        assertEquals(0, store.updatePassword(user.id, "new-hash", 200_000))
        source.connection.use { connection ->
            connection.prepareStatement("SELECT password_hash FROM users WHERE id = ?").use {
                it.setObject(1, UUID.fromString(user.id))
                it.executeQuery().use { rows -> assertTrue(rows.next()); assertEquals("original-hash", rows.getString(1)) }
            }
        }
    }
}
