package ru.sodovaya.volty.backend

import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import jakarta.mail.Folder
import jakarta.mail.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Tag
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlin.test.*

/** Explicitly opted in: delivers one recovery email only to the configured account's own mailbox. */
@Tag("smtp")
class SmtpRecoveryIntegrationTest {
    @Test fun real_recovery_email_arrives_with_the_issued_fragment_link() = testApplication {
        val path = Path.of(checkNotNull(System.getenv("VOLTY_SMTP_SECRET_FILE")))
        val account = Json.parseToJsonElement(Files.readString(path).removePrefix("\uFEFF")).jsonObject
        val username = account.getValue("username").jsonPrimitive.content
        val password = account.getValue("password").jsonPrimitive.content
        val host = account.getValue("host").jsonPrimitive.content
        val sender = SmtpPasswordResetDelivery.fromFile(path)
        var issued: String? = null
        var storedHash: String? = null
        val store = object : BackendStore {
            override fun findUserByEmail(email: String) = UserRecord("isolated-mail-test", username, "unused", "Test", true)
                .takeIf { email == username }
            override fun createOneTimeToken(userId: String, purpose: String, hash: String, expiresAt: Long): Int {
                storedHash = hash; return 1
            }
        }
        val delivery = object : PasswordResetDelivery {
            override val available = true
            override suspend fun send(email: String, token: String) {
                issued = token
                sender.send(email, token)
            }
        }
        application { module(AppDependencies(AppConfig.forTests(), store, testMode = true, passwordResetDelivery = delivery)) }
        val response = client.post("/v1/auth/password-reset/request") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("email", username) }.toString())
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
        val token = checkNotNull(issued)
        assertTrue(storedHash == sha256(token))
        val received = withContext(Dispatchers.IO) {
            val properties = Properties().apply {
                setProperty("mail.imaps.ssl.checkserveridentity", "true")
                setProperty("mail.imaps.connectiontimeout", "5000")
                setProperty("mail.imaps.timeout", "10000")
            }
            Session.getInstance(properties).getStore("imaps").use { mailbox ->
                mailbox.connect(host, 993, username, password)
                val inbox = mailbox.getFolder("INBOX")
                inbox.open(Folder.READ_ONLY)
                try {
                    var found = false
                    repeat(15) {
                        if (!found) {
                            val count = inbox.messageCount
                            if (count > 0) found = inbox.getMessages(maxOf(1, count - 24), count).any {
                                it.isMimeType("text/plain") && (it.content as? String)
                                    ?.contains("https://volty.sodove.ru/reset-password#token=$token") == true
                            }
                            if (!found) delay(1000)
                        }
                    }
                    found
                } finally { inbox.close(false) }
            }
        }
        assertTrue(received, "Recovery email was not received in the owned mailbox")
    }
}
