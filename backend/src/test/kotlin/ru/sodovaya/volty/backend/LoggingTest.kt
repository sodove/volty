package ru.sodovaya.volty.backend

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.slf4j.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LoggingTest {
    @Test fun successfulPollingIsQuietButErrorsAndOtherRequestsRemainVisible() {
        val logger = LoggerFactory.getLogger("io.ktor.test") as Logger
        val events = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(events)
        try {
            testApplication {
                application {
                    installBackendCallLogging()
                    routing {
                        get("/v2/nearby/calls/incoming") {
                            val status = call.request.queryParameters["status"]?.toInt() ?: 200
                            call.respond(HttpStatusCode.fromValue(status))
                        }
                        post("/v2/nearby/calls/incoming") { call.respond(HttpStatusCode.OK) }
                        get("/health") { call.respond(HttpStatusCode.OK) }
                    }
                }
                assertEquals(HttpStatusCode.OK, client.get("/v2/nearby/calls/incoming").status)
                assertEquals(HttpStatusCode.NoContent, client.get("/v2/nearby/calls/incoming?status=204").status)
                assertEquals(HttpStatusCode.Unauthorized, client.get("/v2/nearby/calls/incoming?status=401").status)
                assertEquals(HttpStatusCode.InternalServerError, client.get("/v2/nearby/calls/incoming?status=500").status)
                client.post("/v2/nearby/calls/incoming")
                client.get("/health")
            }
            val requests = events.list.map { it.formattedMessage }.filter { " - /" in it }
            assertEquals(4, requests.size, requests.joinToString("\n"))
            assertTrue(requests.any { "401" in it && "/v2/nearby/calls/incoming" in it })
            assertTrue(requests.any { "500" in it && "/v2/nearby/calls/incoming" in it })
            assertTrue(requests.any { "POST" in it && "/v2/nearby/calls/incoming" in it })
            assertTrue(requests.any { "/health" in it })
        } finally {
            logger.detachAppender(events)
            events.stop()
        }
    }
}
