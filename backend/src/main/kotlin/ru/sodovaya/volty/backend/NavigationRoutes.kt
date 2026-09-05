package ru.sodovaya.volty.backend

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import java.time.Instant

private const val MAX_NAVIGATION_BODY_BYTES = 32 * 1024L
private const val MAX_SEARCH_LIMIT = 8
private const val SEARCH_CACHE_TTL_MILLIS = 60_000L
private const val ROUTE_CACHE_TTL_MILLIS = 30_000L

internal suspend fun handleNavigationSearch(call: ApplicationCall, dependencies: AppDependencies) {
    val query = call.request.queryParameters["q"]?.trim()
        ?: throw ApiException(HttpStatusCode.BadRequest, "invalid_request", "q is required")
    validateNavigationQuery(query)
    val languageTag = validateLanguageTag(call.request.queryParameters["languageTag"] ?: "ru-RU")
    val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.also {
        if (it !in 1..MAX_SEARCH_LIMIT) throw invalidNavigation("limit must be between 1 and $MAX_SEARCH_LIMIT")
    } ?: MAX_SEARCH_LIMIT
    val latitude = call.request.queryParameters["latitude"]?.toDoubleOrNull()
    val longitude = call.request.queryParameters["longitude"]?.toDoubleOrNull()
    if ((latitude == null) != (longitude == null)) throw invalidNavigation("latitude and longitude must be provided together")
    val near = if (latitude != null && longitude != null) {
        validateCoordinate(GeoCoordinateDto(latitude, longitude))
        GeoCoordinateDto(latitude, longitude)
    } else {
        null
    }
    val key = "${query.lowercase()}|$languageTag|${near?.latitude}|${near?.longitude}|$limit"
    val now = Instant.now().toEpochMilli()
    val ip = call.request.local.remoteHost
    if (!dependencies.navigationSearchLimiter.allow(ip, now / 1_000L)) {
        return call.respondNavigationRateLimited(dependencies.navigationSearchLimiter, ip, now / 1_000L)
    }
    val cached = dependencies.navigationSearchCache.get(key, now)
    val result: ProviderResult<List<NavigationPlaceDto>> = cached?.let { ProviderResult.Success(it) }
        ?: dependencies.navigationProvider.search(ProviderSearchRequest(query, near, languageTag, limit)).also { providerResult ->
            if (providerResult is ProviderResult.Success<*>) {
                @Suppress("UNCHECKED_CAST")
                dependencies.navigationSearchCache.put(key, providerResult.value as List<NavigationPlaceDto>, now)
            }
        }
    when (result) {
        is ProviderResult.Success<*> -> {
            val places = result.value as? List<*> ?: throw navigationMalformed()
            if (places.any { it !is NavigationPlaceDto || !isValidPlace(it) }) throw navigationMalformed()
            @Suppress("UNCHECKED_CAST")
            call.respond((places as List<NavigationPlaceDto>).take(MAX_SEARCH_LIMIT))
        }
        ProviderResult.NoRoute -> call.respond(HttpStatusCode.OK, emptyList<NavigationPlaceDto>())
        is ProviderResult.RateLimited -> call.respondRateLimited(result.retryAfterSeconds)
        ProviderResult.Unavailable -> throw navigationUnavailable()
        ProviderResult.MalformedResponse -> throw navigationMalformed()
    }
}

internal suspend fun handleNavigationRoutes(call: ApplicationCall, dependencies: AppDependencies) {
    if ((call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: 0L) > MAX_NAVIGATION_BODY_BYTES) {
        throw ApiException(HttpStatusCode.PayloadTooLarge, "navigation_body_too_large", "Navigation request is too large")
    }
    val body = call.receive<NavigationRouteRequestDto>()
    validateCoordinate(body.origin)
    validatePlace(body.destination)
    val languageTag = validateLanguageTag(body.languageTag)
    val routingProfile = NavigationRoutingProfile.parse(body.routingProfile)
        ?: throw invalidNavigation("routingProfile is invalid")
    if (body.alternativesLimit !in 1..3) throw invalidNavigation("alternativesLimit must be between 1 and 3")
    if (!dependencies.config.navigationEnabled) {
        throw navigationUnavailable()
    }
    val ip = call.request.local.remoteHost
    val now = Instant.now().toEpochMilli()
    if (!dependencies.navigationRouteLimiter.allow(ip, now / 1_000L)) {
        return call.respondNavigationRateLimited(dependencies.navigationRouteLimiter, ip, now / 1_000L)
    }
    val providerRequest = ProviderRouteRequest(
        origin = body.origin,
        destination = GeoCoordinateDto(body.destination.latitude, body.destination.longitude),
        languageTag = languageTag,
        alternativesLimit = body.alternativesLimit,
        routingProfile = routingProfile,
    )
    val key = dependencies.routeCacheKey(providerRequest)
    val result: ProviderResult<NavigationRouteResponse> = dependencies.navigationRouteCache.get(key, now)
        ?.let { ProviderResult.Success(it) }
        ?: dependencies.navigationProvider.routes(providerRequest).also {
            if (it is ProviderResult.Success<*>) {
                @Suppress("UNCHECKED_CAST")
                dependencies.navigationRouteCache.put(key, it.value as NavigationRouteResponse, now)
            }
        }
    when (result) {
        is ProviderResult.Success<*> -> {
            val providerResponse = result.value as? NavigationRouteResponse ?: throw navigationMalformed()
            val response = providerResponse.copy(
                schemaVersion = 1,
                destination = body.destination,
                routes = result.value.routes.take(body.alternativesLimit),
            )
            if (!isValidRouteResponse(response)) throw navigationMalformed()
            call.respond(response)
        }
        ProviderResult.NoRoute -> throw ApiException(HttpStatusCode.UnprocessableEntity, "navigation_no_route", "No route was found")
        is ProviderResult.RateLimited -> call.respondRateLimited(result.retryAfterSeconds)
        ProviderResult.Unavailable -> throw navigationUnavailable()
        ProviderResult.MalformedResponse -> throw navigationMalformed()
    }
}

private fun validateNavigationQuery(query: String) {
    if (query.length !in 3..160) throw invalidNavigation("q must be between 3 and 160 characters")
}

private fun validateLanguageTag(value: String): String {
    val normalized = value.trim()
    if (normalized.length !in 2..35 || !LANGUAGE_TAG.matches(normalized)) throw invalidNavigation("languageTag is invalid")
    return normalized
}

private fun validateCoordinate(value: GeoCoordinateDto) {
    if (!value.latitude.isFinite() || value.latitude !in -90.0..90.0 ||
        !value.longitude.isFinite() || value.longitude !in -180.0..180.0
    ) throw invalidNavigation("coordinate is invalid")
}

private fun validatePlace(value: NavigationPlaceDto) {
    if (value.id.trim().length !in 1..200) throw invalidNavigation("destination id is invalid")
    if (value.title.trim().length !in 1..160) throw invalidNavigation("destination title is invalid")
    if (value.subtitle?.length?.let { it > 160 } == true) throw invalidNavigation("destination subtitle is invalid")
    validateCoordinate(GeoCoordinateDto(value.latitude, value.longitude))
}

private fun isValidPlace(value: NavigationPlaceDto): Boolean = runCatching {
    validatePlace(value)
}.isSuccess

private fun isValidRouteResponse(value: NavigationRouteResponse): Boolean =
    value.schemaVersion == 1 && value.routes.size in 1..3 &&
        value.routes.map { it.id }.toSet().size == value.routes.size &&
        value.routes.all { route ->
            route.id.isNotBlank() && route.distanceMeters.isFinite() && route.distanceMeters >= 0.0 &&
                route.durationSeconds > 0L && route.geometry.size >= 2 &&
                route.geometry.all { coordinate -> runCatching { validateCoordinate(coordinate) }.isSuccess } &&
                route.maneuvers.isNotEmpty() && route.maneuvers.last().kind == "ARRIVE" &&
                route.maneuvers.all { maneuver ->
                    maneuver.id.isNotBlank() && maneuver.instruction.isNotBlank() &&
                        maneuver.shapeIndex in route.geometry.indices &&
                        maneuver.distanceMeters.isFinite() && maneuver.distanceMeters >= 0.0
                }
        }

private fun invalidNavigation(message: String) = ApiException(HttpStatusCode.BadRequest, "navigation_invalid_request", message)
private fun navigationUnavailable() = ApiException(HttpStatusCode.ServiceUnavailable, "navigation_unavailable", "Navigation provider is unavailable")
private fun navigationMalformed() = ApiException(HttpStatusCode.ServiceUnavailable, "navigation_malformed_response", "Navigation provider returned an invalid response")

private suspend fun ApplicationCall.respondRateLimited(retryAfterSeconds: Long?) {
    val retryAfter = (retryAfterSeconds ?: 60L).coerceAtLeast(1L)
    response.header(HttpHeaders.RetryAfter, retryAfter.toString())
    respond(HttpStatusCode.TooManyRequests, ApiError("navigation_rate_limited", "Navigation provider rate limit reached", requestId()))
}

private suspend fun ApplicationCall.respondNavigationRateLimited(limiter: RateLimiter, key: String, now: Long) {
    val retryAfter = limiter.retryAfterSeconds(key, now).coerceAtLeast(1L)
    response.header(HttpHeaders.RetryAfter, retryAfter.toString())
    respond(HttpStatusCode.TooManyRequests, ApiError("navigation_rate_limited", "Navigation request limit reached", requestId()))
}

private val LANGUAGE_TAG = Regex("^[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*$")

class NavigationTtlCache<V>(
    private val maxEntries: Int = 256,
    private val ttlMillis: Long,
) {
    private data class Entry<V>(val value: V, val expiresAtMillis: Long)
    private val entries = object : LinkedHashMap<String, Entry<V>>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry<V>>?): Boolean = size > maxEntries
    }

    @Synchronized
    fun get(key: String, nowMillis: Long): V? {
        val entry = entries[key] ?: return null
        if (entry.expiresAtMillis <= nowMillis) {
            entries.remove(key)
            return null
        }
        return entry.value
    }

    @Synchronized
    fun put(key: String, value: V, nowMillis: Long) {
        entries[key] = Entry(value, nowMillis + ttlMillis)
    }
}

private fun AppDependencies.routeCacheKey(request: ProviderRouteRequest): String = listOf(
    request.origin.latitude,
    request.origin.longitude,
    request.destination.latitude,
    request.destination.longitude,
    request.languageTag,
    request.alternativesLimit,
    request.routingProfile.wireName,
).joinToString("|")
