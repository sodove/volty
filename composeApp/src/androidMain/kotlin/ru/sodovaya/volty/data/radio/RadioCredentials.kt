package ru.sodovaya.volty.data.radio

import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import ru.sodovaya.volty.domain.radio.RadioKey

data class RadioCredentials(val networkName: String, val passphrase: String, val audioKey: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is RadioCredentials && networkName == other.networkName && passphrase == other.passphrase &&
            audioKey.contentEquals(other.audioKey)

    override fun hashCode(): Int = 31 * (31 * networkName.hashCode() + passphrase.hashCode()) + audioKey.contentHashCode()
    override fun toString(): String = "RadioCredentials(networkName=$networkName, passphrase=<redacted>, audioKey=<redacted>)"
}

/** Everything is HMAC-SHA256 under the server's 32-byte radio key, so only members can compute it. */
object RadioCredentialDerivation {
    private const val DAY_MILLIS = 86_400_000L
    private const val BASE32 = "abcdefghijklmnopqrstuvwxyz234567"

    fun derive(key: RadioKey, utcDay: Long): RadioCredentials = RadioCredentials(
        networkName = "DIRECT-Vy-" + base32Lower(hmac(key, "net|${key.epoch}|$utcDay")).take(10),
        passphrase = Base64.getUrlEncoder().withoutPadding().encodeToString(hmac(key, "psk|${key.epoch}|$utcDay")).take(24),
        // Independent of the day, so a ride crossing midnight keeps its audio key.
        audioKey = hmac(key, "audio|${key.epoch}").copyOf(16),
    )

    /** Today first, then yesterday: a client joining just after UTC midnight still finds a hub from before it. */
    fun joinCandidates(key: RadioKey, nowMillis: Long): List<RadioCredentials> {
        val day = nowMillis / DAY_MILLIS
        return listOf(derive(key, day), derive(key, day - 1))
    }

    private fun hmac(key: RadioKey, message: String): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key.key, "HmacSHA256"))
            doFinal(message.toByteArray(Charsets.UTF_8))
        }

    private fun base32Lower(bytes: ByteArray): String {
        val out = StringBuilder()
        var buffer = 0
        var bits = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                out.append(BASE32[(buffer shr (bits - 5)) and 31])
                bits -= 5
            }
            buffer = buffer and ((1 shl bits) - 1)
        }
        if (bits > 0) out.append(BASE32[(buffer shl (5 - bits)) and 31])
        return out.toString()
    }
}
