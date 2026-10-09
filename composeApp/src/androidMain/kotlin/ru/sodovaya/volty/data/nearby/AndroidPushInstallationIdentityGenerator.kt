package ru.sodovaya.volty.data.nearby

import java.security.SecureRandom
import java.util.UUID

internal class AndroidPushInstallationIdentityGenerator : PushInstallationIdentityGenerator {
    private val random = SecureRandom()
    override fun uuid(): String = UUID.randomUUID().toString()
    override fun capability(): String {
        val bytes = ByteArray(32).also(random::nextBytes)
        val digits = "0123456789abcdef"
        return buildString(64) {for (byte in bytes) {
            append(digits[(byte.toInt() and 255) ushr 4]); append(digits[byte.toInt() and 15])
        }}
    }
}
