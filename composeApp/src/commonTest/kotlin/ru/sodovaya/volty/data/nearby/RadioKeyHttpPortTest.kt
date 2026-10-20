package ru.sodovaya.volty.data.nearby

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlin.io.encoding.Base64
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import ru.sodovaya.volty.domain.radio.RadioKey
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class RadioKeyHttpPortTest {
    private val actor = "10000000-0000-0000-0000-000000000001"
    private val community = "20000000-0000-0000-0000-000000000002"
    private val keyBytes = ByteArray(32) { it.toByte() }
    private fun body(id: String = community, epoch: String = "3", key: ByteArray = keyBytes) =
        """{"communityId":"$id","epoch":$epoch,"key":"${Base64.Default.encode(key)}"}"""
    private fun TestScope.client(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        HttpClient(MockEngine) { engine { dispatcher = StandardTestDispatcher(testScheduler); addHandler(handler) } }
    private fun port(client: HttpClient, bearer: String? = "tok") =
        RadioKeyHttpPort(client, "https://example.test/v2/nearby", actor) { bearer }
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    @Test fun parsesIssuedKey() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val client = client { requests += it; respond(body(), HttpStatusCode.OK, json) }
        val port = port(client)
        try {
            assertEquals(RadioKey(community, 3, keyBytes), port.fetch(community))
            val r = requests.single()
            assertEquals(HttpMethod.Get, r.method)
            assertEquals("/v2/nearby/communities/$community/radio-key", r.url.encodedPath)
            assertEquals("Bearer tok", r.headers[HttpHeaders.Authorization])
        } finally { port.close(); client.close() }
    }

    @Test fun rejectsWrongCommunityId() = runTest {
        val client = client { respond(body(id = "30000000-0000-0000-0000-000000000003"), HttpStatusCode.OK, json) }
        val port = port(client)
        try { assertNull(port.fetch(community)) } finally { port.close(); client.close() }
    }

    @Test fun rejectsKeyNot32Bytes() = runTest {
        val client = client { respond(body(key = ByteArray(31)), HttpStatusCode.OK, json) }
        val port = port(client)
        try { assertNull(port.fetch(community)) } finally { port.close(); client.close() }
    }

    @Test fun returnsNullOn404() = runTest {
        val client = client { respond("", HttpStatusCode.NotFound) }
        val port = port(client)
        try { assertNull(port.fetch(community)) } finally { port.close(); client.close() }
    }

    @Test fun malformedOversizedAndStringEpochAreNull() = runTest {
        val bodies = listOf("not json", body(epoch = "\"3\""), body(epoch = "-1"), """{"communityId":"$community"}""",
            body() + " ".repeat(2000), """{"communityId":"$community","epoch":1,"key":"!!!"}""")
        var i = 0
        val client = client { respond(bodies[i++], HttpStatusCode.OK, json) }
        val port = port(client)
        try {
            for (b in bodies) assertNull(port.fetch(community), b.take(40))
            assertEquals(bodies.size, i)
        } finally { port.close(); client.close() }
    }

    @Test fun missingBearerIssuesNoRequestAndRedirectIsNull() = runTest {
        var issued = 0
        val client = client { issued++; respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://other.test")) }
        val noAuth = port(client, bearer = null)
        val auth = port(client)
        try {
            assertNull(noAuth.fetch(community)); assertEquals(0, issued)
            assertNull(auth.fetch(community)); assertEquals(1, issued)
        } finally { noAuth.close(); auth.close(); client.close() }
    }

    @Test fun invalidCommunityIdIsRejectedBeforeAnyRequest() = runTest {
        var issued = 0
        val client = client { issued++; respond(body(), HttpStatusCode.OK, json) }
        val port = port(client)
        try {
            assertFailsWith<IllegalArgumentException> { port.fetch("bad id") }
            assertEquals(0, issued)
        } finally { port.close(); client.close() }
    }
}
