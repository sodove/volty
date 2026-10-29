package ru.sodovaya.volty.backend

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.*

class AccountRemovalPageTest {
    @Test fun public_page_is_read_only_and_has_fresh_script_and_style_nonces() = testApplication {
        // Any store access fails: visiting the Google Play link cannot require an account or delete one.
        application { module(AppDependencies(AppConfig.forTests(), object : BackendStore {}, testMode = true)) }
        val response = client.get("/remove_account")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.headers[HttpHeaders.ContentType]!!.startsWith("text/html"))
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        assertEquals("no-referrer", response.headers["Referrer-Policy"])
        val html = response.bodyAsText()
        assertTrue(html.contains("Volty"))
        assertFalse(html.contains("__CSP_NONCE__"))
        val nonce = Regex("nonce=\"([^\"]+)\"").find(html)!!.groupValues[1]
        val policy = response.headers["Content-Security-Policy"]!!
        assertTrue(policy.contains("script-src 'nonce-$nonce'"))
        assertTrue(policy.contains("style-src 'nonce-$nonce'"))
        assertTrue(policy.contains("frame-ancestors 'none'"))
        assertFalse(client.get("/remove_account").headers["Content-Security-Policy"]!!.contains(nonce))
    }
}
