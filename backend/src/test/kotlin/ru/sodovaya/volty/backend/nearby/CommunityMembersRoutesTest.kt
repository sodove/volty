package ru.sodovaya.volty.backend.nearby

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.*
import ru.sodovaya.volty.backend.*
import kotlin.test.*

class CommunityMembersRoutesTest {
    private val actor="10000000-0000-0000-0000-000000000001"
    private val community="20000000-0000-0000-0000-000000000002"
    private val member="abcdef00-0000-0000-0000-000000000003"
    private val base="/v2/nearby/communities/$community/members"
    private fun dependencies(port:CommunityMembersEndpointPort?=null)=AppDependencies(AppConfig.forTests(),object:BackendStore {
        override fun findUserById(id:String)=UserRecord(actor,"private@example.invalid","ignored","Owner",true).takeIf {id==actor}
        override fun isAccessActive(userId:String,issuedAtEpochSeconds:Long)=userId==actor
    },testMode=true,nearbyMembers=port)
    private inner class Recording:CommunityMembersEndpointPort {
        val calls=mutableListOf<String>()
        var result:CommunityMembersResult=CommunityMembersResult.Listed(community,actor,listOf(CommunityMember(member,"Участник",100)))
        override fun list(actor:String,community:String,after:String?,limit:Int):CommunityMembersResult {
            calls+="$actor:$community:$after:$limit";return result
        }
    }
    @Test fun authenticationAndAbsentDependencyFenceDirectory()=testApplication {
        val deps=dependencies();application {module(deps)}
        assertEquals(HttpStatusCode.Unauthorized,client.get(base).status)
        val response=client.get(base) {bearerAuth(deps.tokenService.issueAccessToken(actor))}
        assertEquals(HttpStatusCode.ServiceUnavailable,response.status);assertEquals("no-store",response.headers[HttpHeaders.CacheControl])
    }
    @Test fun originalActorAndCursorReturnMinimalProfileMetadata()=testApplication {
        val port=Recording();val deps=dependencies(port);application {module(deps)}
        val response=client.get("$base?after=$actor&limit=2") {bearerAuth(deps.tokenService.issueAccessToken(actor))}
        assertEquals(HttpStatusCode.OK,response.status);assertEquals("no-store",response.headers[HttpHeaders.CacheControl])
        val root=Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(setOf("communityId","ownerId","items"),root.keys)
        assertEquals(community,root.getValue("communityId").jsonPrimitive.content)
        assertEquals(actor,root.getValue("ownerId").jsonPrimitive.content)
        val row=root.getValue("items").jsonArray.single().jsonObject
        assertEquals(setOf("userId","displayName","joinedAtMillis"),row.keys)
        assertEquals("Участник",row.getValue("displayName").jsonPrimitive.content)
        assertEquals(listOf("$actor:$community:$actor:2"),port.calls)
    }
    @Test fun unknownAndForeignResourcesHaveSameGenericDenial()=testApplication {
        val port=Recording().apply {result=CommunityMembersResult.Denied};val deps=dependencies(port);application {module(deps)}
        for(server in listOf(community,member)) {
            val response=client.get("/v2/nearby/communities/$server/members") {bearerAuth(deps.tokenService.issueAccessToken(actor))}
            assertEquals(HttpStatusCode.NotFound,response.status)
            assertEquals("community_unavailable",Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("code").jsonPrimitive.content)
        }
        assertEquals(2,port.calls.size)
    }
    @Test fun malformedDuplicateAndUnboundedPageArgumentsNeverReadMembers()=testApplication {
        val port=Recording();val deps=dependencies(port);application {module(deps)}
        for(query in listOf("after=bad","after=${member.uppercase()}","after=$member&after=$member","limit=0","limit=51","limit=no","limit=1&limit=2"))
            assertEquals(HttpStatusCode.BadRequest,client.get("$base?$query") {bearerAuth(deps.tokenService.issueAccessToken(actor))}.status)
        assertTrue(port.calls.isEmpty())
    }
}
