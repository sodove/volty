package ru.sodovaya.volty.backend

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import ru.sodovaya.volty.backend.nearby.*
import kotlin.test.*

class NearbyLegacyWriterBoundaryTest {
    private val actor="10000000-0000-0000-0000-000000000001"
    private val group="20000000-0000-0000-0000-000000000002"
    private val session="30000000-0000-0000-0000-000000000003"
    private val generation="40000000-0000-0000-0000-000000000004"
    private val writers=listOf(
        HttpMethod.Post to "/v1/sharing/start",
        HttpMethod.Post to "/v1/groups/$group/sharing",
        HttpMethod.Post to "/v1/groups/$group/sharing/renew",
        HttpMethod.Post to "/v1/groups/$group/sharing/update",
        HttpMethod.Post to "/v1/groups/$group/sharing/stop",
        HttpMethod.Delete to "/v1/groups/$group/sharing",
        HttpMethod.Post to "/v1/groups/$group/voice/join",
        HttpMethod.Post to "/v1/groups/$group/voice/leave",
    )
    private inner class Store : BackendStore {
        var legacyAdmissions=0
        override fun findUserById(id: String)=UserRecord(actor,"rider@example.invalid","ignored","Rider",true).takeIf {id==actor}
        override fun isAccessActive(userId: String,issuedAtEpochSeconds: Long)=userId==actor
        override fun isGroupMember(userId: String,groupId: String): Boolean {legacyAdmissions++;return true}
        override fun listFriends(userId: String)=emptyList<FriendSummaryDto>()
        override fun listGroups(userId: String)=emptyList<GroupDto>()
    }
    private inner class Consent : ChannelSharingEndpointPort {
        var calls=0
        var current: ChannelSharingDecision?=null
        override fun start(actor: String,session: String,generation: String,mode: ChannelSharingMode,ttl: ChannelSharingTtl): ChannelSharingDecision {
            calls++;return ChannelSharingDecision(ChannelSharingConsent(actor,session,generation,ChannelSharingPhase.ACTIVE,mode,100_000),5000).also {current=it}
        }
        override fun status(actor: String,session: String,generation: String): ChannelSharingDecision? {calls++;return current}
        override fun revoke(actor: String,session: String,generation: String): ChannelSharingDecision? {calls++;current=null;return null}
    }

    @Test fun oldWritersRejectBeforeBodyOrAdmissionAndCannotChangeStartedV2Consent() = testApplication {
        val store=Store();val consent=Consent()
        val deps=AppDependencies(AppConfig.forTests(),store,testMode=true,nearbyChannelSharing=consent,nearbyCompatibility=NearbyCompatibility.VOICE_SPACES)
        application {module(deps)}
        val token=deps.tokenService.issueAccessToken(actor);val path="/v2/nearby/sharing/$session/$generation"
        assertEquals(HttpStatusCode.OK,client.post("$path/start") {bearerAuth(token);contentType(ContentType.Application.Json);setBody("""{"mode":"LOCATION","ttl":"HALF_HOUR"}""")}.status)
        val original=assertNotNull(consent.current)
        for((method,path) in writers) {
            val response=client.request(path) {this.method=method;bearerAuth(token);contentType(ContentType.Application.Json);setBody("malformed delayed old body")}
            assertEquals(HttpStatusCode.Conflict,response.status,path)
            assertContains(response.bodyAsText(),"client_upgrade_required")
            assertSame(original,consent.current)
        }
        assertEquals(1,consent.calls);assertEquals(0,store.legacyAdmissions)
        assertEquals(HttpStatusCode.OK,client.get(path) {bearerAuth(token)}.status)
        assertSame(original,consent.current);assertEquals(2,consent.calls)
    }

    @Test fun upgradeBoundaryPreservesJwtAuthenticationBeforeAnyUpgradeResponse() = testApplication {
        val store=Store();val deps=AppDependencies(AppConfig.forTests(),store,testMode=true,nearbyCompatibility=NearbyCompatibility.VOICE_SPACES)
        application {module(deps)}
        for((method,path) in writers) {
            assertEquals(HttpStatusCode.Unauthorized,client.request(path) {this.method=method;setBody("malformed")}.status)
        }
        assertEquals(0,store.legacyAdmissions)
    }

    @Test fun upgradeBoundaryPreservesFriendsProfileGroupReadsAndProviderDiscovery() = testApplication {
        val store=Store();val deps=AppDependencies(AppConfig.forTests(),store,testMode=true,nearbyCompatibility=NearbyCompatibility.VOICE_SPACES)
        application {module(deps)}
        val token=deps.tokenService.issueAccessToken(actor)
        for(path in listOf("/v1/profile","/v1/friends","/v1/groups","/v1/voice/provider")) {
            assertEquals(HttpStatusCode.OK,client.get(path) {bearerAuth(token)}.status,path)
        }
        assertEquals(0,store.legacyAdmissions)
    }

    @Test fun injectingOneV2EndpointDoesNotSilentlyActivateLegacyCutover() = testApplication {
        val store=Store();val consent=Consent()
        val deps=AppDependencies(AppConfig.forTests(),store,testMode=true,nearbyChannelSharing=consent)
        application {module(deps)}
        assertEquals(NearbyCompatibility.LEGACY,deps.nearbyCompatibility)
        val response=client.post("/v1/groups/$group/voice/leave") {bearerAuth(deps.tokenService.issueAccessToken(actor))}
        assertEquals(HttpStatusCode.OK,response.status)
        assertContains(response.bodyAsText(),"true")
        assertEquals(0,consent.calls)
    }
}
