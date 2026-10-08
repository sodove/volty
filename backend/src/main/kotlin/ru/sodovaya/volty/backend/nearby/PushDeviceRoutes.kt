package ru.sodovaya.volty.backend.nearby

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.put
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import ru.sodovaya.volty.backend.ApiException
import ru.sodovaya.volty.backend.AppDependencies
import java.io.ByteArrayOutputStream

@Serializable
private class PushDeviceRequest(val installationId: String, val installationKey: String,
                                val generation: Long, val bindingId: String, val token: String? = null) {
    override fun toString() = "PushDeviceRequest(<redacted>)"
}

/** Only mutates the authenticated installation binding; no token listing endpoint. */
fun Route.installNearbyPushDeviceRoutes(dependencies: AppDependencies) {
    authenticate("auth-jwt") {
        put("/v2/nearby/push/device") {
            val actor = call.nearbyActor(dependencies)
            val issued = call.principal<JWTPrincipal>()?.payload?.issuedAt?.time?.div(1000)
                ?: throw ApiException(HttpStatusCode.Unauthorized, "unauthorized", "Authentication required")
            val port = dependencies.nearbyPushDevices
                ?: throw ApiException(HttpStatusCode.ServiceUnavailable, "push_unavailable", "Push registration unavailable")
            if (call.request.contentType().withoutParameters() != ContentType.Application.Json) invalidPushRequest()
            val bytes = call.pushRequestBytes()
            val request = try { dependencies.json.decodeFromString<PushDeviceRequest>(bytes.toString(Charsets.UTF_8)) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { invalidPushRequest() }
            val registration = PushDeviceRegistration(actor, issued, request.installationId,
                request.installationKey, request.generation, request.bindingId, request.token)
            if (!validPushRegistration(registration, 0)) invalidPushRequest()
            when (withContext(Dispatchers.IO) { port.apply(registration) }) {
                PushDeviceDecision.APPLIED, PushDeviceDecision.REPLAY -> call.respond(HttpStatusCode.NoContent)
                PushDeviceDecision.STALE -> throw ApiException(HttpStatusCode.Conflict, "push_binding_stale", "Push binding has changed")
                PushDeviceDecision.TOKEN_CONFLICT -> throw ApiException(HttpStatusCode.Conflict, "push_token_conflict", "Push token requires renewal")
                PushDeviceDecision.ACCESS_DENIED -> throw ApiException(HttpStatusCode.Forbidden, "push_access_denied", "Push binding unavailable")
                PushDeviceDecision.INVALID -> invalidPushRequest()
            }
        }
    }
}

private const val MAXIMUM_PUSH_REQUEST_BYTES = 8192
private suspend fun ApplicationCall.pushRequestBytes(): ByteArray {
    if ((request.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: 0) > MAXIMUM_PUSH_REQUEST_BYTES) oversizedPushRequest()
    return try {
        val channel = receiveChannel()
        val buffer = ByteArray(1024)
        val body = ByteArrayOutputStream()
        while (body.size() <= MAXIMUM_PUSH_REQUEST_BYTES) {
            val read = channel.readAvailable(buffer, 0, minOf(buffer.size, MAXIMUM_PUSH_REQUEST_BYTES + 1 - body.size()))
            if (read == -1) break
            if (read > 0) body.write(buffer, 0, read)
        }
        if (body.size() > MAXIMUM_PUSH_REQUEST_BYTES) oversizedPushRequest()
        body.toByteArray()
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: ApiException) { throw failure }
    catch (_: Exception) { invalidPushRequest() }
}

private fun invalidPushRequest(): Nothing = throw ApiException(HttpStatusCode.BadRequest, "invalid_push_request", "Push registration is invalid")
private fun oversizedPushRequest(): Nothing = throw ApiException(HttpStatusCode.PayloadTooLarge, "push_request_too_large", "Push registration is too large")
