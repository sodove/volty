package ru.sodovaya.volty.backend.nearby

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import ru.sodovaya.volty.backend.SchemaMigrator
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.*

@Tag("postgres")
class JdbcSessionCommunityMembersTest {
    private val source=schema()
    private val owner="ffffffff-0000-0000-0000-000000000099"
    private val members=(1..3).map {"abcdef00-0000-0000-0000-00000000000$it"}
    private val stranger=id();private val community=id();private val foreign=id()
    private val directory=JdbcCommunityMembersRepository(source)
    @BeforeEach fun prepare() {
        SchemaMigrator(source).migrate()
        for(actor in members+owner+stranger) sql("INSERT INTO users(id,email,password_hash,display_name,created_at) VALUES ('$actor','$actor@example.invalid','private-hash','Rider-$actor',0)")
        val communities=JdbcCommunityRepository(source)
        communities.create(owner,community,"Riders",visibility=CommunityVisibility.PUBLIC,now={100})
        communities.create(stranger,foreign,"Foreign",now={100})
        for(member in members) communities.joinPublic(member,community,now={101})
    }
    @Test fun pagesReadActualOrderedMembersWithoutPrivateFieldsOrSideEffects() {
        val revision=JdbcRosterJournal(source).revision()
        val first=assertIs<CommunityMembersResult.Listed>(directory.list(owner,community,null,2))
        assertEquals(community,first.communityId);assertEquals(owner,first.ownerId);assertEquals(members.take(2),first.items.map {it.userId})
        assertTrue(first.items.all {it.joinedAtMillis==101L && it.displayName=="Rider-${it.userId}"})
        val second=assertIs<CommunityMembersResult.Listed>(directory.list(owner,community,first.items.last().userId,2))
        assertEquals(listOf(members.last(),owner),second.items.map {it.userId})
        assertTrue(assertIs<CommunityMembersResult.Listed>(directory.list(owner,community,owner,2)).items.isEmpty())
        assertFalse(first.toString().contains("private-hash"));assertFalse(first.toString().contains("@example.invalid"))
        assertEquals(revision,JdbcRosterJournal(source).revision());assertEquals(5,count("nearby_community_members"))
        assertEquals(0,count("nearby_session_operations"));assertEquals(0,count("sharing_sessions"))
    }
    @Test fun deletedAndBannedAccountsCannotBeOfferedAsSuccessors() {
        sql("UPDATE users SET deleted_at=102 WHERE id='${members[1]}'")
        sql("INSERT INTO nearby_community_bans(community_id,user_id,created_at) VALUES ('$community','${members[2]}',102)")
        assertEquals(listOf(members.first(),owner),assertIs<CommunityMembersResult.Listed>(directory.list(owner,community,null,20)).items.map {it.userId})
    }
    @Test fun actualDepartureTransfersDirectoryAuthorityAndFormerOwnerLosesAccess() {
        assertIs<CommunityDepartureResult.Departed>(JdbcCommunityMembershipRepository(source).leave(owner,id(),community,members.first(),now={102}))
        assertEquals(CommunityMembersResult.Denied,directory.list(owner,community,null,20))
        val current=assertIs<CommunityMembersResult.Listed>(directory.list(members.first(),community,null,20))
        assertEquals(members.first(),current.ownerId);assertEquals(members,current.items.map {it.userId})
    }
    @Test fun unknownForeignMemberDeletedOwnerAndDeletedServerAllDenyRead() {
        for((actor,server) in listOf(owner to foreign,owner to id(),stranger to community,members.first() to community))
            assertEquals(CommunityMembersResult.Denied,directory.list(actor,server,null,20))
        sql("UPDATE users SET deleted_at=102 WHERE id='$owner'")
        assertEquals(CommunityMembersResult.Denied,directory.list(owner,community,null,20))
        sql("UPDATE users SET deleted_at=NULL WHERE id='$owner'")
        sql("UPDATE nearby_communities SET state='DELETED' WHERE id='$community'")
        assertEquals(CommunityMembersResult.Denied,directory.list(owner,community,null,20))
    }
    @Test fun earnedPageDoesNotGrantDepartureWithNowIneligibleSuccessor() {
        val page=assertIs<CommunityMembersResult.Listed>(directory.list(owner,community,null,20))
        assertTrue(page.items.any {it.userId==members.first()})
        sql("INSERT INTO nearby_community_bans(community_id,user_id,created_at) VALUES ('$community','${members.first()}',102)")
        val revision=JdbcRosterJournal(source).revision()
        assertEquals(CommunityDepartureResult.InvalidSuccessor,JdbcCommunityMembershipRepository(source).leave(owner,id(),community,members.first(),now={103}))
        assertEquals(owner,assertIs<CommunityMembersResult.Listed>(directory.list(owner,community,null,20)).ownerId)
        assertEquals(5,count("nearby_community_members"));assertEquals(revision,JdbcRosterJournal(source).revision())
    }
    @Test fun noncanonicalIdsAndUnboundedLimitsRejectBeforeDatabaseRead() {
        for(limit in listOf(-1,0,51,Int.MAX_VALUE)) assertFailsWith<IllegalArgumentException> {directory.list(owner,community,null,limit)}
        assertFailsWith<IllegalArgumentException> {directory.list(owner.uppercase(),community,null,20)}
        assertFailsWith<IllegalArgumentException> {directory.list(owner,community,"bad",20)}
    }
    private fun sql(query:String)=source.connection.use {c -> c.createStatement().use {it.execute(query)}}
    private fun count(table:String)=source.connection.use {c -> c.createStatement().use {it.executeQuery("SELECT count(*) FROM $table").use {rows -> check(rows.next());rows.getInt(1)}}}
    private fun schema():DataSource {
        val name="session_members_"+id().replace("-","")
        NearbyPostgresFixture.source().connection.use {c -> c.createStatement().use {it.execute("CREATE SCHEMA $name")}}
        return NearbyPostgresFixture.source(name)
    }
    private fun id()=UUID.randomUUID().toString()
}
