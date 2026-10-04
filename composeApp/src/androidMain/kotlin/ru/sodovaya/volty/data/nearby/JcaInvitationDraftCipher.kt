package ru.sodovaya.volty.data.nearby

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import ru.sodovaya.volty.domain.nearby.community.*

/** Platform JCA, with production keys supplied by the account's Android Keystore alias. */
class JcaInvitationDraftCipher(
    private val encryptionKey: () -> SecretKey,
    private val decryptionKey: () -> SecretKey,
) : InvitationDraftCipher {
    constructor(key: () -> SecretKey) : this(key, key)
    override fun encrypt(draft: InvitationCreationDraft): String = safely {
        val plaintext = InvitationDraftPayloadCodec.encode(draft)
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            // Android Keystore requires provider-generated IVs for randomized encryption.
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
            require(cipher.iv.size == IV_BYTES)
            cipher.updateAAD(aad(draft.actorId, draft.invitationId))
            "1.${encode(cipher.iv)}.${checkedInvitationDraftGcmBody(plaintext.size, cipher.doFinal(plaintext))}"
        } finally { plaintext.fill(0) }
    }

    override fun decrypt(actor: String, invitation: String, encrypted: String): InvitationCreationDraft = safely {
        val associated = aad(actor, invitation)
        require(encrypted.length <= MAX_ENVELOPE_CHARS)
        val parts = encrypted.split('.')
        require(parts.size == 3 && parts[0] == "1")
        val iv = decode(parts[1]).also { require(it.size == IV_BYTES) }
        val body = decode(parts[2]).also { require(it.size in TAG_BYTES + 1..MAX_PAYLOAD_BYTES + TAG_BYTES) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, decryptionKey(), GCMParameterSpec(TAG_BYTES * 8, iv))
        cipher.updateAAD(associated)
        val plaintext = cipher.doFinal(body)
        try { InvitationDraftPayloadCodec.decode(actor, invitation, plaintext) }
        finally { plaintext.fill(0) }
    }

    private fun aad(actor: String, invitation: String): ByteArray {
        invitationDraftId(actor); invitationDraftId(invitation)
        return "volty.nearby.invitation-draft.v1/$actor/$invitation".encodeToByteArray()
    }
    private fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    private fun decode(value: String): ByteArray = Base64.getUrlDecoder().decode(value).also { require(encode(it) == value) }
    private inline fun <T> safely(block: () -> T): T = try { block() }
    catch (_: Exception) { throw IllegalStateException("Invitation draft unavailable") }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BYTES = 16
        const val MAX_PAYLOAD_BYTES = 1_024
        const val MAX_ENVELOPE_CHARS = 4_096
    }
}

/** Provider defaults must still produce the envelope's exact 128-bit authentication tag. */
internal fun checkedInvitationDraftGcmBody(plaintextSize: Int, body: ByteArray): String {
    require(plaintextSize in 1..1_024 && body.size == plaintextSize + 16)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(body)
}
