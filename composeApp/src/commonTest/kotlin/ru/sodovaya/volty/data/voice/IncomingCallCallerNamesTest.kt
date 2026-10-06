package ru.sodovaya.volty.data.voice

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.get
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import ru.sodovaya.volty.data.social.SocialAuthenticatedAccount
import ru.sodovaya.volty.domain.social.SocialUserId
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class IncomingCallCallerNamesTest {
    private val actor="10000000-0000-0000-0000-000000000001"
    private val caller="20000000-0000-0000-0000-000000000002"
    private fun row(name:String="Rider",state:String="ACCEPTED",id:String=caller)=
        """{"friendshipId":"30000000-0000-0000-0000-000000000003","userId":"$id","displayName":"$name","state":"$state"}"""

    @Test fun lookupUsesOnlyOriginalBearerAndOneUnambiguousAcceptedCaller() = runTest {
        val requests=mutableListOf<HttpRequestData>();var response="[${row("Саша")}]"
        val client=HttpClient(MockEngine) {engine {dispatcher=StandardTestDispatcher(testScheduler);addHandler {requests+=it;respond(response,headers=headersOf(HttpHeaders.ContentType,"application/json"))}}}
        val job=SupervisorJob(backgroundScope.coroutineContext[Job]);var token="original"
        val account=SocialAuthenticatedAccount(SocialUserId(actor),job,backgroundScope.coroutineContext) {token}
        val lookup=IncomingCallCallerNameLookup(account,client,"https://example.test/v1")
        try {
            assertEquals("Саша",lookup.lookup(caller));assertTrue(lookup.belongsTo(account))
            assertEquals("https://example.test/v1/friends",requests.single().url.toString())
            assertEquals("Bearer original",requests.single().headers[HttpHeaders.Authorization]);assertEquals(HttpMethod.Get,requests.single().method)
            token="refreshed";assertEquals("Саша",lookup.lookup(caller));assertEquals("Bearer refreshed",requests.last().headers[HttpHeaders.Authorization])
            for(state in listOf("BLOCKED","REQUEST_SENT","REQUEST_RECEIVED")) {response="[${row(state=state)}]";assertNull(lookup.lookup(caller))}
            response="[${row()},${row("Other")}]";assertNull(lookup.lookup(caller))
            response="[${row(id=actor)}]";assertNull(lookup.lookup(caller))
            val before=requests.size;assertNull(lookup.lookup(actor));assertNull(lookup.lookup("../caller"));assertEquals(before,requests.size)
            lookup.close();assertNull(lookup.lookup(caller));assertEquals(before,requests.size);assertTrue(job.isActive)
        } finally {lookup.close();job.cancel();client.close()}
    }

    @Test fun malformedOversizedAndRedirectResponsesNeverEarnCallerName() = runTest {
        var body="[]";var status=HttpStatusCode.OK;val requests=mutableListOf<HttpRequestData>()
        val client=HttpClient(MockEngine) {engine {dispatcher=StandardTestDispatcher(testScheduler);addHandler {requests+=it;respond(body,status,headersOf(HttpHeaders.Location,"https://elsewhere.test/friends"))}}}
        val job=SupervisorJob(backgroundScope.coroutineContext[Job])
        val account=SocialAuthenticatedAccount(SocialUserId(actor),job,backgroundScope.coroutineContext) {"original"}
        val lookup=IncomingCallCallerNameLookup(account,client,"https://example.test/v1")
        try {
            for(value in listOf("{}","[{]","[${row(" ")}]","[${row("x".repeat(81))}]","[${row("bad\\nname")}]"," ".repeat(65*1024),"[${row()}]")) {
                body=value;status=if(value=="[${row()}]") HttpStatusCode.Found else HttpStatusCode.OK
                val before=requests.size
                assertNull(lookup.lookup(caller),"Invalid metadata must not label caller")
                assertEquals(before+1,requests.size);assertEquals("example.test",requests.last().url.host)
            }
            for(code in listOf(HttpStatusCode.Unauthorized,HttpStatusCode.Forbidden,HttpStatusCode.ServiceUnavailable)) {
                status=code;body="[${row()}]";assertNull(lookup.lookup(caller))
            }
            assertTrue(job.isActive)
        } finally {lookup.close();job.cancel();client.close()}
    }

    @Test fun optionalTimeoutAndLookupClosePreserveBorrowedAccountAndSharedClient() = runTest {
        var slow=true;var issued=0
        val client=HttpClient(MockEngine) {engine {dispatcher=StandardTestDispatcher(testScheduler);addHandler {
            issued++;if(slow) delay(3_000L);respond("[${row()}]")
        }}}
        val job=SupervisorJob(backgroundScope.coroutineContext[Job])
        val account=SocialAuthenticatedAccount(SocialUserId(actor),job,backgroundScope.coroutineContext) {"original"}
        val lookup=IncomingCallCallerNameLookup(account,client,"https://example.test/v1")
        try {
            val pending=async {lookup.lookup(caller)};runCurrent();assertEquals(1,issued)
            advanceTimeBy(2_001L);runCurrent();assertTrue(pending.isCompleted);assertNull(pending.await())
            assertTrue(job.isActive);slow=false;assertEquals("Rider",lookup.lookup(caller))
            lookup.close();assertEquals(HttpStatusCode.OK,client.get("https://example.test/health").status)
            assertEquals(3,issued);assertTrue(job.isActive)
        } finally {lookup.close();job.cancel();client.close()}
    }

    @Test fun heldOldAccountReplyCannotLabelSameUserReplacement() = runTest {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val requests=mutableListOf<HttpRequestData>()
        val client=HttpClient(MockEngine) {engine {dispatcher=StandardTestDispatcher(testScheduler);addHandler {requests+=it;withContext(NonCancellable) {entered.complete(Unit);release.await()};respond("[${row()}]")}}}
        val oldJob=SupervisorJob(backgroundScope.coroutineContext[Job]);val freshJob=SupervisorJob(backgroundScope.coroutineContext[Job])
        val old=SocialAuthenticatedAccount(SocialUserId(actor),oldJob,backgroundScope.coroutineContext) {"original"}
        val fresh=SocialAuthenticatedAccount(SocialUserId(actor),freshJob,backgroundScope.coroutineContext) {"replacement"}
        val lookup=IncomingCallCallerNameLookup(old,client,"https://example.test/v1")
        try {
            val pending=async {lookup.lookup(caller)};runCurrent();assertTrue(entered.isCompleted)
            oldJob.cancel();release.complete(Unit);runCurrent();assertNull(pending.await())
            assertFalse(lookup.belongsTo(fresh));assertNull(lookup.lookup(caller));assertEquals(1,requests.size)
            assertEquals("Bearer original",requests.single().headers[HttpHeaders.Authorization]);assertTrue(freshJob.isActive)
        } finally {release.complete(Unit);lookup.close();oldJob.cancel();freshJob.cancel();client.close()}
    }
}
