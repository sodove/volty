package ru.sodovaya.volty.data.nearby

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlin.io.encoding.Base64
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.sodovaya.volty.domain.nearby.community.*
import ru.sodovaya.volty.domain.radio.*
import kotlin.test.*

class NearbyAccountRadioKeyRefreshTest {
    private val actor = "10000000-0000-0000-0000-000000000001"
    private val a = "30000000-0000-0000-0000-00000000000a"
    private val b = "30000000-0000-0000-0000-00000000000b"
    private val root = "https://example.test/v2/nearby"
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val keyB64 = Base64.Default.encode(ByteArray(32) { 9 })

    private class RecordingStore : RadioKeyStore {
        val puts = mutableListOf<Triple<String, RadioKey, Long>>()
        private val mutex = Mutex()
        override suspend fun get(actor: String, community: String): StoredRadioKey? = null
        override suspend fun put(actor: String, key: RadioKey, fetchedAtMillis: Long) {
            mutex.withLock { puts += Triple(actor, key, fetchedAtMillis) }
        }
    }

    private fun summary(id: String) =
        """{"id":"$id","name":"Riders","description":"Ride","visibility":"PUBLIC","createdAtEpochMillis":100}"""

    // Real dispatchers: the refresh runs in the session's own background scope. Every wait here is bounded.
    private suspend fun awaitPuts(store: RecordingStore, n: Int) = withTimeout(5_000) {
        while (store.puts.size < n) delay(10)
    }

    @Test fun listedCommunitiesEachGetOneKeyFetchAndStoreWithClockAndFailuresAreIgnored() = runBlocking {
        val radioPaths = mutableListOf<String>()
        val client = HttpClient(MockEngine) { engine { addHandler { request ->
            val path = request.url.encodedPath
            when {
                path == "/v2/nearby/communities" -> respond("""{"items":[${summary(a)},${summary(b)}]}""", HttpStatusCode.OK, json)
                path.endsWith("/radio-key") -> {
                    radioPaths += path
                    if (path.contains(b)) respond("", HttpStatusCode.NotFound)
                    else respond("""{"communityId":"$a","epoch":4,"key":"$keyB64"}""", HttpStatusCode.OK, json)
                }
                else -> respond("", HttpStatusCode.InternalServerError)
            }
        } } }
        val store = RecordingStore()
        val session = NearbyAccountHttpSession(NearbyAccountCredentials(actor, "tok"), client, root, null, store) { 1_234L }
        try {
            val listed = assertIs<CommunityResult.Ready<List<CommunitySummary>>>(session.communities.memberships())
            assertEquals(2, listed.value.size) // the 404 for b never touches the list result
            awaitPuts(store, 1)
            assertEquals(listOf(Triple(actor, RadioKey(a, 4, ByteArray(32) { 9 }), 1_234L)), store.puts)
            // Listing again re-requests only the community whose fetch failed.
            session.communities.memberships()
            withTimeout(5_000) { while (radioPaths.size < 3) delay(10) }
            delay(100)
            assertEquals(3, radioPaths.size)
            assertEquals(1, radioPaths.count { it.contains(a) })
            assertEquals(2, radioPaths.count { it.contains(b) })
            assertEquals(1, store.puts.size)
        } finally { session.retire(); client.close() }
    }

    @Test fun sessionWithoutStoreNeverRequestsKeys() = runBlocking {
        var radioRequests = 0
        val client = HttpClient(MockEngine) { engine { addHandler { request ->
            if (request.url.encodedPath.endsWith("/radio-key")) radioRequests++
            respond("""{"items":[${summary(a)}]}""", HttpStatusCode.OK, json)
        } } }
        val session = NearbyAccountHttpSession(NearbyAccountCredentials(actor, "tok"), client, root)
        try {
            session.communities.memberships()
            delay(200)
            assertEquals(0, radioRequests)
        } finally { session.retire(); client.close() }
    }
}
