package ru.sodovaya.volty.data.nearby

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import ru.sodovaya.volty.data.social.SocialAuthenticatedAccount
import ru.sodovaya.volty.domain.social.SocialUserId
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class AccountPushRegistrationTest {
    private val actor = "10000000-0000-0000-0000-000000000001"
    private val install = "20000000-0000-0000-0000-000000000002"
    private val binding = "30000000-0000-0000-0000-000000000003"
    private val base = "https://example.test/v2/nearby"
    private fun intent(token: String? = "private-device-token", generation: Long = 7) =
        PushRegistrationIntent(install, "a".repeat(64), generation, binding, token)
    private fun TestScope.account(job: Job, token: suspend () -> String? = {"current-bearer"}) =
        SocialAuthenticatedAccount(SocialUserId(actor), job, backgroundScope.coroutineContext, token)

    @Test fun actualPutUsesCurrentAccountAndExplicitNullRetirementBody() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val client = HttpClient(MockEngine) { engine { dispatcher = StandardTestDispatcher(testScheduler); addHandler { requests += it; respond("", HttpStatusCode.NoContent) } } }
        val job = Job(); var bearer = "first"
        val port = AccountPushRegistration(account(job) {bearer}, client, "$base/")
        try {
            assertEquals(PushRegistrationResult.APPLIED, port.submit(intent()))
            bearer = "refreshed"
            assertEquals(PushRegistrationResult.APPLIED, port.submit(intent(null, 8)))
            assertEquals(2, requests.size)
            assertEquals(listOf("Bearer first", "Bearer refreshed"), requests.map {it.headers[HttpHeaders.Authorization]})
            for (r in requests) {
                assertEquals(HttpMethod.Put, r.method); assertEquals("/v2/nearby/push/device", r.url.encodedPath)
                assertEquals(ContentType.Application.Json, r.body.contentType)
                val body = Json.parseToJsonElement((r.body as TextContent).text).jsonObject
                assertEquals(setOf("installationId", "installationKey", "generation", "bindingId", "token"), body.keys)
                assertEquals(install, body["installationId"]!!.jsonPrimitive.content)
                assertEquals(binding, body["bindingId"]!!.jsonPrimitive.content)
                assertEquals("a".repeat(64), body["installationKey"]!!.jsonPrimitive.content)
            }
            val retirement = Json.parseToJsonElement((requests.last().body as TextContent).text).jsonObject
            assertEquals(JsonNull, retirement["token"]); assertEquals(8L, retirement["generation"]!!.jsonPrimitive.long)
            assertFalse(intent().toString().contains("private-device-token"))
        } finally {port.retire(); job.cancel(); client.close()}
    }

    @Test fun cancelledAccountSuppressesActuallyReturnedResponse() = runTest {
        val release = CompletableDeferred<Unit>(); var issued = 0; var returned = false; var published = false
        val job = Job()
        val client = HttpClient(MockEngine) {engine {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler {withContext(NonCancellable) {
                issued++; release.await(); respond("", HttpStatusCode.NoContent).also {returned = true}
            }}
        }}
        val port = AccountPushRegistration(account(job), client, base)
        try {
            val pending = async {port.submit(intent()).also {published = true}}
            runCurrent(); assertEquals(1, issued)
            job.cancel(); release.complete(Unit); runCurrent()
            assertTrue(returned); assertFailsWith<CancellationException> {pending.await()}; assertFalse(published)
        } finally {release.complete(Unit); port.retire(); job.cancel(); client.close()}
    }

    @Test fun retirementKeepsBorrowedAccountAndParentClientUsableButDeniesFurtherSubmission() = runTest {
        var issued = 0
        val client = HttpClient(MockEngine) {engine {dispatcher = StandardTestDispatcher(testScheduler); addHandler {issued++; respond("", HttpStatusCode.NoContent)}}}
        val job = Job(); val before = job.children.toList()
        val port = AccountPushRegistration(account(job), client, base)
        try {
            assertEquals(before, job.children.toList()); port.retire(); port.retire(); assertTrue(job.isActive)
            assertFailsWith<CancellationException> {port.submit(intent())}; assertEquals(0, issued)
            client.get("https://example.test/parent"); assertEquals(1, issued)
        } finally {port.retire(); job.cancel(); client.close()}
    }

    @Test fun malformedIntentNeverIssuesHttp() = runTest {
        var issued = 0
        val client = HttpClient(MockEngine) {engine {dispatcher = StandardTestDispatcher(testScheduler); addHandler {issued++; respond("", HttpStatusCode.NoContent)}}}
        val job = Job(); val port = AccountPushRegistration(account(job), client, base)
        try {
            for (invalid in listOf(intent(generation = 0), intent(token = "bad\nsecret"), intent(token = ""),
                intent(token = "\"".repeat(4096)), intent(token = "a".repeat(4097)),
                PushRegistrationIntent("invalid", "a".repeat(64), 1, binding, "token"),
                PushRegistrationIntent(install, "a".repeat(63), 1, binding, "token"))) {
                assertEquals(PushRegistrationResult.INVALID, port.submit(invalid))
            }
            assertEquals(0, issued)
        } finally {port.retire(); job.cancel(); client.close()}
    }

    @Test fun requestDeadlineIncludesActuallyStartedBearerRefreshAndTransport() = runTest {
        for (slowBearer in listOf(true, false)) {
            var refreshed = 0; var issued = 0
            val client = HttpClient(MockEngine) {engine {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler {issued++; delay(10_001); respond("", HttpStatusCode.NoContent)}
            }}
            val job = Job()
            val port = AccountPushRegistration(account(job) {
                refreshed++; if (slowBearer) delay(10_001); "bearer"
            }, client, base)
            try {
                assertEquals(PushRegistrationResult.UNAVAILABLE, port.submit(intent()))
                assertEquals(1, refreshed); assertEquals(if (slowBearer) 0 else 1, issued)
                assertTrue(job.isActive)
            } finally {port.retire(); job.cancel(); client.close()}
        }
    }

    @Test fun retiringAccountDuringActuallyStartedRefreshPreventsHttp() = runTest {
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var issued = 0
        val client = HttpClient(MockEngine) {engine {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler {issued++; respond("", HttpStatusCode.NoContent)}
        }}
        val job = Job()
        val port = AccountPushRegistration(account(job) {started.complete(Unit); release.await(); "private-bearer"}, client, base)
        try {
            val pending = async {port.submit(intent())}
            started.await(); job.cancel(); release.complete(Unit)
            assertFailsWith<CancellationException> {pending.await()}; assertEquals(0, issued)
        } finally {release.complete(Unit); port.retire(); job.cancel(); client.close()}
    }

    @Test fun conflictCodesAreTypedAndRedirectIsNeverFollowed() = runTest {
        val cases = listOf(
            Triple(HttpStatusCode.Conflict, "{\"code\":\"push_binding_stale\"}", PushRegistrationResult.STALE),
            Triple(HttpStatusCode.Conflict, "{\"code\":\"push_token_conflict\"}", PushRegistrationResult.TOKEN_CONFLICT),
            Triple(HttpStatusCode.Conflict, "{\"code\":\"unknown\"}", PushRegistrationResult.INVALID_RESPONSE),
            Triple(HttpStatusCode.TemporaryRedirect, "", PushRegistrationResult.INVALID_RESPONSE),
            Triple(HttpStatusCode.Conflict, "x".repeat(8193), PushRegistrationResult.INVALID_RESPONSE),
        )
        for ((status, body, result) in cases) {
            var issued = 0
            val client = HttpClient(MockEngine) {engine {dispatcher = StandardTestDispatcher(testScheduler); addHandler {
                issued++; respond(body, status, headersOf(HttpHeaders.Location, "https://other.test/private"))
            }}}
            val job = Job(); val port = AccountPushRegistration(account(job), client, base)
            try {assertEquals(result, port.submit(intent())); assertEquals(1, issued)}
            finally {port.retire(); job.cancel(); client.close()}
        }
    }
}
