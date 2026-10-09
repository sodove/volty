package ru.sodovaya.volty.backend

import jakarta.mail.Message
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

/** Credentials come only from a protected runtime file, never from API input. */
class SmtpPasswordResetDelivery private constructor(private val account: Account) : PasswordResetDelivery {
    override val available = true

    override suspend fun send(email: String, token: String): Unit = withContext(Dispatchers.IO) {
        val properties = Properties().apply {
            setProperty("mail.smtp.host", account.host)
            setProperty("mail.smtp.port", account.port.toString())
            setProperty("mail.smtp.auth", "true")
            setProperty("mail.smtp.connectiontimeout", "5000")
            setProperty("mail.smtp.timeout", "10000")
            setProperty("mail.smtp.writetimeout", "10000")
            setProperty("mail.smtp.ssl.checkserveridentity", "true")
            if (account.port == 465) setProperty("mail.smtp.ssl.enable", "true") else {
                setProperty("mail.smtp.starttls.enable", "true")
                setProperty("mail.smtp.starttls.required", "true")
            }
        }
        val session = Session.getInstance(properties).apply { debug = false }
        val message = MimeMessage(session).apply {
            setFrom(InternetAddress(account.from, "Volty", "UTF-8"))
            setRecipient(Message.RecipientType.TO, InternetAddress(email, true))
            setSubject("Смена пароля Volty", "UTF-8")
            setText("Чтобы изменить пароль Volty, откройте ссылку:\n\n" +
                "https://volty.sodove.ru/reset-password#token=$token\n\n" +
                "Ссылка действует один час и может быть использована один раз.\n" +
                "Если вы не запрашивали смену пароля, просто проигнорируйте письмо.", "UTF-8")
        }
        session.getTransport("smtp").use { transport ->
            transport.connect(account.host, account.port, account.username, account.password)
            transport.sendMessage(message, message.allRecipients)
        }
    }

    // Deliberately not a data class: accidental diagnostics must not stringify credentials.
    @Serializable
    private class Account(val host: String, val port: Int, val username: String, val password: String, val from: String)

    companion object {
        fun fromEnvironment(): PasswordResetDelivery {
            val path = System.getenv("VOLTY_SMTP_SECRET_FILE")?.takeIf { it.isNotBlank() }
                ?: return UnavailablePasswordResetDelivery
            return fromFile(Path.of(path))
        }

        internal fun fromFile(path: Path): PasswordResetDelivery {
            val account = try {
                require(Files.size(path) in 1..16_384)
                Json.decodeFromString<Account>(Files.readString(path).removePrefix("\uFEFF"))
            } catch (_: Exception) {
                error("SMTP credential file is unreadable or invalid")
            }
            require(account.host.matches(Regex("[a-zA-Z0-9.-]+")) && account.port in setOf(465, 587) &&
                Validation.email(account.username).isSuccess && Validation.email(account.from).isSuccess &&
                account.password.isNotEmpty()) { "SMTP credential configuration is invalid" }
            return SmtpPasswordResetDelivery(account)
        }
    }
}
