package ru.sodovaya.volty.backend.nearby

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import ru.sodovaya.volty.backend.SchemaMigrator
import java.util.UUID
import java.sql.SQLException
import kotlin.test.*

@Tag("postgres")
class JdbcDirectCallPushJournalTest {
    private val caller = id(); private val callee = id()
    private val call = id(); private val key = id(); private val operation = id()
    private val schema = "call_push_" + id().replace("-", "")
    private val source = NearbyPostgresFixture.source().let {base ->
        base.connection.use {c -> c.createStatement().use {it.execute("CREATE SCHEMA $schema")}}
        NearbyPostgresFixture.source(schema)
    }
    private val rings = JdbcDirectCallRingingRepository(source)
    private val media = JdbcDirectCallMediaRepository(source)

    @BeforeEach fun prepare() {
        SchemaMigrator(source).migrate()
        source.connection.use {c -> c.createStatement().use {s ->
            for (actor in listOf(caller, callee)) s.execute("INSERT INTO users(id,email,password_hash,display_name,created_at) VALUES ('$actor','$actor@example.invalid','test','test',0)")
            s.execute("INSERT INTO friendships VALUES ('${id()}','$caller','$callee','ACCEPTED',0)")
        }}
    }

    @Test fun committedRingAndReplayHaveOneCalleeHint() {
        val created = assertNotNull(create().call)
        assertEquals(DirectCallPhase.RINGING, created.phase)
        assertEquals(created, create().call)
        assertEquals(listOf(Hint(callee, "RINGING", created.revision)), hints())
    }

    @Test fun declineAndReplayAppendOnlyTheChangedRevision() {
        val created = assertNotNull(create().call)
        val declined = assertNotNull(rings.decline(callee, call, {101}).call)
        assertEquals(DirectCallPhase.DECLINED, declined.phase)
        rings.decline(callee, call, {102})
        assertEquals(listOf(Hint(callee, "RINGING", created.revision), Hint(callee, "DECLINED", declined.revision)), hints())
    }

    @Test fun acceptedMediaAndEndUseTheSameJournal() {
        val created = assertNotNull(create().call)
        val accepted = assertNotNull(media.accept(callee, call, id(), 0, SfuEpoch("volty-call-$call", "node"), {101}).call)
        assertEquals(DirectCallPhase.ACCEPTED, accepted.phase)
        val ended = assertNotNull(media.stop(caller, call, {102}).call)
        assertEquals(DirectCallPhase.ENDED, ended.phase)
        media.stop(callee, call, {103})
        assertEquals(listOf(Hint(callee, "RINGING", created.revision), Hint(callee, "ACCEPTED", accepted.revision), Hint(callee, "ENDED", ended.revision)), hints())
    }

    @Test fun neverRangCancellationRemainsPrivateThroughReplayAndStatus() {
        assertEquals(DirectCallPhase.CANCELLED, rings.cancelOutgoingRinging(caller, callee, call, key, operation, {100}).call?.phase)
        assertEquals(DirectCallPhase.CANCELLED, create().call?.phase)
        rings.status(caller, call, {102})
        assertEquals(DirectCallRejection.ACCESS_DENIED, rings.status(callee, call, {102}).rejection)
        assertEquals(emptyList(), hints())
    }

    @Test fun failedHintRollsBackCallTransitionAndNewMediaClaims() {
        val original = assertNotNull(create().call)
        source.connection.use {c -> c.createStatement().use {
            it.execute("ALTER TABLE nearby_direct_call_push_outbox ADD CONSTRAINT reject_new_hint CHECK (FALSE) NOT VALID")
        }}
        assertFailsWith<SQLException> {rings.decline(callee, call, {101})}
        assertFailsWith<SQLException> {media.accept(callee, call, id(), 0, SfuEpoch("volty-call-$call", "node"), {102})}
        source.connection.use {c -> c.createStatement().use {s ->
            s.executeQuery("SELECT phase,revision FROM nearby_direct_calls WHERE call_id='$call'").use {r ->
                assertTrue(r.next()); assertEquals("RINGING", r.getString(1)); assertEquals(original.revision, r.getLong(2))
            }
            s.executeQuery("SELECT reserved_call_id FROM nearby_actor_claims WHERE actor_id='$caller'").use {r ->
                assertTrue(r.next()); assertEquals(call, r.getString(1))
            }
            s.executeQuery("SELECT COUNT(*) FROM nearby_session_operations").use {r -> assertTrue(r.next()); assertEquals(0, r.getInt(1))}
        }}
        assertEquals(listOf(Hint(callee, "RINGING", original.revision)), hints())
    }

    private fun create() = rings.create(caller, callee, call, key, operation, {100})
    private data class Hint(val actor: String, val phase: String, val revision: Long)
    private fun hints(): List<Hint> = source.connection.use {c -> c.createStatement().use {s ->
        s.executeQuery("SELECT to_regclass('nearby_direct_call_push_outbox') IS NOT NULL").use {r ->
            assertTrue(r.next()); assertTrue(r.getBoolean(1), "Committed call must have a durable hint journal")
        }
        s.executeQuery("SELECT actor_id,phase,call_revision FROM nearby_direct_call_push_outbox WHERE call_id='$call' ORDER BY event_id").use {r ->
            buildList {while (r.next()) add(Hint(r.getString(1), r.getString(2), r.getLong(3)))}
        }
    }}
    private fun id() = UUID.randomUUID().toString()
}
