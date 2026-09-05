package ru.sodovaya.volty.data.navigation

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.encodeURLParameter
import io.ktor.http.encodedPath
import io.ktor.http.isSuccess
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionPackageFailure
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionPackageFailureException

/** Waits for the backend's verified cache before the signed artifact download. */
class HttpOfflineRegionAcquisition(
    private val client: HttpClient,
    private val catalogUrl: String,
    private val maxWaitMillis: Long = 30 * 60 * 1_000L,
    private val userAgent: String = "Volty/0.7 offline-region-repository",
) {
    private val json = Json { ignoreUnknownKeys = true }
    // The backend route is an opt-in URL convention. Arbitrary CDN catalogs
    // continue to download directly from their signed artifact URLs.
    private val endpoint = runCatching { Url(catalogUrl) }.getOrNull()?.takeIf {
        it.protocol == URLProtocol.HTTPS && it.encodedPath.endsWith("/offline/catalog.json")
    }

    suspend fun ensureReady(regionId: String, releaseVersion: String) {
        if (endpoint == null) return
        try {
            val completed = withTimeoutOrNull(maxWaitMillis) {
                var action = "ensure"
                while (true) {
                    val response = request(regionId, releaseVersion, action) ?: return@withTimeoutOrNull true
                    if (response.regionId != regionId || response.releaseVersion != releaseVersion) {
                        fail(OfflineRegionPackageFailure.INCOMPATIBLE, "Acquisition release does not match the signed catalog")
                    }
                    when (response.status) {
                        "ready" -> return@withTimeoutOrNull true
                        "unavailable" -> fail(OfflineRegionPackageFailure.INCOMPATIBLE, "Regional package is unavailable")
                        "failed" -> fail(OfflineRegionPackageFailure.NETWORK, "Server could not acquire the regional package")
                        "queued", "downloading" -> Unit
                        else -> fail(OfflineRegionPackageFailure.NETWORK, "Unknown acquisition state")
                    }
                    delay((response.retryAfterSeconds ?: 5).coerceIn(1, 30) * 1_000L)
                    action = "status"
                }
            }
            if (completed != true) fail(OfflineRegionPackageFailure.NETWORK, "Regional package preparation timed out")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: OfflineRegionPackageFailureException) {
            throw failure
        } catch (error: Exception) {
            throw OfflineRegionPackageFailureException(
                OfflineRegionPackageFailure.NETWORK, "Regional package preparation failed", error,
            )
        }
    }

    private suspend fun request(regionId: String, releaseVersion: String, action: String): AcquisitionStatus? {
        val catalog = requireNotNull(endpoint)
        val url = URLBuilder(catalog).apply {
            encodedPath = catalog.encodedPath.removeSuffix("catalog.json") +
                "regions/${regionId.encodeURLParameter()}/$action"
            parameters.clear()
            parameters.append("releaseVersion", releaseVersion)
            fragment = ""
        }.buildString()
        return client.prepareRequest(url) {
            method = if (action == "ensure") HttpMethod.Post else HttpMethod.Get
            header(HttpHeaders.Accept, "application/json")
            header(HttpHeaders.UserAgent, userAgent)
        }.execute { response ->
            // A static host may use the same catalog path. Only the first
            // probe permits this fallback: a disappearing status API is an error.
            val bytes = response.bodyAsChannel().readRemaining(MAX_RESPONSE_BYTES + 1).readByteArray()
            if (bytes.size > MAX_RESPONSE_BYTES) fail(OfflineRegionPackageFailure.NETWORK, "Acquisition response is too large")
            val parsed = runCatching { json.decodeFromString<AcquisitionStatus>(bytes.decodeToString()) }.getOrNull()
            if (action == "ensure" && response.status.value in setOf(404, 405)) {
                if (parsed?.status == "unavailable") return@execute parsed
                return@execute null
            }
            if (!response.status.isSuccess()) {
                fail(OfflineRegionPackageFailure.NETWORK, "Acquisition request failed (${response.status.value})")
            }
            parsed ?: fail(OfflineRegionPackageFailure.NETWORK, "Malformed acquisition response")
        }
    }

    private fun fail(category: OfflineRegionPackageFailure, message: String): Nothing =
        throw OfflineRegionPackageFailureException(category, message)

    @Serializable
    private data class AcquisitionStatus(
        val status: String,
        val regionId: String,
        val releaseVersion: String? = null,
        val retryAfterSeconds: Int? = null,
    )

    private companion object {
        const val MAX_RESPONSE_BYTES = 16 * 1_024L
    }
}
