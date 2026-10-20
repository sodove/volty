package ru.sodovaya.volty.backend.nearby

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import ru.sodovaya.volty.backend.SchemaMigrator
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.*

@Tag("postgres")
class JdbcRadioKeyRepositoryTest {
    private val source=schema()
    private val owner="ffffffff-0000-0000-0000-000000000099"
    private val member="abcdef00-0000-0000-0000-000000000001"
    private val stranger=id();private val community=id();private val other=id()
    private val keys=JdbcRadioKeyRepository(source,ByteArray(32) {(it+1).toByte()})
    @BeforeEach fun prepare() {
        SchemaMigrator(source).migrate()
        for(actor in listOf(owner,member,stranger)) sql("INSERT INTO users(id,email,password_hash,display_name,created_at) VALUES ('$actor','$actor@example.invalid','private-hash','Rider-$actor',0)")
        val communities=JdbcCommunityRepository(source)
        communities.create(owner,community,"Riders",visibility=CommunityVisibility.PUBLIC,now={100})
        communities.create(owner,other,"Other",now={100})
        communities.joinPublic(member,community,now={101})
    }
    @Test fun memberGetsStableKeyAtEpochZero() {
        val first=assertIs<RadioKeyResult.Issued>(keys.issue(member,community))
        val second=assertIs<RadioKeyResult.Issued>(keys.issue(owner,community))
        assertEquals(0L,first.epoch);assertEquals(0L,second.epoch);assertEquals(32,first.key.size)
        assertContentEquals(first.key,second.key)
    }
    @Test fun departureAdvancesEpochAndChangesKey() {
        val before=assertIs<RadioKeyResult.Issued>(keys.issue(owner,community))
        sql("INSERT INTO nearby_community_outbox(revision,community_id,actor_id,kind) VALUES (1000,'$community','$member','MEMBER_LEFT')")
        val after=assertIs<RadioKeyResult.Issued>(keys.issue(owner,community))
        assertEquals(1L,after.epoch);assertFalse(before.key.contentEquals(after.key))
    }
    @Test fun nonMemberIsDenied() {
        assertEquals(RadioKeyResult.Denied,keys.issue(stranger,community))
        sql("UPDATE nearby_communities SET state='DELETED' WHERE id='$community'")
        assertEquals(RadioKeyResult.Denied,keys.issue(owner,community))
    }
    @Test fun keysDifferAcrossCommunities() {
        val a=assertIs<RadioKeyResult.Issued>(keys.issue(owner,community))
        val b=assertIs<RadioKeyResult.Issued>(keys.issue(owner,other))
        assertEquals(a.epoch,b.epoch);assertFalse(a.key.contentEquals(b.key))
    }
    private fun sql(query:String)=source.connection.use {c -> c.createStatement().use {it.execute(query)}}
    private fun schema():DataSource {
        val name="radio_key_"+id().replace("-","")
        NearbyPostgresFixture.source().connection.use {c -> c.createStatement().use {it.execute("CREATE SCHEMA $name")}}
        return NearbyPostgresFixture.source(name)
    }
    private fun id()=UUID.randomUUID().toString()
}
