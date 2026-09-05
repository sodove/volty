package ru.sodovaya.volty.backend

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.header
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.time.Instant
import kotlin.math.abs

private val offlineId = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")

/** Only public distribution operations are forwarded. Worker administration stays private. */
fun Route.installOfflineRegionRoutes(dependencies: AppDependencies) {
    route("/offline") {
        get("/catalog.json") { call.offlineResponse(dependencies, "/catalog.json") }
        get("/resolve") {
            val lat = call.request.queryParameters["lat"]?.toDoubleOrNull()
            val lon = call.request.queryParameters["lon"]?.toDoubleOrNull()
            require(lat != null && lat.isFinite() && lat in -90.0..90.0 && lon != null && lon.isFinite() && lon in -180.0..180.0) { "Valid lat and lon are required" }
            call.offlineResolve(dependencies, lat, lon)
        }
        post("/regions/{id}/ensure") {
            val key = "offline:${call.request.local.remoteHost}"
            if (!dependencies.navigationSearchLimiter.allow(key, Instant.now().epochSecond)) {
                call.response.header("Retry-After", "60")
                throw ApiException(HttpStatusCode.TooManyRequests, "rate_limited", "Too many region requests")
            }
            call.offlineResponse(dependencies, "/regions/${call.offlineParameter("id")}/ensure${call.releaseQuery()}", "POST")
        }
        get("/regions/{id}/status") {
            call.offlineResponse(dependencies, "/regions/${call.offlineParameter("id")}/status${call.releaseQuery()}")
        }
        get("/regions/{id}/{release}/{artifact...}") {
            val id = call.offlineParameter("id")
            val version = call.offlineParameter("release")
            val artifact = call.parameters.getAll("artifact")?.joinToString("/") ?: ""
            if (artifact !in setOf("manifest.json", "routing/valhalla-routing.tar.gz", "search/places.sqlite.gz", "map/$id.pmtiles")) {
                throw ApiException(HttpStatusCode.NotFound, "not_found", "Artifact not found")
            }
            call.offlineResponse(dependencies, "/regions/$id/$version/$artifact")
        }
    }
}

private data class StaticOfflineCandidate(
    val regionId: String,
    val releaseVersion: String,
    val area: Double,
)

private suspend fun ApplicationCall.offlineResolve(dependencies: AppDependencies, latitude: Double, longitude: Double) {
    val manager = dependencies.config.offlineManagerUrl
    if (manager != null) {
        offlineResponse(dependencies, "/resolve?lat=$latitude&lon=$longitude")
        return
    }

    val root = dependencies.config.offlineFilesRoot?.let(::File)?.canonicalFile
        ?: throw ApiException(HttpStatusCode.NotFound, "offline_unavailable", "Offline distribution is not configured")
    val catalog = File(root, "catalog.json").canonicalFile
    if (!catalog.toPath().startsWith(root.toPath()) || !catalog.isFile) {
        throw ApiException(HttpStatusCode.NotFound, "offline_unavailable", "Offline distribution is not configured")
    }
    val document = try {
        Json.parseToJsonElement(catalog.readText()).jsonObject
    } catch (_: Exception) {
        throw ApiException(HttpStatusCode.ServiceUnavailable, "offline_unavailable", "Offline catalog is unavailable")
    }
    val candidates = document["regions"]?.jsonArray.orEmpty().mapNotNull { entry ->
        val region = entry.jsonObject["region"]?.jsonObject ?: return@mapNotNull null
        val release = entry.jsonObject["latestRelease"]?.jsonObject ?: return@mapNotNull null
        val regionId = region["regionId"]?.jsonPrimitive?.content ?: return@mapNotNull null
        val releaseVersion = release["releaseVersion"]?.jsonPrimitive?.content ?: return@mapNotNull null
        val bounds = region["bounds"]?.jsonObject ?: return@mapNotNull null
        val south = bounds["south"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return@mapNotNull null
        val west = bounds["west"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return@mapNotNull null
        val north = bounds["north"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return@mapNotNull null
        val east = bounds["east"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return@mapNotNull null
        if (south > latitude || latitude > north || west > longitude || longitude > east) return@mapNotNull null
        StaticOfflineCandidate(regionId, releaseVersion, abs(north - south) * abs(east - west))
    }
    val selected = candidates.minWithOrNull(compareBy<StaticOfflineCandidate> { it.area }.thenBy { it.regionId })
    val ready = selected?.let {
        File(root, "regions/${it.regionId}/${it.releaseVersion}/.ready.json").isFile
    } == true
    val response = buildJsonObject {
        put("status", when {
            selected == null -> "unsupported"
            ready -> "ready"
            else -> "available"
        })
        if (selected == null) {
            put("regionId", JsonNull)
            put("releaseVersion", JsonNull)
        } else {
            put("regionId", selected.regionId)
            put("releaseVersion", selected.releaseVersion)
        }
    }
    respondText(response.toString(), ContentType.Application.Json)
}

private fun ApplicationCall.offlineParameter(name: String): String =
    requireNotNull(parameters[name]).also { require(offlineId.matches(it) && !it.contains("..")) { "Invalid $name" } }

private fun ApplicationCall.releaseQuery(): String = request.queryParameters["releaseVersion"]?.let {
    require(offlineId.matches(it) && !it.contains("..")) { "Invalid releaseVersion" }
    "?releaseVersion=$it"
} ?: ""

private suspend fun ApplicationCall.offlineResponse(dependencies: AppDependencies, path: String, method: String = "GET") {
    val manager = dependencies.config.offlineManagerUrl
    if (manager == null) {
        // Compatibility for an existing static deployment. Never expose staging, keys or directories.
        val root = dependencies.config.offlineFilesRoot?.let(::File)?.canonicalFile
        val file = root?.let { File(it, path.removePrefix("/")).canonicalFile }
        if (method == "GET" && '?' !in path && root != null && file != null &&
            file.toPath().startsWith(root.toPath()) && file.isFile) {
            response.header("Cache-Control", if (path == "/catalog.json") "no-cache" else "public, max-age=86400, immutable")
            respondFile(file)
            return
        }
        throw ApiException(HttpStatusCode.NotFound, "offline_unavailable", "Offline distribution is not configured")
    }
    val connection = URI(manager.trimEnd('/') + path).toURL().openConnection() as HttpURLConnection
    connection.requestMethod = method
    connection.connectTimeout = 5_000
    connection.readTimeout = 30_000
    connection.instanceFollowRedirects = false
    request.headers["Range"]?.let { connection.setRequestProperty("Range", it) }
    request.headers["If-Range"]?.let { connection.setRequestProperty("If-Range", it) }
    try {
        val status = try { withContext(Dispatchers.IO) { connection.responseCode } } catch (_: IOException) {
            response.header("Retry-After", "10")
            throw ApiException(HttpStatusCode.ServiceUnavailable, "offline_service_unavailable", "Offline package service is temporarily unavailable")
        }
        if (status in 300..399 && status != 304) throw ApiException(HttpStatusCode.BadGateway, "offline_invalid_response", "Offline service returned an invalid response")
        listOf("ETag", "Last-Modified", "Cache-Control", "Retry-After", "Accept-Ranges", "Content-Range").forEach { name ->
            connection.getHeaderField(name)?.let { response.header(name, it) }
        }
        val contentType = connection.contentType?.let { runCatching { ContentType.parse(it) }.getOrNull() } ?: ContentType.Application.OctetStream
        respondOutputStream(contentType, HttpStatusCode.fromValue(status), contentLength = connection.contentLengthLong.takeIf { it >= 0 }) {
            try {
                withContext(Dispatchers.IO) {
                    (if (status >= 400) connection.errorStream else connection.inputStream)?.use { it.copyTo(this@respondOutputStream) }
                }
            } finally { connection.disconnect() }
        }
    } catch (failure: Throwable) {
        connection.disconnect()
        throw failure
    }
}
