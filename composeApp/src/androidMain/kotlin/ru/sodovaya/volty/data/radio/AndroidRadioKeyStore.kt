package ru.sodovaya.volty.data.radio

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.sodovaya.volty.domain.nearby.community.invitationDraftId
import ru.sodovaya.volty.domain.radio.RadioKey
import ru.sodovaya.volty.domain.radio.RadioKeyStore
import ru.sodovaya.volty.domain.radio.StoredRadioKey

/**
 * Radio keys per account, AES-GCM encrypted under a per-account Android Keystore key, like the invitation drafts.
 * Anything unreadable (missing Keystore key, tampering, wrong community) reads as absent. Needs device verification:
 * Keystore and SharedPreferences are not available to unit tests.
 */
class AndroidRadioKeyStore(context: Context) : RadioKeyStore {
    private val appContext = context.applicationContext ?: context

    override suspend fun get(actor: String, community: String): StoredRadioKey? = withContext(Dispatchers.IO) {
        invitationDraftId(actor); invitationDraftId(community)
        try {
            val stored = prefs(actor).getString(community, null) ?: return@withContext null
            val raw = Base64.getDecoder().decode(stored)
            val key = existingKey(actor) ?: return@withContext null
            val cipher = Cipher.getInstance(TRANSFORM).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, raw.copyOfRange(0, IV_BYTES)))
                updateAAD("$actor|$community".toByteArray())
            }
            val parts = cipher.doFinal(raw, IV_BYTES, raw.size - IV_BYTES).decodeToString().split('|')
            if (parts.size != 3) return@withContext null
            val material = Base64.getDecoder().decode(parts[2])
            if (material.size != 32) return@withContext null
            StoredRadioKey(RadioKey(community, parts[0].toLong(), material), parts[1].toLong())
        } catch (_: Exception) { null }
    }

    override suspend fun put(actor: String, key: RadioKey, fetchedAtMillis: Long) {
        withContext(Dispatchers.IO) {
            invitationDraftId(actor); invitationDraftId(key.communityId)
            require(key.key.size == 32)
            val plain = "${key.epoch}|$fetchedAtMillis|${Base64.getEncoder().encodeToString(key.key)}".toByteArray()
            val cipher = Cipher.getInstance(TRANSFORM).apply {
                init(Cipher.ENCRYPT_MODE, createdKey(actor))
                updateAAD("$actor|${key.communityId}".toByteArray())
            }
            val sealed = cipher.iv + cipher.doFinal(plain)
            check(prefs(actor).edit().putString(key.communityId, Base64.getEncoder().encodeToString(sealed)).commit())
        }
    }

    private fun prefs(actor: String) = appContext.getSharedPreferences("nearby_radio_keys_$actor", Context.MODE_PRIVATE)
    private fun alias(actor: String) = "volty.nearby.radio-keys.$actor"
    private fun keystore() = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun existingKey(actor: String): SecretKey? = keystore().getKey(alias(actor), null) as? SecretKey

    private fun createdKey(actor: String): SecretKey {
        existingKey(actor)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(KeyGenParameterSpec.Builder(alias(actor), KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
                .build())
            generateKey()
        }
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}
