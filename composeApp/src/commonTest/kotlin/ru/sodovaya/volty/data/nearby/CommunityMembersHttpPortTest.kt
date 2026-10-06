package ru.sodovaya.volty.data.nearby

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import ru.sodovaya.volty.domain.nearby.community.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class CommunityMembersHttpPortTest {
    private val actor="10000000-0000-0000-0000-000000000001"
    private val community="20000000-0000-0000-0000-000000000002"
    private val first="30000000-0000-0000-0000-000000000003"
    private val second="40000000-0000-0000-0000-000000000004"
    private fun row(id:String=second)="""{"userId":"$id","displayName":"Участник","joinedAtMillis":100}"""
    private fun page(vararg rows:String)="""{"communityId":"$community","ownerId":"$actor","items":[${rows.joinToString(",")}]}"""
    private fun TestScope.client(handler:suspend MockRequestHandleScope.(HttpRequestData)->HttpResponseData)=HttpClient(MockEngine) {
        engine {dispatcher=StandardTestDispatcher(testScheduler);addHandler(handler)}
    }
    private fun port(client:HttpClient)=CommunityMembersHttpPort(client,"https://example.test/v2/nearby",actor) {"original"}
    @Test fun requestUsesOriginalBearerAndEarnedCursorWithoutMembershipOrVoiceMutation()=runTest {
        val requests=mutableListOf<HttpRequestData>();val client=client {requests+=it;respond(page(row()))};val port=port(client)
        try {
            val result=assertIs<CommunityResult.Ready<CommunityMembersPage>>(port.list(community,first,2)).value
            assertEquals(CommunityMembersPage(community,actor,listOf(CommunityMemberSummary(second,"Участник",100))),result)
            val request=requests.single();assertEquals(HttpMethod.Get,request.method)
            assertEquals("/v2/nearby/communities/$community/members",request.url.encodedPath)
            assertEquals(first,request.url.parameters["after"]);assertEquals("2",request.url.parameters["limit"])
            assertEquals("Bearer original",request.headers[HttpHeaders.Authorization])
        } finally {port.close();client.close()}
    }
    @Test fun foreignOwnerCommunityDuplicateUnorderedAndInvalidRowsNeverBecomeCandidates()=runTest {
        val cases=listOf(Triple(page(row()).replace("\"ownerId\":\"$actor\"","\"ownerId\":\"$first\""),2,null),
            Triple(page(row()).replace(community,first),2,null),Triple(page(row(),row()),2,null),
            Triple(page(row(second),row(first)),2,null),Triple(page(row(first)),2,first),
            Triple(page(row(first),row(second)),1,null),Triple(page(row("bad")),2,null),
            Triple(page(row().replace("\"joinedAtMillis\":100","\"joinedAtMillis\":-1")),2,null),
            Triple(page(row().replace("\"joinedAtMillis\":100","\"joinedAtMillis\":\"100\"")),2,null),
            Triple(page(row().replace("Участник"," ")),2,null),Triple(page(row().replace("Участник","A".repeat(81))),2,null))
        var issued=0;val client=client {respond(cases[issued++].first)};val port=port(client)
        try {
            for((_,limit,after) in cases) assertEquals(CommunityResult.Failed(CommunityFailure.INVALID_RESPONSE),port.list(community,after,limit))
            assertEquals(cases.size,issued)
        } finally {port.close();client.close()}
    }
    @Test fun invalidInputAuthDenialAndRedirectNeverIssueAnotherRead()=runTest {
        val statuses=listOf(HttpStatusCode.Unauthorized,HttpStatusCode.NotFound,HttpStatusCode.Forbidden,HttpStatusCode.Found)
        var issued=0;val client=client {respond("",statuses[issued++],headersOf(HttpHeaders.Location,"https://other.test"))};val port=port(client)
        try {
            assertFailsWith<IllegalArgumentException> {port.list(community,"bad",20)}
            assertFailsWith<IllegalArgumentException> {port.list(community,null,51)}
            assertEquals(0,issued)
            for(reason in listOf(CommunityFailure.UNAUTHORIZED,CommunityFailure.ACCESS_DENIED,CommunityFailure.ACCESS_DENIED,CommunityFailure.UNAVAILABLE))
                assertEquals(CommunityResult.Failed(reason),port.list(community))
            assertEquals(4,issued)
        } finally {port.close();client.close()}
    }
    @Test fun maximumEscapedNamesPageIsBoundedButNotTruncatedBySmallerAdaptersLimit()=runTest {
        val rows=(1..50).map {index -> row("50000000-0000-0000-0000-${index.toString().padStart(12,'0')}").replace("Участник","\\u0000".repeat(80))}
        val body=page(*rows.toTypedArray());assertTrue(body.encodeToByteArray().size>16*1024)
        val client=client {respond(body)};val port=port(client)
        try {
            val result=assertIs<CommunityResult.Ready<CommunityMembersPage>>(port.list(community,null,50)).value
            assertEquals(50,result.items.size);assertTrue(result.items.all {it.displayName.length==80})
        } finally {port.close();client.close()}
    }
    @Test fun finiteIssuedTimeoutAndCallerCancellationNeverRetryRead()=runTest {
        var issued=0;val entered=CompletableDeferred<Unit>();var cancelled=false
        val client=client {
            if(issued++==0) {delay(10_001);respond(page(row()))}
            else {entered.complete(Unit);try {awaitCancellation()} finally {cancelled=true}}
        };val port=port(client)
        try {
            assertEquals(CommunityResult.Failed(CommunityFailure.UNAVAILABLE),port.list(community))
            val read=launch {port.list(community)};entered.await();read.cancelAndJoin();runCurrent()
            assertTrue(cancelled);assertEquals(2,issued)
        } finally {port.close();client.close()}
    }
    @Test fun oversizedAndMalformedUtf8CannotBecomeMemberPage()=runTest {
        val malformed=(page(row()).dropLast(1)+",\"ignored\":\"").encodeToByteArray()+byteArrayOf(0xc3.toByte(),0x28)+"\"}".encodeToByteArray()
        val bodies=listOf(ByteArray(32*1024+1) {120},malformed)
        var issued=0;val client=client {respond(bodies[issued++])};val port=port(client)
        try {
            repeat(2) {assertEquals(CommunityResult.Failed(CommunityFailure.INVALID_RESPONSE),port.list(community))}
            assertEquals(2,issued)
        } finally {port.close();client.close()}
    }
}
