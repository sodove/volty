package ru.sodovaya.volty.backend.nearby

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import ru.sodovaya.volty.backend.*
import java.util.UUID
import kotlin.test.*

class ChannelSharingPacketRoutesTest {
    private fun id() = UUID.randomUUID().toString()
    private val actor = id(); private val session = id(); private val generation = id(); private val channel = id()
    private val publication = "/v2/nearby/sharing/$session/$generation/packet"
    private val audience = "/v2/nearby/channel-sessions/$session/sharing/audience"
    private val packet = ChannelSharingPacket(7, 1000, null)
    private fun deps(port: ChannelSharingPacketEndpointPort? = null) = AppDependencies(AppConfig.forTests(), object : BackendStore {
        override fun findUserById(id: String) = UserRecord(actor, "test@example.invalid", "ignored", "Test", true).takeIf {id == actor}
        override fun isAccessActive(userId: String, issuedAtEpochSeconds: Long) = userId == actor
    }, testMode = true, nearbySharingPackets = port)
    private inner class Port : ChannelSharingPacketEndpointPort {
        val calls = mutableListOf<String>()
        var acknowledgement: ChannelSharingPacketAcknowledgement? = ChannelSharingPacketAcknowledgement(session, generation, 7, 500)
        var snapshot: ChannelSharingAudience? = ChannelSharingAudience(channel, 1100, listOf(
            ChannelSharingAudiencePeer(actor, session, generation, ChannelSharingMode.LOCATION, packet, 1600)))
        override fun publish(actor: String, session: String, generation: String, packet: ChannelSharingPacket): ChannelSharingPacketAcknowledgement? {
            calls += "publish:$actor:$session:$generation:${packet.sequence}"; return acknowledgement
        }
        override fun mapAudience(actor:String,session:String):ChannelSharingAudience? {calls += "map:$actor:$session";return snapshot}
        override fun audience(actor: String, session: String): ChannelSharingAudience? {calls += "audience:$actor:$session"; return snapshot}
    }
    @Test fun optInMapUsesCurrentAuthorityAndDowngradesExpiredSampleToLocationOnly() = testApplication {
        val port=Port();val deps=deps(port);application {module(deps)};val token=deps.tokenService.issueAccessToken(actor)
        val location=LocationDto(55.0,60.0,4.0,1000,16000)
        val stale=packet.copy(location=location,telemetry=SharedTelemetryDto(profile="RIDE",powerW=TelemetryNumberDto(true,true,4200.0)))
        port.snapshot=ChannelSharingAudience(channel,21000,listOf(ChannelSharingAudiencePeer(actor,session,generation,ChannelSharingMode.RIDE,stale,26000)))
        val response=client.get(audience+"?lastKnown=true") {bearerAuth(token)}
        assertEquals(HttpStatusCode.OK,response.status)
        val body=response.bodyAsText();assertTrue(body.contains("LOCATION"));assertFalse(body.contains("4200"));assertTrue(body.contains("55.0"))
        assertEquals(listOf("map:$actor:$session"),port.calls)
        assertEquals(HttpStatusCode.ServiceUnavailable,client.get(audience) {bearerAuth(token)}.status)
        port.snapshot=port.snapshot!!.copy(peers=port.snapshot!!.peers.map {it.copy(deadlineEpochMillis=91001)})
        assertEquals(HttpStatusCode.ServiceUnavailable,client.get(audience+"?lastKnown=true") {bearerAuth(token)}.status)
    }
    @Test fun authenticationAndDisabledPorts() = testApplication {
        val deps = deps(); application {module(deps)}
        assertEquals(HttpStatusCode.Unauthorized, client.post(publication).status)
        assertEquals(HttpStatusCode.Unauthorized, client.get(audience).status)
        val token = deps.tokenService.issueAccessToken(actor)
        assertEquals(HttpStatusCode.ServiceUnavailable, client.post(publication) {bearerAuth(token)}.status)
        assertEquals(HttpStatusCode.ServiceUnavailable, client.get(audience) {bearerAuth(token)}.status)
    }
    @Test fun exactAuthenticatedPublicationAndAudienceWithFiniteCredit() = testApplication {
        val port = Port(); val deps = deps(port); application {module(deps)}
        val token = deps.tokenService.issueAccessToken(actor)
        val response = client.post(publication) {bearerAuth(token); contentType(ContentType.Application.Json); setBody(backendJson().encodeToString(ChannelSharingPacket.serializer(), packet))}
        assertEquals(HttpStatusCode.OK, response.status)
        assertFalse(response.bodyAsText().contains(actor)); assertFalse(response.bodyAsText().contains("deadline"))
        val peers = client.get(audience) {bearerAuth(token)}
        assertEquals(HttpStatusCode.OK, peers.status)
        assertTrue(peers.bodyAsText().contains("\"remainingMillis\":500"))
        assertFalse(peers.bodyAsText().contains("deadlineEpochMillis"))
        assertEquals(listOf("publish:$actor:$session:$generation:7", "audience:$actor:$session"), port.calls)
    }
    @Test fun audienceNamesAreOptionalBoundedMetadataWithOriginalCredit() = testApplication {
        val port=Port();val deps=deps(port);application {module(deps)};val token=deps.tokenService.issueAccessToken(actor)
        val original=assertNotNull(port.snapshot);val peer=original.peers.single()
        for(name in listOf("Саша",null," ","x".repeat(81),"bad\nname")) {
            port.snapshot=original.copy(peers=listOf(peer.copy(displayName=name)))
            val response=client.get(audience) {bearerAuth(token)};assertEquals(HttpStatusCode.OK,response.status)
            val decoded=kotlinx.serialization.json.Json.parseToJsonElement(response.bodyAsText()) as kotlinx.serialization.json.JsonObject
            val row=(decoded["peers"] as kotlinx.serialization.json.JsonArray).single() as kotlinx.serialization.json.JsonObject
            assertEquals("500",row["remainingMillis"].toString())
            if(name=="Саша") assertEquals("\"Саша\"",row["displayName"].toString())
            else assertTrue(row["displayName"]==null || row["displayName"]==kotlinx.serialization.json.JsonNull)
        }
        assertEquals(5,port.calls.size)
    }
    @Test fun malformedCapturePathAndBoundedBodyNeverReachPort() = testApplication {
        val port = Port(); val deps = deps(port); application {module(deps)}; val token = deps.tokenService.issueAccessToken(actor)
        for (body in listOf("broken", """{"sequence":-1,"capturedAtEpochMillis":1000,"location":null}""", """{"sequence":0,"capturedAtEpochMillis":-1,"location":null}""",
            """{"sequence":0,"capturedAtEpochMillis":1000,"location":{"latitude":91,"longitude":0,"accuracyMeters":0,"capturedAtEpochMillis":1000,"staleAfterEpochMillis":2000}}""")) {
            assertEquals(HttpStatusCode.BadRequest, client.post(publication) {bearerAuth(token); contentType(ContentType.Application.Json); setBody(body)}.status)
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, client.post(publication) {bearerAuth(token); contentType(ContentType.Application.Json); setBody("x".repeat(16385))}.status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/v2/nearby/channel-sessions/bad/sharing/audience") {bearerAuth(token)}.status)
        assertTrue(port.calls.isEmpty())
    }
    @Test fun deniedAndMisboundAcknowledgements() = testApplication {
        val port = Port(); val deps = deps(port); application {module(deps)}; val token = deps.tokenService.issueAccessToken(actor)
        val valid = assertNotNull(port.acknowledgement)
        for (invalid in listOf(valid.copy(sessionId=id()), valid.copy(generation=id()), valid.copy(sequence=8), valid.copy(remainingMillis=0), valid.copy(remainingMillis=15001))) {
            port.acknowledgement=invalid
            assertEquals(HttpStatusCode.ServiceUnavailable, client.post(publication) {bearerAuth(token); contentType(ContentType.Application.Json); setBody(backendJson().encodeToString(ChannelSharingPacket.serializer(), packet))}.status)
        }
        port.acknowledgement=null; port.snapshot=null
        assertEquals(HttpStatusCode.NotFound, client.post(publication) {bearerAuth(token); contentType(ContentType.Application.Json); setBody(backendJson().encodeToString(ChannelSharingPacket.serializer(), packet))}.status)
        assertEquals(HttpStatusCode.NotFound, client.get(audience) {bearerAuth(token)}.status)
    }
    @Test fun corruptAudienceCannotInventFreshnessOrDuplicateParticipants() = testApplication {
        val port = Port(); val deps = deps(port); application {module(deps)}; val token = deps.tokenService.issueAccessToken(actor)
        val valid = assertNotNull(port.snapshot); val peer = valid.peers.single()
        for (invalid in listOf(valid.copy(channelId="bad"), valid.copy(serverTimeEpochMillis=-1), valid.copy(peers=listOf(peer,peer)),
            valid.copy(peers=listOf(peer.copy(actorId="bad"))), valid.copy(peers=listOf(peer.copy(deadlineEpochMillis=1100))),
            valid.copy(peers=listOf(peer.copy(deadlineEpochMillis=16001))), valid.copy(peers=listOf(peer.copy(packet=packet.copy(capturedAtEpochMillis=1200)))))) {
            port.snapshot=invalid; assertEquals(HttpStatusCode.ServiceUnavailable, client.get(audience) {bearerAuth(token)}.status)
        }
    }
    @Test fun actualConsentModeAndEarnedFlagsSanitizeIndependentPortOutput() = testApplication {
        val port = Port(); val deps = deps(port); application {module(deps)}; val token = deps.tokenService.issueAccessToken(actor)
        val peer = assertNotNull(port.snapshot).peers.single()
        val telemetry = SharedTelemetryDto(profile="RIDE", powerW=TelemetryNumberDto(true,false,4200.0), packVoltageV=TelemetryNumberDto(true,true,99.0))
        port.snapshot=port.snapshot!!.copy(peers=listOf(peer.copy(mode=ChannelSharingMode.RIDE, packet=packet.copy(telemetry=telemetry))))
        val response = client.get(audience) {bearerAuth(token)}
        assertEquals(HttpStatusCode.OK,response.status)
        val text = response.bodyAsText(); assertFalse(text.contains("4200")); assertFalse(text.contains("99.0"))
        port.snapshot=port.snapshot!!.copy(peers=listOf(peer.copy(mode=ChannelSharingMode.LOCATION, packet=packet.copy(telemetry=telemetry))))
        assertEquals(HttpStatusCode.ServiceUnavailable,client.get(audience) {bearerAuth(token)}.status)
    }
}
