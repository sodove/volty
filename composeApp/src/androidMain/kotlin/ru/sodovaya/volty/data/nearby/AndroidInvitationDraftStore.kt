package ru.sodovaya.volty.data.nearby

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.sodovaya.volty.domain.nearby.community.*

/**
 * Account-specific encrypted draft storage. All native preferences/Keystore work is on IO.
 * Production logout retirement/cleanup is a subsequent binding, not implicit in this store.
 * Native provider and process-death behavior require device verification.
 */
class AndroidInvitationDraftStore(context: Context, actorId: String) : InvitationDraftStore {
    override val actorId = actorId.also { invitationDraftId(it) }
    private val appContext = context.applicationContext ?: context
    private val alias = "volty.nearby.invitation-drafts.$actorId"
    private val delegate by lazy {
        EncryptedInvitationDraftStore(actorId,
            PreferenceInvitationDraftRecords(actorId, AndroidInvitationPreferenceAccess(appContext, "nearby_invitation_drafts_$actorId")),
            JcaInvitationDraftCipher({ key(createIfMissing = true) }, { key(createIfMissing = false) }))
    }

    override suspend fun read(invitationId: String): InvitationCreationDraft? = withContext(Dispatchers.IO) { delegate.read(invitationId) }
    override suspend fun retain(draft: InvitationCreationDraft): InvitationCreationDraft = withContext(Dispatchers.IO) { delegate.retain(draft) }

    private fun key(createIfMissing: Boolean): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val existing = store.getKey(alias, null)
        if (existing != null) return existing as? SecretKey ?: error("Invitation draft unavailable")
        check(createIfMissing && !store.containsAlias(alias))
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
                .build())
            generateKey()
        }
    }
    private companion object { const val KEYSTORE = "AndroidKeyStore" }
}

private class AndroidInvitationPreferenceAccess(context: Context, name: String) : InvitationPreferenceAccess {
    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    private val file = File(context.dataDir, "shared_prefs/$name.xml")
    private val backup = File(context.dataDir, "shared_prefs/$name.xml.bak")
    override fun hasStoredFile(): Boolean = file.exists() || backup.exists()
    override fun value(key: String): String? = prefs.getString(key, null)
    override fun commit(values: Map<String, String>): Boolean {
        val editor = prefs.edit()
        values.forEach { (key, value) -> editor.putString(key, value) }
        return editor.commit()
    }
}
