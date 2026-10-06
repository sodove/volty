package ru.sodovaya.volty.data.nearby

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import ru.sodovaya.volty.domain.nearby.*
import ru.sodovaya.volty.domain.social.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ChannelSharingPacketHttpPortTest {
    private val sid="10000000-0000-0000-0000-000000000001"
    private val gen="20000000-0000-0000-0000-000000000002"
    private val channel="30000000-0000-0000-0000-000000000003"
    private val actor="40000000-0000-0000-0000-000000000004"
    private val tag=ChannelSharingTag(sid,gen)
    private val packet=ChannelSharingPacket(7,1000,null)
    private fun ack(session: String=sid, generation: String=gen, sequence: Long=7, credit: String="1000") =
        """{"sessionId":"$session","generation":"$generation","sequence":$sequence,"remainingMillis":$credit}"""
    private fun peer(mode: String="LOCATION", telemetry: String="null", captured: Long=1000, credit: String="1000") =
        """{"actorId":"$actor","sessionId":"$sid","generation":"$gen","mode":"$mode","packet":{"sequence":7,"capturedAtEpochMillis":$captured,"location":null,"telemetry":$telemetry},"remainingMillis":$credit}"""
    private fun audience(peers: String=peer(), destination: String=channel, observed: Long=1100) =
        """{"channelId":"$destination","serverTimeEpochMillis":$observed,"peers":[$peers]}"""
    private fun TestScope.client(handler: suspend MockRequestHandleScope.(HttpRequestData)->HttpResponseData)=
        HttpClient(MockEngine) {engine {dispatcher=StandardTestDispatcher(testScheduler); addHandler(handler)}}

    @Test fun actualPublicationAndAudienceBindOriginalRequestAndMonotonicCredit()=runTest {
        var clock=100L; val paths=mutableListOf<String>()
        val http=client {request -> paths+=request.url.encodedPath; assertEquals("Bearer original",request.headers[HttpHeaders.Authorization]); clock=400
            if(request.method==HttpMethod.Post) {assertTrue(assertIs<TextContent>(request.body).text.contains("\"sequence\":7")); respond(ack())} else respond(audience())}
        val port=ChannelSharingPacketHttpPort(http,"https://api.test/v2/nearby",{"original"},{clock})
        try {
            val publication=assertIs<ChannelSharingPacketReply.Published>(port.publish(tag,ChannelSharingMode.LOCATION,packet))
            assertEquals(1100,publication.deadlineMillis); assertEquals(0,publication.requestOrdinal)
            clock=100
            val result=assertIs<ChannelSharingPacketReply.Audience>(port.audience(channel,sid))
            assertEquals(1,result.requestOrdinal); assertEquals(1100,result.peers.single().deadlineMillis)
            assertEquals(listOf("/v2/nearby/sharing/$sid/$gen/packet","/v2/nearby/channel-sessions/$sid/sharing/audience"),paths)
        } finally {port.close();http.close()}
    }
    @Test fun audienceReadsOptionalNameWithoutChangingPacketOrCredit()=runTest {
        var response=audience(peer().dropLast(1)+",\"displayName\":\"Саша\"}");var issued=0
        val http=client {issued++;respond(response)}
        val port=ChannelSharingPacketHttpPort(http,"https://api.test/v2/nearby",{"original"},{100})
        try {
            val named=assertIs<ChannelSharingPacketReply.Audience>(port.audience(channel,sid)).peers.single()
            assertEquals("Саша",named.displayName);assertEquals(1100,named.deadlineMillis)
            for(raw in listOf("null","false","{}","\" \"", "\""+"x".repeat(81)+"\"","\"bad\\nname\"")) {
                response=audience(peer().dropLast(1)+",\"displayName\":$raw}")
                val fallback=assertIs<ChannelSharingPacketReply.Audience>(port.audience(channel,sid)).peers.single()
                assertNull(fallback.displayName);assertEquals(named.packet,fallback.packet);assertEquals(named.deadlineMillis,fallback.deadlineMillis)
            }
            response=audience();assertNull(assertIs<ChannelSharingPacketReply.Audience>(port.audience(channel,sid)).peers.single().displayName)
            assertEquals(8,issued)
        } finally {port.close();http.close()}
    }
    @Test fun misboundAcknowledgementChannelDuplicateAndIncoherentEvidenceFailClosed()=runTest {
        for (body in listOf(ack(session=gen),ack(generation=sid),ack(sequence=8),ack(credit="0"),ack(credit="15001"),ack(credit="\"1000\""))) {
            val http=client {respond(body)};val port=ChannelSharingPacketHttpPort(http,"https://api.test/v2/nearby",{"token"},{100})
            try {assertEquals(ChannelSharingFailure.INVALID_RESPONSE,assertIs<ChannelSharingPacketReply.Failed>(port.publish(tag,ChannelSharingMode.LOCATION,packet)).reason)} finally {port.close();http.close()}
        }
        for(body in listOf(audience(destination=sid),audience(peer()+","+peer()),audience(peer(captured=1101)),audience(peer(credit="15001")),audience(peer(credit="0")),audience(peer(mode="FULL")))) {
            val http=client {respond(body)};val port=ChannelSharingPacketHttpPort(http,"https://api.test/v2/nearby",{"token"},{100})
            try {assertEquals(ChannelSharingFailure.INVALID_RESPONSE,assertIs<ChannelSharingPacketReply.Failed>(port.audience(channel,sid)).reason,body)} finally {port.close();http.close()}
        }
    }
    @Test fun earnedUnknownValuesAndRidePrivacyAreOwnedByReaderAndWriter()=runTest {
        val telemetry="""{"profile":"RIDE","powerW":{"supported":true,"known":false,"value":4200},"packVoltageV":{"supported":true,"known":true,"value":99}}"""
        var response=audience(peer("RIDE",telemetry)); var body=""
        val http=client {if(it.method==HttpMethod.Post) {body=assertIs<TextContent>(it.body).text;respond(ack())} else respond(response)}
        val port=ChannelSharingPacketHttpPort(http,"https://api.test/v2/nearby",{"token"},{100})
        try {
            val received=assertIs<ChannelSharingPacketReply.Audience>(port.audience(channel,sid)).peers.single().packet.telemetry!!
            assertNull(received.powerW.value);assertFalse(received.packVoltageV.supported)
            val own=SharedTelemetry(profile=TelemetryShareProfile.RIDE,powerW=TelemetryNumber(true,false,4200.0),packVoltageV=TelemetryNumber.known(99.0))
            assertIs<ChannelSharingPacketReply.Published>(port.publish(tag,ChannelSharingMode.RIDE,packet.copy(telemetry=own)))
            assertFalse(body.contains("4200"));assertFalse(body.contains("99.0"))
            response=audience(peer("LOCATION",telemetry))
            assertEquals(ChannelSharingFailure.INVALID_RESPONSE,assertIs<ChannelSharingPacketReply.Failed>(port.audience(channel,sid)).reason)
        } finally {port.close();http.close()}
    }
    @Test fun delayedEvidenceDoesNotRenewSampleAndExpiredPeersDisappear()=runTest {
        for((start,receipt,failure) in listOf(Triple(100L,1100L,ChannelSharingFailure.EXPIRED),Triple(100L,99L,ChannelSharingFailure.INVALID_RESPONSE),Triple(Long.MAX_VALUE-100,Long.MAX_VALUE-50,ChannelSharingFailure.INVALID_RESPONSE))) {
            var clock=start;val http=client {clock=receipt;respond(ack())};val port=ChannelSharingPacketHttpPort(http,"https://api.test/v2/nearby",{"token"},{clock})
            try {assertEquals(failure,assertIs<ChannelSharingPacketReply.Failed>(port.publish(tag,ChannelSharingMode.LOCATION,packet)).reason)} finally {port.close();http.close()}
        }
        var clock=100L;val http=client {clock=1100;respond(audience())};val port=ChannelSharingPacketHttpPort(http,"https://api.test/v2/nearby",{"token"},{clock})
        try {assertTrue(assertIs<ChannelSharingPacketReply.Audience>(port.audience(channel,sid)).peers.isEmpty())} finally {port.close();http.close()}
    }
    @Test fun cancelledBearerAndClosedChildNeverIssueLateHttpOrCloseBorrowedParent()=runTest {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();var calls=0
        val http=client {calls++;respond(ack())};val port=ChannelSharingPacketHttpPort(http,"https://api.test/v2/nearby",{withContext(NonCancellable) {entered.complete(Unit);release.await()};"token"},{100})
        try {
            val pending=async {port.publish(tag,ChannelSharingMode.LOCATION,packet)};runCurrent();assertTrue(entered.isCompleted)
            pending.cancel();release.complete(Unit);runCurrent();assertTrue(pending.isCancelled);assertEquals(0,calls)
            port.close();assertEquals(ChannelSharingFailure.RETIRED,assertIs<ChannelSharingPacketReply.Failed>(port.audience(channel,sid)).reason)
            assertEquals(0,calls);assertEquals(HttpStatusCode.OK,http.get("https://api.test/parent").status)
        } finally {release.complete(Unit);port.close();http.close()}
    }
    @Test fun boundedResponsesStrictUtf8AndRedirects()=runTest {
        for(kind in 0..2) {
            var calls=0;val http=client {calls++;when(kind) {0->respond("x".repeat(262145));1->respond(byteArrayOf(0xC3.toByte(),0x28));else->respond("",HttpStatusCode.Found,headersOf(HttpHeaders.Location,"https://other.test"))}}
            val port=ChannelSharingPacketHttpPort(http,"https://api.test/v2/nearby",{"token"},{100})
            try {assertEquals(if(kind==2) ChannelSharingFailure.UNAVAILABLE else ChannelSharingFailure.INVALID_RESPONSE,assertIs<ChannelSharingPacketReply.Failed>(port.audience(channel,sid)).reason);assertEquals(1,calls)} finally {port.close();http.close()}
        }
    }
    @Test fun emptyAudienceIsValidAndLocalInvalidCaptureIssuesNoHttp()=runTest {
        var calls=0
        val http=client {calls++;respond(audience(peers=""))}
        val port=ChannelSharingPacketHttpPort(http,"https://api.test/v2/nearby",{"token"},{100})
        try {
            assertTrue(assertIs<ChannelSharingPacketReply.Audience>(port.audience(channel,sid)).peers.isEmpty())
            for(invalid in listOf(packet.copy(sequence=-1),packet.copy(capturedAtEpochMillis=-1),packet.copy(location=LocationSnapshot(0.0,0.0,0.0,1001,2000)))) {
                assertEquals(ChannelSharingFailure.INVALID_RESPONSE,assertIs<ChannelSharingPacketReply.Failed>(port.publish(tag,ChannelSharingMode.LOCATION,invalid)).reason)
            }
            assertEquals(ChannelSharingFailure.INVALID_RESPONSE,assertIs<ChannelSharingPacketReply.Failed>(port.audience("bad",sid)).reason)
            assertEquals(1,calls)
        } finally {port.close();http.close()}
    }
    @Test fun heldResponseTimesOutAndMissingBearerNeverIssuesHttp()=runTest {
        var calls=0
        val http=client {calls++;delay(11_000);respond(ack())}
        val missing=ChannelSharingPacketHttpPort(http,"https://api.test/v2/nearby",{null},{100})
        val port=ChannelSharingPacketHttpPort(http,"https://api.test/v2/nearby",{"token"},{testScheduler.currentTime})
        try {
            assertEquals(ChannelSharingFailure.UNAUTHORIZED,assertIs<ChannelSharingPacketReply.Failed>(missing.publish(tag,ChannelSharingMode.LOCATION,packet)).reason)
            assertEquals(0,calls)
            assertEquals(ChannelSharingFailure.UNAVAILABLE,assertIs<ChannelSharingPacketReply.Failed>(port.publish(tag,ChannelSharingMode.LOCATION,packet)).reason)
            assertEquals(1,calls)
        } finally {missing.close();port.close();http.close()}
    }
}
