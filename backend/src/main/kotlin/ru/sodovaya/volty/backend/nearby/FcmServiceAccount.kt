package ru.sodovaya.volty.backend.nearby

import com.google.auth.oauth2.ServiceAccountCredentials
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

internal class FcmServiceAccount(val project: String, val credentials: ServiceAccountCredentials) {
    override fun toString() = "FcmServiceAccount(<redacted>)"
    companion object {
        /** Local file only: no ADC lookup, remote credential kind or credential-supplied origin. */
        fun load(path: Path, project: String): FcmServiceAccount = try {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Firebase credential loading interrupted")
            require(project.matches(Regex("[a-z][a-z0-9-]{0,127}")))
            require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
            val raw = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { it.readNBytes(65537) }
            require(raw.size in 1..65536)
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString()
            val json = Json.parseToJsonElement(text).jsonObject
            fun field(name: String): String? = (json[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
            require(field("type") == "service_account" && field("project_id") == project)
            require(field("token_uri") == "https://oauth2.googleapis.com/token")
            require("universe_domain" !in json || field("universe_domain") == "googleapis.com")
            val email = field("client_email") ?: error("Missing account")
            require(email.length <= 254 && email.matches(Regex("[A-Za-z0-9._-]+@${Regex.escape(project)}\\.iam\\.gserviceaccount\\.com")))
            // Re-serialize the validated object so duplicate JSON fields cannot diverge across parsers.
            val credentials = ServiceAccountCredentials.fromStream(ByteArrayInputStream(json.toString().toByteArray(Charsets.UTF_8)))
                .createScoped(listOf("https://www.googleapis.com/auth/firebase.messaging")) as ServiceAccountCredentials
            require(credentials.projectId == project && credentials.privateKey != null)
            FcmServiceAccount(project, credentials)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (interrupted: InterruptedException) { Thread.currentThread().interrupt(); throw interrupted }
        catch (_: Exception) {
            // SDK and filesystem exceptions can carry the key, account or secret-mounted path.
            throw IllegalArgumentException("Invalid Firebase service-account configuration")
        }
    }
}
