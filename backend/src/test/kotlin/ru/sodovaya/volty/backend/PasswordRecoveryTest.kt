package ru.sodovaya.volty.backend

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import kotlin.test.*

class PasswordRecoveryTest {
    private class Delivery(private val fail: Boolean = false) : PasswordResetDelivery {
        override val available = true
        val sent = mutableListOf<Pair<String, String>>()
        override suspend fun send(email: String, token: String) {
            sent += email to token
            if (fail) error("private transport details must not reach the response")
        }
    }
    private class Store : BackendStore {
        val user = UserRecord("rider-id", "rider@example.test", "unused", "Rider", true)
        val tokens = mutableListOf<Triple<String, String, Long>>()
        var passwordHash: String? = null
        var updateCount = 1
        val consumed = mutableSetOf<String>()
        override fun findUserByEmail(email: String) = user.takeIf { it.email == email }
        override fun createOneTimeToken(userId: String, purpose: String, hash: String, expiresAt: Long): Int {
            assertEquals(user.id, userId)
            tokens += Triple(purpose, hash, expiresAt)
            return 1
        }
        override fun consumeOneTimeToken(purpose: String, hash: String, now: Long): String? =
            tokens.firstOrNull { it.first == purpose && it.second == hash && it.third > now && hash !in consumed }
                ?.let { consumed += hash; user.id }
        override fun updatePassword(userId: String, passwordHash: String, now: Long): Int {
            assertEquals(user.id, userId)
            if (updateCount == 1) this.passwordHash = passwordHash
            return updateCount
        }
    }

    @Test fun known_account_receives_the_issued_token_but_http_does_not() = testApplication {
        val store = Store()
        val delivery = Delivery()
        application { module(AppDependencies(AppConfig.forTests(), store, testMode = true, passwordResetDelivery = delivery)) }
        val before = nowMillis()
        val response = client.post("/v1/auth/password-reset/request") {
            contentType(ContentType.Application.Json); setBody("""{"email":" RIDER@example.test "}""")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
        val (email, token) = delivery.sent.single()
        assertEquals(store.user.email, email)
        assertEquals("PASSWORD_RESET", store.tokens.single().first)
        assertEquals(sha256(token), store.tokens.single().second)
        assertTrue(store.tokens.single().third in (before + 3_600_000)..(nowMillis() + 3_600_000))
        assertFalse(response.bodyAsText().contains(token))
    }

    @Test fun unknown_account_has_the_same_receipt_without_email_or_token() = testApplication {
        val store = Store()
        val delivery = Delivery()
        application { module(AppDependencies(AppConfig.forTests(), store, testMode = true, passwordResetDelivery = delivery)) }
        val response = client.post("/v1/auth/password-reset/request") {
            contentType(ContentType.Application.Json); setBody("""{"email":"unknown@example.test"}""")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
        assertTrue(delivery.sent.isEmpty())
        assertTrue(store.tokens.isEmpty())
    }

    @Test fun smtp_failure_is_retryable_and_does_not_leak_transport_details() = testApplication {
        val store = Store()
        val delivery = Delivery(fail = true)
        application { module(AppDependencies(AppConfig.forTests(), store, testMode = true, passwordResetDelivery = delivery)) }
        val response = client.post("/v1/auth/password-reset/request") {
            contentType(ContentType.Application.Json); setBody("""{"email":"rider@example.test"}""")
        }
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertFalse(response.bodyAsText().contains("private transport"))
        assertEquals(1, delivery.sent.size)
    }

    @Test fun repeated_requests_cannot_flood_a_known_or_unknown_mailbox() = testApplication {
        val store = Store()
        val delivery = Delivery()
        application { module(AppDependencies(AppConfig.forTests(), store, testMode = true, passwordResetDelivery = delivery)) }
        for (email in listOf(store.user.email, "unknown@example.test")) {
            fun body() = """{"email":"$email"}"""
            val first = client.post("/v1/auth/password-reset/request") {
                contentType(ContentType.Application.Json); setBody(body())
            }
            assertEquals(HttpStatusCode.Accepted, first.status)
            val second = client.post("/v1/auth/password-reset/request") {
                contentType(ContentType.Application.Json); setBody(body())
            }
            assertEquals(HttpStatusCode.TooManyRequests, second.status)
        }
        assertEquals(1, delivery.sent.size)
        assertEquals(1, store.tokens.size)
    }

    @Test fun issued_link_changes_password_once_and_invalid_input_does_not_consume_it() = testApplication {
        val store = Store()
        val delivery = Delivery()
        application { module(AppDependencies(AppConfig.forTests(), store, testMode = true, passwordResetDelivery = delivery)) }
        client.post("/v1/auth/password-reset/request") {
            contentType(ContentType.Application.Json); setBody("""{"email":"rider@example.test"}""")
        }
        val token = delivery.sent.single().second
        suspend fun confirm(password: String) = client.post("/v1/auth/password-reset") {
            contentType(ContentType.Application.Json); setBody("""{"token":"$token","newPassword":"$password"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, confirm("short").status)
        assertTrue(store.consumed.isEmpty())
        assertNull(store.passwordHash)
        assertEquals(HttpStatusCode.OK, confirm("new-test-password").status)
        assertTrue(org.mindrot.jbcrypt.BCrypt.checkpw("new-test-password", store.passwordHash))
        assertEquals(HttpStatusCode.BadRequest, confirm("another-password").status)
        assertTrue(org.mindrot.jbcrypt.BCrypt.checkpw("new-test-password", store.passwordHash))
    }

    @Test fun missing_account_cannot_report_a_successful_password_change() = testApplication {
        val store = Store().apply { updateCount = 0 }
        val delivery = Delivery()
        application { module(AppDependencies(AppConfig.forTests(), store, testMode = true, passwordResetDelivery = delivery)) }
        client.post("/v1/auth/password-reset/request") {
            contentType(ContentType.Application.Json); setBody("""{"email":"rider@example.test"}""")
        }
        val token = delivery.sent.single().second
        for (endpoint in listOf("/v1/auth/password-reset", "/v1/auth/password-reset/confirm")) {
            store.consumed.clear()
            val response = client.post(endpoint) {
                contentType(ContentType.Application.Json); setBody("""{"token":"$token","newPassword":"new-test-password"}""")
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertNull(store.passwordHash)
        }
    }

    @Test fun unconfigured_email_recovery_does_not_acknowledge_a_nonexistent_delivery()=testApplication {
        val store=object:BackendStore {override fun findUserByEmail(email:String):UserRecord?=null}
        application {module(AppDependencies(AppConfig.forTests(),store,testMode=true))}
        val response=client.post("/v1/auth/password-reset/request") {
            contentType(ContentType.Application.Json);setBody("""{"email":"rider@example.test"}""")
        }
        assertEquals(HttpStatusCode.ServiceUnavailable,response.status)
    }

    @Test fun reset_page_exists_without_reading_a_token_or_mutating_an_account()=testApplication {
        application {module(AppDependencies(AppConfig.forTests(),object:BackendStore {},testMode=true))}
        val response=client.get("/reset-password")
        assertEquals(HttpStatusCode.OK,response.status)
        assertEquals("no-store",response.headers[HttpHeaders.CacheControl])
        assertEquals("no-referrer",response.headers["Referrer-Policy"])
        val html = response.bodyAsText()
        assertFalse(html.contains("__CSP_NONCE__"))
        val nonce = Regex("nonce=\"([^\"]+)\"").find(html)!!.groupValues[1]
        assertTrue(response.headers["Content-Security-Policy"]!!.contains("script-src 'nonce-$nonce'"))
        assertTrue(response.headers["Content-Security-Policy"]!!.contains("style-src 'nonce-$nonce'"))
        val next = client.get("/reset-password")
        assertFalse(next.headers["Content-Security-Policy"]!!.contains(nonce))
    }
}
