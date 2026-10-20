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

    private class Log<T> {
        private val mutex = Mutex()
        private val items = mutableListOf<T>()
        suspend fun add(item: T) = mutex.withLock { items += item }
        suspend fun snapshot(): List<T> = mutex.withLock { items.toList() }
    }

    private class RecordingStore : RadioKeyStore {
        val puts = Log<Triple<String, RadioKey, Long>>()
        override suspend fun get(actor: String, community: String): StoredRadioKey? = null
        override suspend fun put(actor: String, key: RadioKey, fetchedAtMillis: Long) {
            puts.add(Triple(actor, key, fetchedAtMillis))
        }
    }

    private fun summary(id: String) =
        """{"id":"$id","name":"Riders","description":"Ride","visibility":"PUBLIC","createdAtEpochMillis":100}"""

    // Real dispatchers: the refresh runs in the session's own background scope. Every wait here is bounded.
    private suspend fun awaitPuts(store: RecordingStore, n: Int) = withTimeout(5_000) {
        while (store.puts.snapshot().size < n) delay(10)
    }

    @Test fun listedCommunitiesEachGetOneKeyFetchAndStoreWithClockAndFailuresAreIgnored() = runBlocking {
        val radioPaths = Log<String>()
        val client = HttpClient(MockEngine) { engine { addHandler { request ->
            val path = request.url.encodedPath
            when {
                path == "/v2/nearby/communities" -> respond("""{"items":[${summary(a)},${summary(b)}]}""", HttpStatusCode.OK, json)
                path.endsWith("/radio-key") -> {
                    radioPaths.add(path)
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
            assertEquals(listOf(Triple(actor, RadioKey(a, 4, ByteArray(32) { 9 }), 1_234L)), store.puts.snapshot())
            // Re-list until the failed community b has been requested a second time. A listing made while b's first
            // attempt is still in flight is skipped by design, so keep listing (bounded) instead of assuming timing.
            withTimeout(5_000) {
                while (radioPaths.snapshot().count { it.contains(b) } < 2) { session.communities.memberships(); delay(20) }
            }
            val paths = radioPaths.snapshot()
            assertEquals(1, paths.count { it.contains(a) }) // a was stored, so it is never fetched again
            assertTrue(paths.count { it.contains(b) } >= 2)
            assertEquals(1, store.puts.snapshot().size)
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

    // Blocks the radio-key response (ignoring cancellation, like a response already on the wire) until released.
    private class BlockedKeyServer(private val summaryJson: String, private val keyB64: String, private val community: String) {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val answered = CompletableDeferred<Unit>()
        val client = HttpClient(MockEngine) { engine { addHandler { request ->
            if (request.url.encodedPath.endsWith("/radio-key")) {
                entered.complete(Unit)
                withContext(NonCancellable) { release.await() }
                answered.complete(Unit)
                respond("""{"communityId":"$community","epoch":4,"key":"$keyB64"}""", HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"))
            } else respond("""{"items":[$summaryJson]}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        } } }
    }

    @Test fun retiringTheSessionWhileAKeyIsInFlightStoresNothing() = runBlocking {
        val server = BlockedKeyServer(summary(a), keyB64, a)
        val store = RecordingStore()
        val session = NearbyAccountHttpSession(NearbyAccountCredentials(actor, "tok"), server.client, root, null, store)
        try {
            session.communities.memberships()
            withTimeout(5_000) { server.entered.await() }
            session.retire()
            server.release.complete(Unit)
            withTimeout(5_000) { server.answered.await() } // the response really was delivered after retirement
            delay(300)
            assertEquals(emptyList(), store.puts.snapshot())
        } finally { server.release.complete(Unit); server.client.close() }
    }

    @Test fun completingTheBorrowedAccountJobWhileAKeyIsInFlightStoresNothing() = runBlocking {
        val server = BlockedKeyServer(summary(a), keyB64, a)
        val store = RecordingStore()
        val accountJob = Job()
        val session = NearbyAccountHttpSession(NearbyAccountCredentials(actor, "tok"), server.client, root, accountJob, store)
        try {
            session.communities.memberships()
            withTimeout(5_000) { server.entered.await() }
            accountJob.cancel()
            server.release.complete(Unit)
            withTimeout(5_000) { server.answered.await() }
            delay(300)
            assertEquals(emptyList(), store.puts.snapshot())
        } finally { server.release.complete(Unit); session.retire(); server.client.close() }
    }

    // The fetch itself completes, then the store is slow: retirement must cancel the refresh while it is inside put.
    // (Retiring closes the key port, so blocking only the HTTP response cannot tell whether the job was cancelled.)
    private class SlowStore : RadioKeyStore {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val puts = Log<RadioKey>()
        override suspend fun get(actor: String, community: String): StoredRadioKey? = null
        override suspend fun put(actor: String, key: RadioKey, fetchedAtMillis: Long) {
            entered.complete(Unit)
            release.await()
            puts.add(key)
        }
    }

    @Test fun retiringTheSessionWhileTheStoreIsWritingAbandonsTheWrite() = runBlocking {
        val client = HttpClient(MockEngine) { engine { addHandler { request ->
            val body = if (request.url.encodedPath.endsWith("/radio-key"))
                """{"communityId":"$a","epoch":4,"key":"$keyB64"}""" else """{"items":[${summary(a)}]}"""
            respond(body, HttpStatusCode.OK, json)
        } } }
        val store = SlowStore()
        val session = NearbyAccountHttpSession(NearbyAccountCredentials(actor, "tok"), client, root, null, store)
        try {
            session.communities.memberships()
            withTimeout(5_000) { store.entered.await() }
            session.retire()
            store.release.complete(Unit)
            delay(300)
            assertEquals(emptyList(), store.puts.snapshot())
        } finally { store.release.complete(Unit); client.close() }
    }
}
