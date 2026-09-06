package ru.sodovaya.volty.data.navigation

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionPackageFailure
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionPackageFailureException

@OptIn(ExperimentalCoroutinesApi::class)
class HttpOfflineRegionAcquisitionTest {
    @Test
    fun waits_for_matching_release_before_returning_and_issues_versioned_post_then_get() = runTest {
        val requests = mutableListOf<Pair<HttpMethod, String>>()
        val replies = ArrayDeque(listOf(status("queued"), status("downloading"), status("ready")))
        val client = client { request ->
            requests += request.method to request.url.toString()
            respond(replies.removeFirst())
        }
        try {
            HttpOfflineRegionAcquisition(client, CATALOG).ensureReady("ekb", "2026.09")
            assertEquals(listOf(
                HttpMethod.Post to "$BASE/ekb/ensure?releaseVersion=2026.09",
                HttpMethod.Get to "$BASE/ekb/status?releaseVersion=2026.09",
                HttpMethod.Get to "$BASE/ekb/status?releaseVersion=2026.09",
            ), requests)
            assertTrue(replies.isEmpty())
        } finally { client.close() }
    }

    @Test
    fun arbitrary_cdn_catalog_does_not_receive_acquisition_requests() = runTest {
        var requests = 0
        val client = client { requests++; error("CDN has no acquisition API") }
        try {
            HttpOfflineRegionAcquisition(client, "https://cdn.example.org/releases/catalog.json")
                .ensureReady("ekb", "2026.09")
            assertEquals(0, requests)
        } finally { client.close() }
    }

    @Test
    fun initial_404_or_405_preserves_static_distribution() = runTest {
        for (code in listOf(HttpStatusCode.NotFound, HttpStatusCode.MethodNotAllowed)) {
            var requests = 0
            val client = client { requests++; respond("static", code) }
            try {
                HttpOfflineRegionAcquisition(client, CATALOG).ensureReady("ekb", "2026.09")
                assertEquals(1, requests)
            } finally { client.close() }
        }
    }

    @Test
    fun structured_unavailable_404_does_not_enable_static_fallback() = runTest {
        val client = client { respond(status("unavailable"), HttpStatusCode.NotFound) }
        try {
            val failure = assertFailsWith<OfflineRegionPackageFailureException> {
                HttpOfflineRegionAcquisition(client, CATALOG).ensureReady("ekb", "2026.09")
            }
            assertEquals(OfflineRegionPackageFailure.INCOMPATIBLE, failure.category)
        } finally { client.close() }
    }

    @Test
    fun malformed_or_unknown_success_responses_never_authorize_artifact_download() = runTest {
        for (body in listOf("not json", status("mystery"), "x".repeat(16 * 1_024 + 1))) {
            val client = client { respond(body) }
            try {
                val failure = assertFailsWith<OfflineRegionPackageFailureException> {
                    HttpOfflineRegionAcquisition(client, CATALOG).ensureReady("ekb", "2026.09")
                }
                assertEquals(OfflineRegionPackageFailure.NETWORK, failure.category)
            } finally { client.close() }
        }
    }

    @Test
    fun status_endpoint_missing_after_queue_does_not_enable_static_fallback() = runTest {
        val requests = mutableListOf<HttpMethod>()
        val client = client { request ->
            requests += request.method
            if (requests.size == 1) respond(status("queued"))
            else respond("missing", HttpStatusCode.NotFound)
        }
        try {
            val failure = assertFailsWith<OfflineRegionPackageFailureException> {
                HttpOfflineRegionAcquisition(client, CATALOG).ensureReady("ekb", "2026.09")
            }
            assertEquals(OfflineRegionPackageFailure.NETWORK, failure.category)
            assertEquals(listOf(HttpMethod.Post, HttpMethod.Get), requests)
        } finally { client.close() }
    }

    @Test
    fun wrong_region_or_release_cannot_authorize_artifact_download() = runTest {
        for (body in listOf(status("ready", region = "other"), status("ready", release = "older"))) {
            val client = client { respond(body) }
            try {
                val failure = assertFailsWith<OfflineRegionPackageFailureException> {
                    HttpOfflineRegionAcquisition(client, CATALOG).ensureReady("ekb", "2026.09")
                }
                assertEquals(OfflineRegionPackageFailure.INCOMPATIBLE, failure.category)
            } finally { client.close() }
        }
    }

    @Test
    fun failed_or_unavailable_package_never_continues_to_download() = runTest {
        for ((state, expected) in listOf(
            "failed" to OfflineRegionPackageFailure.NETWORK,
            "unavailable" to OfflineRegionPackageFailure.INCOMPATIBLE,
        )) {
            val client = client { respond(status(state)) }
            try {
                val failure = assertFailsWith<OfflineRegionPackageFailureException> {
                    HttpOfflineRegionAcquisition(client, CATALOG).ensureReady("ekb", "2026.09")
                }
                assertEquals(expected, failure.category)
            } finally { client.close() }
        }
    }

    @Test
    fun permanently_queued_acquisition_is_bounded_and_actually_polls() = runTest {
        val methods = mutableListOf<HttpMethod>()
        val client = client { request -> methods += request.method; respond(status("queued")) }
        try {
            val failure = assertFailsWith<OfflineRegionPackageFailureException> {
                HttpOfflineRegionAcquisition(client, CATALOG, maxWaitMillis = 2_500)
                    .ensureReady("ekb", "2026.09")
            }
            assertEquals(OfflineRegionPackageFailure.NETWORK, failure.category)
            assertEquals(listOf(HttpMethod.Post, HttpMethod.Get, HttpMethod.Get), methods)
        } finally { client.close() }
    }

    @Test
    fun cancellation_after_ensure_prevents_all_status_requests() = runTest {
        val methods = mutableListOf<HttpMethod>()
        val client = client { request ->
            methods += request.method
            respond(status("queued"))
        }
        try {
            val job = launch { HttpOfflineRegionAcquisition(client, CATALOG).ensureReady("ekb", "2026.09") }
            testScheduler.runCurrent()
            assertEquals(listOf(HttpMethod.Post), methods)
            job.cancelAndJoin()
            testScheduler.advanceUntilIdle()
            assertTrue(job.isCancelled)
            assertEquals(listOf(HttpMethod.Post), methods)
        } finally { client.close() }
    }

    private fun TestScope.client(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ) = HttpClient(MockEngine) {
        followRedirects = false
        engine {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler(handler)
        }
    }

    private fun status(state: String, region: String = "ekb", release: String = "2026.09") =
        """{"status":"$state","regionId":"$region","releaseVersion":"$release","retryAfterSeconds":1}"""

    private companion object {
        const val CATALOG = "https://volty.example.org/api/offline/catalog.json"
        const val BASE = "https://volty.example.org/api/offline/regions"
    }
}
