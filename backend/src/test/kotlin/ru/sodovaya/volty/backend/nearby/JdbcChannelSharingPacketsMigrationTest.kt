package ru.sodovaya.volty.backend.nearby

import org.junit.jupiter.api.Tag
import ru.sodovaya.volty.backend.SchemaMigrator
import java.util.UUID
import kotlin.test.*

@Tag("postgres")
class JdbcChannelSharingPacketsMigrationTest {
    @Test fun earlierActiveReceiptWithoutEarnedStartIsRevokedWithoutLosingAccountsOrFriends() {
        fun id() = UUID.randomUUID().toString()
        val schema = "packet_upgrade_" + id().replace("-", "")
        val source = NearbyPostgresFixture.source().let {base ->
            base.connection.use {c -> c.createStatement().use {it.execute("CREATE SCHEMA $schema")}}
            NearbyPostgresFixture.source(schema)
        }
        val historical = listOf("V1__initial.sql", "V2__nearby_session_journal.sql", "V3__nearby_roster_journal.sql",
            "V4__nearby_media_webhook_inbox.sql", "V5__nearby_media_observations.sql", "V6__nearby_room_webhook_targets.sql",
            "V7__nearby_communities.sql", "V8__nearby_departure_receipts.sql", "V9__nearby_resource_deletion_receipts.sql",
            "V10__nearby_channel_creation_receipts.sql", "V11__nearby_private_invitations.sql", "V12__nearby_ringing_calls.sql",
            "V13__nearby_typed_voice_destinations.sql", "V14__nearby_unique_call_actor_media.sql", "V15__nearby_lost_call_media.sql",
            "V16__nearby_channel_sharing_consent.sql").mapIndexed {index, file -> index + 1 to "/db/migration/$file"}
        SchemaMigrator(source, historical).migrate()
        val actor = id(); val peer = id(); val session = id(); val generation = id()
        source.connection.use {c -> c.createStatement().use {s ->
            for (user in listOf(actor, peer)) s.execute("INSERT INTO users(id,email,password_hash,display_name,created_at) VALUES ('$user','$user@example.invalid','test','test',0)")
            s.execute("INSERT INTO friendships VALUES ('${id()}','$actor','$peer','ACCEPTED',0)")
            s.execute("INSERT INTO nearby_channel_sharing_consent VALUES ('$actor','$session','$generation','ACTIVE','LOCATION',1000)")
        }}
        SchemaMigrator(source).migrate(); SchemaMigrator(source).migrate()
        source.connection.use {c -> c.createStatement().use {s ->
            s.executeQuery("SELECT phase,started_at,deadline_epoch_millis FROM nearby_channel_sharing_consent").use {
                assertTrue(it.next()); assertEquals("REVOKED", it.getString(1)); assertNull(it.getObject(2)); assertEquals(1000, it.getLong(3)); assertFalse(it.next())
            }
            for ((table, expected) in listOf("users" to 2, "friendships" to 1, "schema_migrations" to 18, "nearby_channel_sharing_packets" to 0)) {
                s.executeQuery("SELECT COUNT(*) FROM $table").use {assertTrue(it.next()); assertEquals(expected, it.getInt(1))}
            }
        }}
    }
}
