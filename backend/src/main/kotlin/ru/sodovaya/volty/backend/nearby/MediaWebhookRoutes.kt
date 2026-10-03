package ru.sodovaya.volty.backend.nearby

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.sodovaya.volty.backend.AppDependencies
import java.io.ByteArrayOutputStream

enum class MediaWebhookDisposition { ACCEPTED, RETRY, CONFLICT }

/** ACCEPTED requires durable insert/dedup, not an in-memory publication. */
fun interface MediaWebhookEventPort { fun accept(event: VerifiedMediaWebhook): MediaWebhookDisposition }
data class MediaWebhookEndpoint(val verifier: MediaWebhookVerifier, val events: MediaWebhookEventPort)

fun Route.installNearbyMediaWebhookRoutes(dependencies: AppDependencies) {
    post("/v2/nearby/media/webhook") {
        call.response.header(HttpHeaders.CacheControl, "no-store")
        val endpoint = dependencies.nearbyMediaWebhook
        if (endpoint == null) { call.respond(HttpStatusCode.ServiceUnavailable); return@post }
        val maximum = MediaWebhookVerifier.MAXIMUM_BODY_BYTES
        if ((call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: 0) > maximum) {
            call.respond(HttpStatusCode.PayloadTooLarge); return@post
        }
        val bytes = try {
            val channel = call.receiveChannel()
            val buffer = ByteArray(8192)
            val body = ByteArrayOutputStream()
            while (body.size() <= maximum) {
                val read = channel.readAvailable(buffer, 0, minOf(buffer.size, maximum + 1 - body.size()))
                if (read == -1) break
                if (read > 0) body.write(buffer, 0, read)
            }
            if (body.size() > maximum) { call.respond(HttpStatusCode.PayloadTooLarge); return@post }
            body.toByteArray()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { call.respond(HttpStatusCode.BadRequest); return@post }
        when (val verified = endpoint.verifier.verify(bytes, call.request.headers[HttpHeaders.Authorization])) {
            is MediaWebhookResult.Ignored -> call.respond(HttpStatusCode.NoContent)
            is MediaWebhookResult.Rejected -> call.respond(when (verified.reason) {
                MediaWebhookFailure.BODY_TOO_LARGE -> HttpStatusCode.PayloadTooLarge
                MediaWebhookFailure.UNAUTHORIZED -> HttpStatusCode.Unauthorized
                else -> HttpStatusCode.BadRequest
            })
            is MediaWebhookResult.Accepted -> {
                val disposition = try { withContext(Dispatchers.IO) { endpoint.events.accept(verified.event) } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { MediaWebhookDisposition.RETRY }
                call.respond(when (disposition) {
                    MediaWebhookDisposition.ACCEPTED -> HttpStatusCode.NoContent
                    MediaWebhookDisposition.RETRY -> HttpStatusCode.ServiceUnavailable
                    MediaWebhookDisposition.CONFLICT -> HttpStatusCode.Conflict
                })
            }
        }
    }
}
