package ru.sodovaya.volty.data.social

import android.content.Context
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Small Keystore-backed token store for the optional social session.
 * Preferences contain ciphertext only; the AES key is non-exportable and
 * remains owned by Android Keystore.
 */
class AndroidSocialCredentialStore(context: Context) : SocialCredentialStore {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override suspend fun read(): SocialCredentials? = synchronized(lock) {
        val iv = prefs.getString(KEY_IV, null) ?: return@synchronized null
        val payload = prefs.getString(KEY_PAYLOAD, null) ?: return@synchronized null
        runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, decode(iv)))
            val value = cipher.doFinal(decode(payload)).toString(StandardCharsets.UTF_8)
            val separator = value.indexOf(SEP)
            require(separator > 0)
            SocialCredentials(
                accessToken = value.substring(0, separator),
                refreshToken = value.substring(separator + SEP.length)
                    .substringBefore(SEP_EXPIRES),
                expiresAtEpochMillis = value.substringAfter(SEP_EXPIRES).toLong(),
            )
        }.getOrNull()
    }

    override suspend fun write(credentials: SocialCredentials) = synchronized(lock) {
        require(credentials.accessToken.isNotBlank())
        require(credentials.refreshToken.isNotBlank())
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val value = "${credentials.accessToken}$SEP${credentials.refreshToken}$SEP_EXPIRES${credentials.expiresAtEpochMillis}"
        prefs.edit()
            .putString(KEY_IV, encode(cipher.iv))
            .putString(KEY_PAYLOAD, encode(cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))))
            .apply()
    }

    override suspend fun clear() = synchronized(lock) {
        prefs.edit().clear().apply()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(ALGORITHM, ANDROID_KEYSTORE).run {
            init(android.security.keystore.KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                    android.security.keystore.KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(false)
                .build())
            generateKey()
        }
    }

    private fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun decode(value: String): ByteArray = Base64.decode(value, Base64.NO_WRAP)

    private companion object {
        const val PREFS_NAME = "volty_social_credentials"
        const val KEY_IV = "iv"
        const val KEY_PAYLOAD = "payload"
        const val KEY_ALIAS = "volty.social.session"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALGORITHM = "AES"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val SEP = "\u0000"
        const val SEP_EXPIRES = "\u0001"
        val lock = Any()
    }
}
