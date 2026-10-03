package ru.sodovaya.volty.backend.nearby

import java.security.MessageDigest
import java.util.Base64

/** Caller owns the random token and stable retry payload. Never persist or return the raw token. */
object InvitationTokens {
    fun hash(raw: String): String {
        require(raw.length == 43 && raw.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' })
        val bytes = Base64.getUrlDecoder().decode(raw)
        require(bytes.size == 32 && Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) == raw)
        return digest(bytes)
    }
    internal fun payloadHash(vararg parts: String): String = digest(parts.joinToString("\u0000").toByteArray(Charsets.UTF_8))
    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
