package ru.sodovaya.volty.backend

import com.sun.net.httpserver.HttpServer
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class OfflineRegionRoutesTest {
    @Test fun `proxy preserves signed bytes and component ranges without exposing worker admin`() = testApplication {
        val issued = CopyOnWriteArrayList<String>()
        val upstream = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        upstream.createContext("/") { exchange ->
            issued += "${exchange.requestMethod} ${exchange.requestURI} ${exchange.requestHeaders.getFirst("Range") ?: ""}".trim()
            val partial = exchange.requestHeaders.getFirst("Range") != null
            val bytes = if (partial) "abc" else "{\"signed\":\"bytes preserved\"}"
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.responseHeaders.add("ETag", "\"release-hash\"")
            if (partial) exchange.responseHeaders.add("Content-Range", "bytes 0-2/10")
            exchange.sendResponseHeaders(if (partial) 206 else 200, bytes.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(bytes.toByteArray()) }
        }
        upstream.start()
        try {
            application { module(AppDependencies(AppConfig.forTests().copy(offlineManagerUrl = "http://127.0.0.1:${upstream.address.port}"), object : BackendStore {}, testMode = true)) }
            assertEquals("{\"signed\":\"bytes preserved\"}", client.get("/offline/catalog.json").bodyAsText())
            assertEquals(HttpStatusCode.OK, client.post("/offline/regions/ekb/ensure?releaseVersion=v1").status)
            val ranged = client.get("/offline/regions/ekb/v1/map/ekb.pmtiles") { header("Range", "bytes=0-2") }
            assertEquals(HttpStatusCode.PartialContent, ranged.status)
            assertEquals("bytes 0-2/10", ranged.headers["Content-Range"])
            assertEquals("3", ranged.headers["Content-Length"])
            assertEquals("abc", ranged.bodyAsText())
            assertEquals(HttpStatusCode.NotFound, client.post("/offline/prune").status)
            assertEquals(HttpStatusCode.NotFound, client.get("/offline/.staging/secret").status)
            assertEquals(listOf("GET /catalog.json", "POST /regions/ekb/ensure?releaseVersion=v1", "GET /regions/ekb/v1/map/ekb.pmtiles bytes=0-2"), issued)
        } finally { upstream.stop(0) }
    }

    @Test fun `invalid coordinates never reach the worker`() = testApplication {
        application { module(AppDependencies(AppConfig.forTests().copy(offlineManagerUrl = "http://127.0.0.1:1"), object : BackendStore {}, testMode = true)) }
        assertEquals(HttpStatusCode.BadRequest, client.get("/offline/resolve?lat=NaN&lon=60").status)
        val failed = client.get("/offline/catalog.json")
        assertEquals(HttpStatusCode.ServiceUnavailable, failed.status)
        assertFalse(failed.bodyAsText().contains("127.0.0.1"))
    }
}
